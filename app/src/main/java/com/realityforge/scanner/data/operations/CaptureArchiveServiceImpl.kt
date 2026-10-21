package com.realityforge.scanner.data.operations

import com.realityforge.scanner.BuildConfig
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.engine.LensCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

class CaptureArchiveServiceImpl(
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
) : CaptureArchiveService {
    override suspend fun export(projectId: String, destination: OutputStream): Int = withContext(Dispatchers.IO) {
        val project = requireNotNull(dao.project(projectId)) { "Project no longer exists." }
        val photos = dao.photos(projectId)
        require(photos.size in 3..250) { "A capture benchmark needs between three and 250 photos." }
        val manifest = JSONObject()
            .put("schema", 1)
            .put("source", "user_import_or_capture_unverified")
            .put("projectId", project.id)
            .put("projectName", project.name)
            .put("projectRevision", project.revision)
            .put("engineVersion", BuildConfig.VERSION_NAME)
            .put("physicalCaptureVerified", false)
        val rows = JSONArray()
        ZipOutputStream(destination.buffered()).use { zip ->
            val buffer = ByteArray(BUFFER_BYTES)
            for (photo in photos) {
                coroutineContext.ensureActive()
                val member = "photos/${photo.id}.image"
                val inputFile = paths.photoFile(photo)
                check(inputFile.isFile && inputFile.length() == photo.bytes) {
                    "Original photograph missing or changed."
                }
                val digest = ProjectRepository.newCaptureDigest()
                zip.putNextEntry(ZipEntry(member))
                var copied = 0L
                inputFile.inputStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        check(read > 0) { "Original photograph produced an invalid zero-length read." }
                        zip.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
                zip.closeEntry()
                check(copied == photo.bytes && digest.digest().toHex() == photo.sha256) {
                    "Original photograph integrity check failed. Export stopped."
                }
                rows.put(
                    JSONObject()
                        .put("path", member)
                        .put("sha256", photo.sha256)
                        .put("bytes", photo.bytes)
                        .put("width", photo.width)
                        .put("height", photo.height)
                        .put("focal35mm", photo.focal35mm ?: JSONObject.NULL)
                        .put("addedAt", photo.addedAt),
                )
            }
            val profile = paths.projectFile(projectId, "camera-calibration-v1.json")
            if (profile.isFile) {
                require(profile.length() in 30..10_000) { "Invalid lens profile size." }
                val calibration = JSONObject(profile.readText(Charsets.UTF_8))
                require(
                    calibration.getInt("schema") == LensCalibration.SCHEMA_VERSION &&
                        calibration.getString("model") == "brown-conrady-5",
                ) { "Unrecognized calibration version." }
                zip.putNextEntry(ZipEntry("camera-calibration-v1.json"))
                profile.inputStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        check(read > 0) { "Calibration profile produced an invalid zero-length read." }
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
                manifest.put("calibrationIncluded", true)
            } else {
                manifest.put("calibrationIncluded", false)
            }
            check(dao.project(projectId)?.revision == project.revision) {
                ProjectRepository.CAPTURE_REVISION_CHANGED
            }
            manifest.put("photos", rows)
            zip.putNextEntry(ProjectRepository.captureManifestEntry())
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }
        photos.size
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object { private const val BUFFER_BYTES = 64 * 1024 }
}
