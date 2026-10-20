package com.realityforge.scanner.data.archives

import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.data.reopening.BoundedJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CaptureArchivePhoto(
    val id: String, val file: File, val sha256: String, val bytes: Long,
    val width: Int, val height: Int, val focal35mm: Double?, val addedAt: Long,
    val included: Boolean = true, val selectionOrder: Long? = null,
    val sharpness: Double = 0.0, val darkFraction: Double = 0.0, val brightFraction: Double = 0.0,
    val captureMetadataRecord: String? = null, val opticalIdentitySha256: String? = null,
    val curationRecord: String? = null, val captureSettingsRecord: String? = null,
)
data class CaptureArchiveSource(
    val projectId: String, val projectName: String, val projectRevision: Long,
    val engineVersion: String, val photos: List<CaptureArchivePhoto>,
    val calibration: File? = null,
    val source: String = "user_import_or_capture_unverified",
)
data class ValidatedArchivePhoto(
    val sourceId: String, val file: File, val sha256: String, val bytes: Long,
    val width: Int, val height: Int, val focal35mm: Double?, val addedAt: Long,
    val included: Boolean, val selectionOrder: Long?,
    val sharpness: Double = 0.0, val darkFraction: Double = 0.0, val brightFraction: Double = 0.0,
    val captureMetadataRecord: String? = null, val opticalIdentitySha256: String? = null,
    val curationRecord: String? = null, val captureSettingsRecord: String? = null,
)
data class ValidatedCaptureArchive(
    val stagingDirectory: File,
    val sourceProjectId: String,
    val projectName: String,
    val sourceRevision: Long,
    val engineVersion: String,
    val provenance: String,
    val photos: List<ValidatedArchivePhoto>,
    val calibration: File?,
) : AutoCloseable {
    override fun close() { stagingDirectory.deleteRecursively() }
}

/** ZIP transport preserves original bytes. Metadata records provenance, never independently verifies a physical capture. */
class CaptureArchiveWriter {
    fun write(source: CaptureArchiveSource, destination: OutputStream, checkCurrent: () -> Unit = {},
        beforeComplete: () -> Unit = checkCurrent): Int {
        require(source.photos.size in 1..250)
        requireUuid(source.projectId)
        require(source.projectRevision >= 0 && source.engineVersion.length in 1..256)
        require(source.projectName.length in 1..80)
        require(source.source in PROVENANCE)
        require(source.photos.map { it.id }.distinct().size == source.photos.size)
        val manifest = JSONObject().put("schema", 2).put("source", source.source)
            .put("projectId", source.projectId).put("projectName", source.projectName)
            .put("projectRevision", source.projectRevision).put("engineVersion", source.engineVersion)
            .put("physicalCaptureVerified", false)
        val rows = JSONArray()
        ZipOutputStream(destination.buffered()).use { zip ->
            for (photo in source.photos) {
                checkCurrent(); requireUuid(photo.id)
                require(photo.bytes in 1..MAX_MEMBER_BYTES && photo.sha256.matches(SHA256))
                require(photo.addedAt >= 0 && (photo.selectionOrder == null || photo.selectionOrder >= 0))
                require(photo.focal35mm == null || (photo.focal35mm.isFinite() && photo.focal35mm in 1.0..2000.0))
                require(photo.sharpness.isFinite() && photo.sharpness in 0.0..1.0e12 && photo.darkFraction.isFinite() &&
                    photo.darkFraction in 0.0..1.0 && photo.brightFraction.isFinite() && photo.brightFraction in 0.0..1.0)
                require(photo.width in 1..65535 && photo.height in 1..65535 && photo.width.toLong() * photo.height <= 268_435_456)
                require(photo.file.isFile && !Files.isSymbolicLink(photo.file.toPath()) && photo.file.length() == photo.bytes)
                val member = "photos/${photo.id}.image"
                zip.putNextEntry(ZipEntry(member))
                val actual = photo.file.inputStream().use { copyChecked(it, zip, MAX_MEMBER_BYTES, checkCurrent) }
                zip.closeEntry()
                check(actual.first == photo.bytes && actual.second == photo.sha256) { "Original integrity changed during archive export." }
                val row = JSONObject().put("path", member).put("sha256", photo.sha256).put("bytes", photo.bytes)
                    .put("width", photo.width).put("height", photo.height).put("focal35mm", photo.focal35mm ?: JSONObject.NULL)
                    .put("addedAt", photo.addedAt).put("included", photo.included)
                    .put("selectionOrder", photo.selectionOrder ?: JSONObject.NULL)
                    .put("quality", JSONObject().put("sharpness", photo.sharpness).put("darkFraction", photo.darkFraction)
                        .put("brightFraction", photo.brightFraction))
                val records = ArchivePhotoRecords(photo.captureMetadataRecord, photo.opticalIdentitySha256,
                    photo.curationRecord, photo.captureSettingsRecord)
                records.validate(photo.id, photo.sha256, photo.included, photo.selectionOrder)
                records.encode()?.let { bytes ->
                    val metadataMember = "metadata/${photo.id}.json"
                    zip.putNextEntry(ZipEntry(metadataMember)); zip.write(bytes); zip.closeEntry()
                    row.put("metadata", JSONObject().put("path", metadataMember).put("bytes", bytes.size).put("sha256", hash(bytes)))
                }
                rows.put(row)
            }
            source.calibration?.let { profile ->
                require(profile.isFile && !Files.isSymbolicLink(profile.toPath()) && profile.length() <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
                val text = profile.readText(Charsets.UTF_8)
                CalibrationProfileCodec.decode(text)
                val bytes = text.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(CALIBRATION_MEMBER)); zip.write(bytes); zip.closeEntry()
                manifest.put("calibration", JSONObject().put("path", CALIBRATION_MEMBER)
                    .put("bytes", bytes.size).put("sha256", hash(bytes)))
            }
            manifest.put("calibrationIncluded", source.calibration != null).put("photos", rows)
            beforeComplete()
            zip.putNextEntry(ZipEntry(MANIFEST_MEMBER))
            val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Archive completion metadata exceeds its bound." }
            zip.write(manifestBytes)
            zip.closeEntry(); zip.finish()
        }
        return source.photos.size
    }
}

/** Only a fully validated completion manifest can leave staging. No ZIP path is used as an absolute path. */
class CaptureArchiveImporter {
    fun stage(source: InputStream, stagingRoot: File, checkCancelled: () -> Unit = {},
        reserveAdditional: (Long) -> Unit = {}): ValidatedCaptureArchive {
        require(!Files.isSymbolicLink(stagingRoot.toPath()))
        require(stagingRoot.isDirectory || stagingRoot.mkdirs())
        val stage = File(stagingRoot, "archive-${UUID.randomUUID()}")
        check(stage.mkdir()) { "Could not create archive staging." }
        var keep = false
        try {
            val actual = linkedMapOf<String, Pair<Long, String>>()
            var total = 0L
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && allowedMember(entry.name)) { "Unsupported or unsafe archive path." }
                    require(entry.name !in actual && actual.size < 502) { "Duplicate or excessive archive entries." }
                    val limit = when (entry.name) {
                        MANIFEST_MEMBER -> MAX_MANIFEST_BYTES.toLong()
                        CALIBRATION_MEMBER -> CalibrationProfileCodec.MAX_PROFILE_BYTES.toLong()
                        else -> if (METADATA_MEMBER.matches(entry.name)) ArchivePhotoRecords.MAX_RECORD_MEMBER_BYTES.toLong() else MAX_MEMBER_BYTES
                    }
                    require(entry.size < 0 || entry.size <= limit) { "Archive member exceeds the expanded-size limit." }
                    val target = File(stage, entry.name)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    // No created child can be a symlink and the staging directory is independently generated.
                    require(target.canonicalFile.toPath().startsWith(stage.canonicalFile.toPath()))
                    val observed = FileOutputStream(target).use { output ->
                        var memberWritten = 0L
                        val result = copyChecked(zip, output, limit, {
                            checkCancelled()
                            // The callback probes the filesystem repeatedly; already written bytes are not charged twice.
                            reserveAdditional(minOf(limit - memberWritten, 1024L * 1024))
                        }) { count ->
                            memberWritten += count
                            total = Math.addExact(total, count.toLong())
                            require(total <= MAX_TOTAL_BYTES) { "Archive expansion exceeds the collection limit." }
                        }
                        output.fd.sync()
                        result
                    }
                    zip.closeEntry()
                    if (observed.first > 1024 * 1024 && entry.compressedSize > 0)
                        require(observed.first / entry.compressedSize <= 100) { "Archive member has an unsafe expansion ratio." }
                    actual[entry.name] = observed
                }
            }
            checkCancelled()
            val manifestFile = File(stage, MANIFEST_MEMBER)
            require(manifestFile.isFile) { "Archive completion manifest is missing." }
            val manifest = manifestFile.inputStream().use { BoundedJson.read(it, MAX_MANIFEST_BYTES, checkCancelled) }
            val schema = exactLong(manifest, "schema", 1, 2).toInt()
            val sourceId = exactString(manifest, "projectId", 36).also(::requireUuid)
            val name = exactString(manifest, "projectName", 80)
            val revision = exactLong(manifest, "projectRevision", 0, Long.MAX_VALUE)
            val engine = exactString(manifest, "engineVersion", 256)
            val provenance = exactString(manifest, "source", 80).also { require(it in PROVENANCE) { "Unsupported capture provenance." } }
            // A self-declared ZIP flag cannot establish physical-device validation.
            require(manifest.get("physicalCaptureVerified") == false) { "Archive cannot assert independently verified physical capture." }
            val rows = manifest.get("photos") as? JSONArray ?: error("Archive photos must be an array.")
            require(rows.length() in 1..250)
            val expected = linkedSetOf(MANIFEST_MEMBER)
            val photos = ArrayList<ValidatedArchivePhoto>(rows.length())
            for (i in 0 until rows.length()) {
                checkCancelled()
                val row = rows.get(i) as? JSONObject ?: error("Photo metadata must be an object.")
                val path = exactString(row, "path", 80)
                require(PHOTO_MEMBER.matches(path) && expected.add(path)) { "Invalid or duplicate manifest photo." }
                val bytes = exactLong(row, "bytes", 1, MAX_MEMBER_BYTES)
                val digest = exactString(row, "sha256", 64).also { require(it.matches(SHA256)) }
                require(actual[path] == (bytes to digest)) { "Archive photo hash or byte length mismatch." }
                val width = exactLong(row, "width", 1, 65535).toInt()
                val height = exactLong(row, "height", 1, 65535).toInt()
                require(width.toLong() * height <= 268_435_456) { "Photo dimensions exceed decode limit." }
                val focal = if (row.isNull("focal35mm")) null else (row.get("focal35mm") as? Number)?.toDouble()
                    ?.also { require(it.isFinite() && it in 1.0..2000.0) } ?: error("Invalid focal metadata.")
                val included = if (schema == 1) true else row.get("included") as? Boolean ?: error("Invalid photo selection.")
                val order = if (schema == 1 || row.isNull("selectionOrder")) null else exactLong(row, "selectionOrder", 0, Long.MAX_VALUE)
                val quality = row.optJSONObject("quality")
                val sharpness = quality?.let { exactDouble(it, "sharpness", 0.0, 1.0e12) } ?: 0.0
                val dark = quality?.let { exactDouble(it, "darkFraction", 0.0, 1.0) } ?: 0.0
                val bright = quality?.let { exactDouble(it, "brightFraction", 0.0, 1.0) } ?: 0.0
                val photoId = path.removePrefix("photos/").removeSuffix(".image")
                val records = if (row.isNull("metadata")) ArchivePhotoRecords() else {
                    require(schema == 2) { "Legacy archive cannot include versioned photo records." }
                    val record = row.get("metadata") as? JSONObject ?: error("Photo metadata integrity record must be an object.")
                    val recordPath = exactString(record, "path", 80)
                    require(recordPath == "metadata/$photoId.json" && expected.add(recordPath)) { "Metadata belongs to a different photo." }
                    val recordBytes = exactLong(record, "bytes", 1, ArchivePhotoRecords.MAX_RECORD_MEMBER_BYTES.toLong())
                    val recordHash = exactString(record, "sha256", 64).also { require(it.matches(SHA256)) }
                    require(actual[recordPath] == (recordBytes to recordHash)) { "Photo metadata hash or byte length mismatch." }
                    ArchivePhotoRecords.decode(File(stage, recordPath).readBytes())
                }
                records.validate(photoId, digest, included, order)
                photos += ValidatedArchivePhoto(photoId, File(stage, path),
                    digest, bytes, width, height, focal, exactLong(row, "addedAt", 0, Long.MAX_VALUE), included, order,
                    sharpness, dark, bright, records.captureMetadataRecord, records.opticalIdentitySha256,
                    records.curationRecord, records.captureSettingsRecord)
            }
            val calibrationIncluded = manifest.get("calibrationIncluded") as? Boolean ?: error("Calibration presence must be explicit.")
            val calibration = if (!calibrationIncluded) null else {
                expected += CALIBRATION_MEMBER
                val profile = File(stage, CALIBRATION_MEMBER)
                require(profile.isFile) { "Calibration profile is missing." }
                if (schema == 2) {
                    val row = manifest.get("calibration") as? JSONObject ?: error("Calibration integrity record is missing.")
                    require(exactString(row, "path", 80) == CALIBRATION_MEMBER)
                    require(actual[CALIBRATION_MEMBER] == (exactLong(row, "bytes", 1, CalibrationProfileCodec.MAX_PROFILE_BYTES.toLong()) to
                        exactString(row, "sha256", 64))) { "Calibration profile hash mismatch." }
                }
                CalibrationProfileCodec.decode(profile.readText(Charsets.UTF_8))
                profile
            }
            require(actual.keys == expected) { "Archive contains missing or unmanifested members." }
            keep = true
            return ValidatedCaptureArchive(stage, sourceId, name, revision, engine, provenance, photos.toList(), calibration)
        } finally {
            if (!keep) stage.deleteRecursively()
        }
    }
}

internal const val MANIFEST_MEMBER = "session.json"
internal const val CALIBRATION_MEMBER = "camera-calibration-v1.json"
internal const val MAX_MEMBER_BYTES = 150L * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 1024 * 1024
internal const val MAX_TOTAL_BYTES = 40L * 1024 * 1024 * 1024
internal val SHA256 = Regex("[0-9a-f]{64}")
internal val PROVENANCE = setOf("user_import_or_capture_unverified", "synthetic", "physical_capture_unverified")
private val PHOTO_MEMBER = Regex("photos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.image")
private val METADATA_MEMBER = Regex("metadata/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")
private fun allowedMember(path: String) = path == MANIFEST_MEMBER || path == CALIBRATION_MEMBER || PHOTO_MEMBER.matches(path) || METADATA_MEMBER.matches(path)
internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun requireUuid(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) }
private fun exactString(json: JSONObject, key: String, limit: Int): String = (json.get(key) as? String)?.also {
    require(it.length in 1..limit && '\u0000' !in it && '\n' !in it && '\r' !in it)
} ?: error("$key must be a string.")
private fun exactLong(json: JSONObject, key: String, min: Long, max: Long): Long {
    val raw = json.get(key)
    require(raw is Int || raw is Long) { "$key must be an integer." }
    return (raw as Number).toLong().also { require(it in min..max) { "$key is outside supported limits." } }
}
private fun exactDouble(json: JSONObject, key: String, min: Double, max: Double): Double =
    (json.get(key) as? Number)?.toDouble()?.also { require(it.isFinite() && it in min..max) }
        ?: error("$key must be a finite number.")
private fun copyChecked(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit,
    onBytes: (Int) -> Unit = {}): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L; var zeroReads = 0
    while (true) {
        check()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) { require(++zeroReads < 16) { "Input stream stalled." }; continue }
        zeroReads = 0
        require(read <= limit - count) { "Expanded member exceeds the supported limit." }
        output.write(buffer, 0, read); digest.update(buffer, 0, read)
        count += read; onBytes(read)
    }
    return count to digest.digest().joinToString("") { "%02x".format(it) }
}
