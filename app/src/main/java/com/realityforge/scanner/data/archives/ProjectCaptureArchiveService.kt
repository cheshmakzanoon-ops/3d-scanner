package com.realityforge.scanner.data.archives

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.operations.CaptureArchiveService
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.reopening.BoundedJson
import com.realityforge.scanner.data.storagebudget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** The restore callback must insert the project and every photograph in one Room transaction. */
interface ArchiveProjectStore {
    suspend fun project(id: String): ScanProject?
    suspend fun photos(id: String): List<ScanPhoto>
    suspend fun restore(project: ScanProject, photos: List<ScanPhoto>)
}

class ScanDaoArchiveProjectStore(
    private val dao: ScanDao,
    private val restoreTransaction: suspend (ScanProject, List<ScanPhoto>) -> Unit,
) : ArchiveProjectStore {
    override suspend fun project(id: String) = dao.project(id)
    override suspend fun photos(id: String) = dao.photos(id)
    override suspend fun restore(project: ScanProject, photos: List<ScanPhoto>) = restoreTransaction(project, photos)
}

enum class ArchiveRestoreBoundary { VALIDATED, JOURNAL_SYNCED, FILES_PROMOTED, DATABASE_COMMITTED }
fun interface ArchiveRestoreFaultInjector { fun after(boundary: ArchiveRestoreBoundary) }
data class ArchiveRecoveryReport(val committedPreserved: Int, val incompleteRemoved: Int, val activeSkipped: Int,
    val failures: List<String>)

/**
 * Original bytes are validated in an isolated operation directory. A synced intent and ownership marker
 * precede atomic directory promotion. Only the final Room transaction makes a restored project visible.
 * Recovery never identifies garbage by a photograph filename or by an elapsed-time guess.
 */
class ProjectCaptureArchiveService(
    private val store: ArchiveProjectStore,
    private val paths: ProjectPathPolicy,
    private val engineVersion: String,
    private val budgets: StorageBudgetService? = null,
    private val faults: ArchiveRestoreFaultInjector = ArchiveRestoreFaultInjector {},
) : CaptureArchiveService {
    private val workRoot get() = File(paths.root, ".archive-restores").also {
        require(!Files.isSymbolicLink(it.toPath()))
        check(it.isDirectory || it.mkdir()) { "Cannot create private archive staging." }
    }

    override suspend fun export(projectId: String, destination: OutputStream): Int = withContext(Dispatchers.IO) {
        val project = requireNotNull(store.project(projectId)) { "Project no longer exists." }
        check(project.state != "DELETING" && project.state != "DELETING_PHOTO") { "Project cleanup is pending." }
        val photos = store.photos(projectId)
        val profile = paths.projectFile(projectId, CALIBRATION_MEMBER).takeIf(File::isFile)
        val provenanceFile = paths.projectFile(projectId, PROVENANCE_MEMBER)
        val provenance = if (provenanceFile.isFile) provenanceFile.inputStream().use {
            BoundedJson.read(it, MAX_MANIFEST_BYTES).getString("source")
        }.also { require(it in PROVENANCE) } else "user_import_or_capture_unverified"
        val originalBytes = photos.fold(0L) { sum, photo -> Math.addExact(sum, photo.bytes) }
        val metadataBound = Math.addExact(2L * MAX_MANIFEST_BYTES,
            Math.multiplyExact(photos.size.toLong(), ArchivePhotoRecords.MAX_RECORD_MEMBER_BYTES.toLong()))
        val bound = Math.addExact(Math.addExact(originalBytes, originalBytes / 100), metadataBound)
        val token = ReservationToken(UUID.randomUUID().toString(), project.id, project.revision)
        val context = coroutineContext
        try {
            budgets?.requireReservation(token, StorageEstimate.export(bound))
            CaptureArchiveWriter().write(CaptureArchiveSource(project.id, project.name, project.revision, engineVersion,
                photos.map { CaptureArchivePhoto(it.id, paths.photoFile(it), it.sha256, it.bytes, it.width, it.height,
                    it.focal35mm, it.addedAt, it.included, it.selectionOrder, it.sharpness, it.darkFraction, it.brightFraction,
                    it.captureMetadataRecord, it.opticalIdentitySha256, it.curationRecord, it.captureSettingsRecord) },
                profile, provenance), destination, checkCurrent = { context.ensureActive() }, beforeComplete = {
                context.ensureActive()
                // This is an IO caller outside a Room transaction. The completion record is written only after this read.
                check(runBlocking { store.project(projectId)?.revision } == project.revision) {
                    "Project changed during capture archive export. Choose the current project and retry."
                }
            })
        } finally {
            withContext(NonCancellable) { budgets?.release(token) }
        }
    }

    suspend fun restore(source: InputStream): String = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val operation = File(workRoot, operationId)
        check(operation.mkdir())
        val token = ReservationToken(operationId, projectId, 0)
        val context = coroutineContext
        var committed = false
        RandomAccessFile(File(operation, LOCK_MEMBER), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                try {
                    val checkSpace: (Long) -> Unit = { remaining ->
                        context.ensureActive()
                        if (budgets != null) runBlocking {
                            budgets.requireReservation(token, StorageEstimate(stagingBytes = remaining))
                        } else {
                            val decision = StorageAccounting.decide(remaining, FileSpaceProbe(paths.root).availableBytes(), emptyList())
                            if (!decision.admitted) throw InsufficientStorageException(decision)
                        }
                    }
                    val validated = CaptureArchiveImporter().stage(source, operation, { context.ensureActive() }, checkSpace)
                    validated.use { archive ->
                        faults.after(ArchiveRestoreBoundary.VALIDATED)
                        context.ensureActive()
                        val project = ScanProject(projectId, archive.projectName, System.currentTimeMillis(),
                            state = "READY", message = "Capture archive restored. Original photographs are ready for processing.")
                        val prepared = File(operation, PREPARED_MEMBER).also { check(it.mkdir()) }
                        val originals = File(prepared, "originals").also { check(it.mkdir()) }
                        val photos = archive.photos.mapIndexed { index, photo ->
                            context.ensureActive()
                            val id = UUID.randomUUID().toString()
                            Files.move(photo.file.toPath(), File(originals, "$id.image").toPath(), StandardCopyOption.ATOMIC_MOVE)
                            val curation = photo.curationRecord?.let { record ->
                                val sourcePhoto = PhotoCurationRecordCodec.decode(record)
                                // Restored projects are independent. Old removal confirmations must
                                // never authorize deletion of the new original, even if soft state is retained.
                                PhotoCurationRecordCodec.encode(sourcePhoto.copy(id = id, position = index,
                                    pendingRemoval = sourcePhoto.pendingRemoval?.copy(requestId = UUID.randomUUID().toString())))
                            }
                            ScanPhoto(id, projectId, "$id.image", photo.sha256, photo.bytes, photo.width, photo.height,
                                photo.focal35mm, photo.sharpness, photo.darkFraction, photo.brightFraction, photo.addedAt,
                                photo.included, index.toLong(), captureMetadataRecord = photo.captureMetadataRecord,
                                opticalIdentitySha256 = photo.opticalIdentitySha256, curationRecord = curation,
                                captureSettingsRecord = photo.captureSettingsRecord)
                        }
                        archive.calibration?.let {
                            Files.move(it.toPath(), File(prepared, CALIBRATION_MEMBER).toPath(), StandardCopyOption.ATOMIC_MOVE)
                        }
                        val provenance = JSONObject().put("schema", 1).put("source", archive.provenance)
                            .put("sourceProjectId", archive.sourceProjectId).put("sourceRevision", archive.sourceRevision)
                            .put("sourceEngineVersion", archive.engineVersion).put("physicalCaptureVerified", false)
                        writeSynced(File(prepared, PROVENANCE_MEMBER), provenance.toString())
                        val intent = JSONObject().put("schema", 1).put("operationId", operationId).put("projectId", projectId)
                            .put("projectName", project.name).put("createdAt", project.createdAt)
                            .put("photos", JSONArray(photos.map { JSONObject().put("id", it.id).put("sha256", it.sha256)
                                .put("bytes", it.bytes) }))
                        val intentText = intent.toString()
                        val marker = JSONObject().put("schema", 1).put("operationId", operationId).put("projectId", projectId)
                            .put("intentSha256", hash(intentText.toByteArray(Charsets.UTF_8)))
                        writeSynced(File(prepared, MARKER_MEMBER), marker.toString())
                        syncDirectory(originals); syncDirectory(prepared)
                        writeSynced(File(operation, INTENT_MEMBER), intentText)
                        syncDirectory(operation)
                        faults.after(ArchiveRestoreBoundary.JOURNAL_SYNCED)
                        context.ensureActive()
                        check(store.project(projectId) == null) { "Restoration project identity already exists." }
                        val destination = paths.projectDirectory(projectId, create = false)
                        check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Restoration destination already exists." }
                        // No cancellation inside the small promotion/metadata commit interval. Original byte IO is complete.
                        withContext(NonCancellable) {
                            Files.move(prepared.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            syncDirectory(paths.root)
                            faults.after(ArchiveRestoreBoundary.FILES_PROMOTED)
                            store.restore(project, photos)
                            committed = true
                            faults.after(ArchiveRestoreBoundary.DATABASE_COMMITTED)
                        }
                    }
                    projectId
                } finally {
                    withContext(NonCancellable) {
                        // A transaction may have committed before its caller lost the process/response.
                        if (!committed) committed = store.project(projectId) != null
                        if (committed) removeOperation(operation)
                        else reconcileOperation(operation)
                        budgets?.release(token)
                    }
                }
            }
        }
    }

    /** Run before admission at startup; locks skip an operation that is still alive in another process. */
    suspend fun recover(): ArchiveRecoveryReport = withContext(Dispatchers.IO) {
        var preserved = 0; var removed = 0; var skipped = 0
        val failures = mutableListOf<String>()
        for (operation in workRoot.listFiles().orEmpty()) {
            coroutineContext.ensureActive()
            if (!isUuid(operation.name) || !operation.isDirectory || Files.isSymbolicLink(operation.toPath())) continue
            try {
                RandomAccessFile(File(operation, LOCK_MEMBER), "rw").use { lockFile ->
                    val lock = try { lockFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) { skipped++; return@use }
                    lock.use {
                        if (reconcileOperation(operation)) preserved++ else removed++
                    }
                }
            } catch (failure: Exception) { failures += "${operation.name}: ${failure.message ?: "Archive recovery failed."}" }
        }
        ArchiveRecoveryReport(preserved, removed, skipped, failures.toList())
    }

    private suspend fun reconcileOperation(operation: File): Boolean {
        val intentFile = File(operation, INTENT_MEMBER)
        if (!intentFile.isFile) { removeOperation(operation); return false }
        require(intentFile.length() in 1..MAX_MANIFEST_BYTES.toLong() && !Files.isSymbolicLink(intentFile.toPath()))
        val bytes = intentFile.readBytes().also { require(it.size <= MAX_MANIFEST_BYTES) }
        val intent = BoundedJson.read(bytes.inputStream(), MAX_MANIFEST_BYTES)
        require(intent.get("schema") == 1 && intent.getString("operationId") == operation.name)
        val projectId = intent.getString("projectId").also { require(isUuid(it)) }
        val destination = paths.projectDirectory(projectId, create = false)
        val project = store.project(projectId)
        if (project != null) {
            require(destination.isDirectory) { "Committed archive storage is missing; retained the recovery record." }
            verifyOwnership(destination, projectId, operation.name, hash(bytes))
            val rows = intent.getJSONArray("photos")
            val photos = store.photos(projectId)
            require(rows.length() == photos.size) { "Committed archive metadata is incomplete; retained the recovery record." }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val photo = requireNotNull(photos.singleOrNull { it.id == row.getString("id") })
                require(photo.sha256 == row.getString("sha256") && photo.bytes == row.getLong("bytes"))
                val file = paths.photoFile(photo)
                require(file.isFile && file.length() == photo.bytes)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count > 0)
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == photo.sha256) {
                    "Committed archive original failed verification; retained the recovery record."
                }
            }
            removeOperation(operation)
            return true
        }
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            verifyOwnership(destination, projectId, operation.name, hash(bytes))
            check(destination.deleteRecursively()) { "Uncommitted archive storage could not be removed; retry cleanup." }
            syncDirectory(paths.root)
        }
        removeOperation(operation)
        return false
    }

    private fun verifyOwnership(directory: File, projectId: String, operationId: String, digest: String) {
        require(!Files.isSymbolicLink(directory.toPath()))
        val marker = File(directory, MARKER_MEMBER)
        require(marker.isFile && !Files.isSymbolicLink(marker.toPath())) { "Archive ownership marker is missing." }
        val json = marker.inputStream().use { BoundedJson.read(it, 4096) }
        require(json.get("schema") == 1 && json.getString("projectId") == projectId &&
            json.getString("operationId") == operationId && json.getString("intentSha256") == digest) {
            "Archive ownership marker differs; no project files were removed."
        }
    }

    private fun removeOperation(operation: File) {
        require(operation.parentFile.canonicalFile == workRoot.canonicalFile && isUuid(operation.name))
        check(operation.deleteRecursively()) { "Archive staging cleanup needs a retry." }
        syncDirectory(workRoot)
    }

    companion object {
        private const val LOCK_MEMBER = "operation.lock"
        private const val PREPARED_MEMBER = "prepared-project"
        private const val INTENT_MEMBER = "restore-intent.json"
        private const val MARKER_MEMBER = "archive-restoration.json"
        const val PROVENANCE_MEMBER = "capture-provenance.json"
        private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun writeSynced(file: File, text: String) {
            val temporary = File(file.parentFile, "${file.name}.partial")
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        private fun syncDirectory(directory: File) {
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
