package com.realityforge.scanner.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ProjectRepository(private val context: Context, val dao: ScanDao) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid project identifier." }
        return File(root, id).apply { mkdirs() }
    }
    fun artifact(id: String, key: String, extension: String): File {
        require(key.matches(Regex("sparse-(?:m[0-9]{2}-)?r[0-9]+-[0-9a-f]{16}")) && extension in setOf("json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png")) { "Invalid artifact key." }
        return File(directory(id), "$key.$extension")
    }
    /** Read only the completed revision, bounded before JSON allocation and rechecked after I/O. */
    suspend fun currentPreview(id: String): JSONObject? = withContext(Dispatchers.IO) {
        val before = dao.project(id) ?: return@withContext null
        if (before.resultRevision == null || before.resultRevision != before.revision) return@withContext null
        val key = requireNotNull(before.resultKey) { "Completed scan has no artifact identity." }
        val encodedRevision = Regex("sparse-(?:m[0-9]{2}-)?r([0-9]+)-[0-9a-f]{16}")
            .matchEntire(key)?.groupValues?.get(1)?.toLongOrNull()
        check(encodedRevision == before.revision) { "Saved model revision does not match this project." }
        val file = artifact(id, key, "json")
        val bytes = file.inputStream().use { input ->
            val size = file.length()
            require(size in 2..MAX_PREVIEW_BYTES) { "Saved preview exceeds the memory limit." }
            val data = ByteArray(size.toInt())
            var cursor = 0
            while (cursor < data.size) {
                coroutineContext.ensureActive()
                val count = input.read(data, cursor, minOf(65536, data.size - cursor))
                check(count > 0) { "Saved preview is truncated." }
                cursor += count
            }
            check(input.read() == -1) { "Saved preview changed during loading." }
            data
        }
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '\"') quoted = false }
            else if (c == '\"') quoted = true
            else if (c == '{' || c == '[') { depth++; require(depth <= 32) { "Saved preview JSON is too deeply nested." } }
            else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
        }
        require(depth == 0 && !quoted) { "Saved preview JSON is incomplete." }
        val reader = org.json.JSONTokener(text)
        val json = reader.nextValue() as? JSONObject ?: error("Saved preview is not an object.")
        require(reader.nextClean() == '\u0000') { "Unexpected data after preview." }
        val storedRevision = json.get("revision")
        check((storedRevision is Int || storedRevision is Long) &&
            (storedRevision as Number).toLong() == before.revision && json.getString("artifactKey") == key) {
            "Saved preview belongs to a different reconstruction."
        }
        val after = dao.project(id)
        if (after == null || after.revision != before.revision || after.resultRevision != before.resultRevision || after.resultKey != key)
            return@withContext null
        json
    }
    companion object { const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024 }
    fun photoFile(photo: ScanPhoto): File = File(directory(photo.projectId), "originals/${photo.filename}")
    suspend fun create(name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.insert(ScanProject(id, name.trim().ifBlank { "Untitled scan" }.take(80), System.currentTimeMillis()))
        directory(id); id
    }
    /** Copy to private storage. Gallery access is not needed after a successful import. */
    suspend fun importPhoto(projectId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        requireNotNull(dao.project(projectId)) { "Project no longer exists." }
        val originals = File(directory(projectId), "originals").apply { mkdirs() }
        require(originals.usableSpace > 512L * 1024 * 1024) { "Keep at least 512 MB free before importing." }
        val id = UUID.randomUUID().toString()
        val temporary = File(originals, "$id.partial")
        val destination = File(originals, "$id.image")
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this photo." }.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= 150L * 1024 * 1024) { "Photograph exceeds the 150 MB input limit." }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (dao.duplicate(projectId, hash) > 0) return@withContext false
            val bitmap = ImageLoader.upright(temporary)
            val statistics = try { ImageLoader.quality(bitmap) } finally { bitmap.recycle() }
            val fullBounds = ImageLoader.bounds(temporary)
            val focal = ImageLoader.focal35(temporary)
            require(temporary.renameTo(destination)) { "Could not finalize the imported photograph." }
            coroutineContext.ensureActive()
            // A cancellation delivered just after a committed DB transaction must not delete
            // the corresponding original in finally. Keep this publication window indivisible.
            withContext(NonCancellable) {
                dao.addPhoto(ScanPhoto(id, projectId, destination.name, hash, bytes,
                    fullBounds.first, fullBounds.second, focal, statistics[2], statistics[3], statistics[4], System.currentTimeMillis()))
                committed = true
            }
            true
        } finally {
            temporary.delete()
            if (!committed) destination.delete()
        }
    }
    /** User-directed offline capture archive. Never uploads or silently shares photographs. */
    suspend fun exportCaptureBundle(projectId: String, destination: OutputStream): Int = withContext(Dispatchers.IO) {
        val project = requireNotNull(dao.project(projectId)) { "Project no longer exists." }
        val photos = dao.photos(projectId)
        require(photos.size in 3..250) { "A capture benchmark needs between three and 250 photos." }
        val manifest = JSONObject().put("schema", 1).put("source", "user_import_or_capture_unverified")
            .put("projectId", project.id).put("projectName", project.name)
            .put("projectRevision", project.revision).put("engineVersion", "m05-real-validation-1")
            .put("physicalCaptureVerified", false)
        val rows = JSONArray()
        ZipOutputStream(destination.buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (photo in photos) {
                coroutineContext.ensureActive()
                val member = "photos/${photo.id}.image"
                val inputFile = photoFile(photo)
                check(inputFile.isFile && inputFile.length() == photo.bytes) { "Original photograph missing or changed." }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(member))
                var copied = 0L
                inputFile.inputStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
                zip.closeEntry()
                check(copied == photo.bytes && digest.digest().joinToString("") { "%02x".format(it) } == photo.sha256) {
                    "Original photograph integrity check failed. Export stopped."
                }
                rows.put(JSONObject().put("path", member).put("sha256", photo.sha256)
                    .put("bytes", photo.bytes).put("width", photo.width).put("height", photo.height)
                    .put("focal35mm", photo.focal35mm ?: JSONObject.NULL)
                    .put("addedAt", photo.addedAt))
            }
            val profile = LensCalibration.file(this@ProjectRepository, projectId)
            if (profile.isFile) {
                require(profile.length() in 30..10_000) { "Invalid lens profile size." }
                LensCalibration.read(this@ProjectRepository, projectId) // Refuse corrupt profiles.
                zip.putNextEntry(ZipEntry("camera-calibration-v1.json"))
                zip.write(profile.readBytes())
                zip.closeEntry()
                manifest.put("calibrationIncluded", true)
            } else manifest.put("calibrationIncluded", false)
            check(dao.project(projectId)?.revision == project.revision) {
                "Project changed during capture export; discard this incomplete archive."
            }
            manifest.put("photos", rows)
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }
        photos.size
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val dir = directory(id)
        dao.deleteProject(id)
        check(dir.deleteRecursively()) { "Database entry deleted; some local files could not be removed." }
    }
    /** Atomic artifact publication on the same filesystem. */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot commit checkpoint." }
        } finally { temporary.delete() }
    }
}
