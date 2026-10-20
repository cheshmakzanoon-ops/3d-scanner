package com.realityforge.scanner.data.operations

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.capture.metadata.CaptureMetadataCodec
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.importing.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The sole import implementation. A durable journal covers the file/Room crash boundary. */
class BoundPhotoImportService(
    context: Context,
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
    private val storage: com.realityforge.scanner.data.storagebudget.StorageBudgetService? = null,
) : PhotoImportService {
    private val writer = AtomicProjectFileWriterImpl(paths)
    private val importer = ImportService(
        AndroidImportSourceAccess(context.contentResolver), AndroidImportInspector,
        ImportPublicationPort { publish(it) }, File(context.filesDir, "import-staging-v1"),
    )

    override suspend fun importPhoto(projectId: String, uri: Uri): Boolean {
        val revision = requireNotNull(dao.project(projectId)).revision
        return import(ImportRequest(UUID.randomUUID().toString(), projectId, revision,
            listOf(ImportSource(uri.toString())))).importedCount == 1
    }

    suspend fun import(request: ImportRequest, progress: suspend (ImportProgress) -> Unit = {}): ImportBatchResult {
        val token = com.realityforge.scanner.data.storagebudget.ReservationToken(request.operationId,
            request.projectId, request.expectedRevision)
        val estimate = com.realityforge.scanner.data.storagebudget.StorageEstimate.photoImport(null)
        storage?.requireReservation(token, estimate)
        return try {
            importer.import(request) { update ->
                storage?.requireReservation(token, estimate)
                progress(update)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { storage?.release(token) }
        }
    }

    private suspend fun publish(staged: StagedImport): ImportPublicationResult = withContext(Dispatchers.IO) {
        val project = dao.project(staged.projectId)
            ?: return@withContext ImportPublicationResult.Rejected("PROJECT_MISSING", "The capture project was removed.", staged.expectedRevision)
        if (project.revision != staged.expectedRevision) return@withContext ImportPublicationResult.Rejected(
            "REVISION_CHANGED", "The capture project changed; review the input selection.", project.revision)
        dao.photoByHash(staged.projectId, staged.sha256)?.let {
            return@withContext ImportPublicationResult.Duplicate(it.id, project.revision)
        }
        if (project.state in setOf("DELETING", "DELETING_PHOTO")) return@withContext ImportPublicationResult.Rejected(
            "PROJECT_DELETING", "Project deletion is pending.", project.revision)
        val id = UUID.randomUUID().toString()
        val (temporary, destination) = paths.newOriginalFiles(staged.projectId, id)
        val metadata = staged.metadata
        val photo = ScanPhoto(id, staged.projectId, destination.name, staged.sha256, staged.bytes,
            metadata.width, metadata.height, metadata.focal35mm, metadata.sharpness,
            metadata.darkFraction, metadata.brightFraction, System.currentTimeMillis(),
            selectionOrder = dao.photos(staged.projectId).size.toLong(),
            captureMetadataRecord = CaptureMetadataCodec.encode(metadata.captureMetadata).toString(Charsets.UTF_8),
            opticalIdentitySha256 = CaptureMetadataCodec.opticalIdentitySha256(metadata.captureMetadata),
            captureSettingsRecord = JSONObject().apply { metadata.captureSettings.toSortedMap().forEach { (key, value) -> put(key, value) } }.toString())
        val journal = paths.projectFile(staged.projectId, "import-${staged.operationId}-${staged.sourceIndex}.journal")
        check(!journal.exists()) { "An earlier import publication requires recovery." }
        writer.write(journal, encode(photo, staged.expectedRevision).toString())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            staged.file.inputStream().use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0) { "Invalid staged photo read." }
                    bytes += count
                    check(bytes <= staged.bytes) { "Staged original grew during publication." }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
                output.fd.sync()
            } }
            check(bytes == staged.bytes && hex(digest.digest()) == staged.sha256) { "Staged original identity changed." }
            AtomicProjectFileWriterImpl.moveReplacing(temporary, destination)
            val revision = dao.addPhotoExpected(photo, staged.expectedRevision)
            journal.delete()
            ImportPublicationResult.Imported(photo.id, revision)
        } catch (failure: Throwable) {
            // The DAO may have committed before its caller observed an exception. Never report
            // rejection while a retry could publish the same photograph behind the user's back.
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val actual = dao.photoByHash(staged.projectId, photo.sha256)
                    val confirmedRevision = dao.project(staged.projectId)?.revision ?: staged.expectedRevision
                    if (actual?.id == photo.id) {
                        check(actual.filename == photo.filename && actual.bytes == photo.bytes)
                        journal.delete()
                        ImportPublicationResult.Imported(photo.id, confirmedRevision)
                    } else {
                        writer.write(journal, encode(photo, staged.expectedRevision).put("phase", "ABORTED").toString())
                        check(removeUncommittedOriginal(destination, photo.sha256, photo.bytes)) {
                            "Uncommitted original requires recovery."
                        }
                        check(!journal.exists() || journal.delete())
                        if (actual != null) ImportPublicationResult.Duplicate(actual.id, confirmedRevision)
                        else ImportPublicationResult.Rejected("PUBLICATION_ABORTED",
                            "The photograph was not committed. Its staged publication was rolled back; retry importing it.",
                            confirmedRevision)
                    }
                } catch (_: Exception) {
                    ImportPublicationResult.PendingRecovery(photo.id,
                        "The photograph may have been committed. Publication requires recovery before another import; reopen the project to reconcile it.",
                        staged.expectedRevision)
                }
            }
        } finally {
            temporary.delete()
        }
    }

    /** Called under the project write reservation before another import is admitted. */
    suspend fun recover(projectId: String) = withContext(Dispatchers.IO) {
        importer.recoverAbandoned()
        val journals = java.nio.file.Files.newDirectoryStream(paths.projectDirectory(projectId, false).toPath(),
            "import-*.journal").use { entries -> entries.asSequence().take(251).map { it.toFile() }.toList() }
        check(journals.size <= 250) { "Too many pending import publications; recovery requires review." }
        for (journal in journals.sortedBy { it.name }) {
            paths.requireOwnedFile(journal)
            if (journal.length() !in 1..128L * 1024L) continue
            val record = runCatching { JSONObject(journal.readText()) }.getOrNull() ?: continue
            if (record.optInt("schema") != 1 || record.optString("projectId") != projectId) continue
            val id = record.getString("id")
            val (temporary, destination) = paths.newOriginalFiles(projectId, id)
            val expectedHash = record.getString("sha256")
            val expectedBytes = record.getLong("bytes")
            val existing = dao.photoByHash(projectId, expectedHash)
            if (existing?.id == id) { journal.delete(); continue }
            if (record.optString("phase", "PREPARED") == "ABORTED" || existing != null) {
                check(removeUncommittedOriginal(destination, expectedHash, expectedBytes)) {
                    "Aborted import requires recovery before another operation."
                }
                temporary.delete(); journal.delete(); continue
            }
            if (!destination.exists()) { temporary.delete(); journal.delete(); continue }
            check(destination.isFile && destination.length() == expectedBytes) {
                "Pending original bytes changed; publication recovery stopped."
            }
            val digest = destination.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }; hex(hash.digest())
            }
            check(digest == expectedHash) { "Pending original checksum changed; publication recovery stopped." }
            val revision = record.getLong("expectedRevision")
            if (dao.project(projectId)?.revision != revision) {
                writer.write(journal, record.put("phase", "ABORTED").toString())
                check(removeUncommittedOriginal(destination, expectedHash, expectedBytes))
                temporary.delete(); journal.delete(); continue
            }
            val photo = ScanPhoto(id, projectId, destination.name, digest, destination.length(),
                record.getInt("width"), record.getInt("height"),
                if (record.isNull("focal35mm")) null else record.getDouble("focal35mm"),
                record.getDouble("sharpness"), record.getDouble("darkFraction"), record.getDouble("brightFraction"),
                record.getLong("addedAt"), selectionOrder = if (!record.isNull("selectionOrder")) record.getLong("selectionOrder") else null, captureMetadataRecord = record.getString("captureMetadataRecord"),
                opticalIdentitySha256 = record.getString("opticalIdentitySha256"),
                captureSettingsRecord = if (record.isNull("captureSettingsRecord")) null else record.getString("captureSettingsRecord"))
            dao.addPhotoExpected(photo, revision)
            journal.delete()
        }
    }

    suspend fun recoverAbandonedStaging(): Int = importer.recoverAbandoned()

    private fun removeUncommittedOriginal(file: File, expectedHash: String, expectedBytes: Long): Boolean {
        if (!file.exists()) return true
        paths.requireOwnedFile(file)
        if (!file.isFile || file.length() != expectedBytes) return false
        val actual = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
            hex(digest.digest())
        }
        return actual == expectedHash && file.delete()
    }

    private fun encode(photo: ScanPhoto, expectedRevision: Long) = JSONObject()
        .put("schema", 1).put("phase", "PREPARED").put("projectId", photo.projectId).put("expectedRevision", expectedRevision)
        .put("id", photo.id).put("sha256", photo.sha256).put("bytes", photo.bytes)
        .put("width", photo.width).put("height", photo.height).put("focal35mm", photo.focal35mm ?: JSONObject.NULL)
        .put("sharpness", photo.sharpness).put("darkFraction", photo.darkFraction).put("brightFraction", photo.brightFraction)
        .put("addedAt", photo.addedAt).put("selectionOrder", photo.selectionOrder ?: JSONObject.NULL).put("captureMetadataRecord", photo.captureMetadataRecord)
        .put("opticalIdentitySha256", photo.opticalIdentitySha256)
        .put("captureSettingsRecord", photo.captureSettingsRecord ?: JSONObject.NULL)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
