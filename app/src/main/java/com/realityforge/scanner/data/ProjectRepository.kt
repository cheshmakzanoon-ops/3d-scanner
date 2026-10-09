package com.realityforge.scanner.data

import android.content.Context
import android.net.Uri
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
        require(key.matches(Regex("sparse-r[0-9]+-[0-9a-f]{16}")) && extension in setOf("json", "glb", "ply")) { "Invalid artifact key." }
        return File(directory(id), "$key.$extension")
    }
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
