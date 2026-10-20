package com.realityforge.scanner.data.importing

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One bounded private stream at a time. Provider length/MIME are never trusted. Publication
 * consumes immutable, validated staged bytes; URI access is never needed by a background job.
 * The operation directory is a recovery boundary: no file there is a committed original.
 */
class ImportService(
    private val sourceAccess: ImportSourceAccess,
    private val inspector: ImportInspector,
    private val publication: ImportPublicationPort,
    private val stagingRoot: File,
    private val budgets: ImportBudgets = ImportBudgets(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    suspend fun import(
        request: ImportRequest,
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportBatchResult = withContext(Dispatchers.IO) {
        val sources = request.sources.map { source -> source.copy(
            captureSettings = java.util.Collections.unmodifiableMap(LinkedHashMap(source.captureSettings))) }
        require(sources.size <= budgets.maxPhotos) { "Too many photographs in this batch." }
        val root = prepareRoot()
        val key = File(root, request.operationId).path
        check(activeOperations.add(key)) { "This import operation is already running." }
        val directory = File(root, request.operationId)
        try {
            // The only durable effect of an abandoned operation is published by the port. A rerun
            // uses operationId/sourceIndex idempotency there, never trusts an old partial stream.
            if (directory.exists()) removeOwnedDirectory(root, directory)
            check(directory.mkdir()) { "Cannot create private import staging." }
            var revision = request.expectedRevision
            var batchBytes = 0L
            val outcomes = ArrayList<ImportPhotoResult>(sources.size)
            for ((index, source) in sources.withIndex()) {
                coroutineContext.ensureActive()
                val temporary = File(directory, "$index.partial")
                val outcome = try {
                    var photoBytes = 0L
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    val progressStep = maxOf(64 * 1024L, budgets.maxPhotoBytes / 128)
                    var nextProgress = progressStep
                    sourceAccess.open(source).use { input ->
                        FileOutputStream(temporary).use { output ->
                            while (true) {
                                coroutineContext.ensureActive()
                                var count = input.read(buffer)
                                if (count < 0) break
                                // A buggy provider may repeatedly return zero without reaching EOF.
                                // A single-byte fallback makes progress without spinning forever.
                                if (count == 0) {
                                    val byte = input.read()
                                    if (byte < 0) break
                                    buffer[0] = byte.toByte(); count = 1
                                }
                                require(photoBytes <= budgets.maxPhotoBytes - count) {
                                    "Photograph exceeds the encoded-byte limit."
                                }
                                require(batchBytes <= budgets.maxBatchBytes - count) {
                                    "Import exceeds this batch's storage budget."
                                }
                                val free = availableBytes(root)
                                if (free < budgets.freeSpaceHeadroomBytes ||
                                    free - budgets.freeSpaceHeadroomBytes < count) {
                                    throw ImportStorageException()
                                }
                                output.write(buffer, 0, count)
                                hash.update(buffer, 0, count)
                                photoBytes += count; batchBytes += count
                                if (photoBytes >= nextProgress) {
                                    onProgress(ImportProgress(request.operationId, request.projectId,
                                        index, sources.size, photoBytes))
                                    nextProgress = photoBytes + progressStep
                                }
                            }
                            require(photoBytes > 0) { "This photograph is empty." }
                            output.fd.sync()
                        }
                    }
                    coroutineContext.ensureActive()
                    val metadata = inspector.inspect(temporary, source)
                    coroutineContext.ensureActive()
                    val staged = StagedImport(request.operationId, index, request.projectId,
                        revision, temporary, hash.digest().joinToString("") { "%02x".format(it) },
                        photoBytes, metadata)
                    // A cancellation received after a DB commit must not turn an accepted import
                    // into a rejection. The publication owner keeps this critical section bounded.
                    val result = withContext(NonCancellable) { publication.publish(staged) }
                    when (result) {
                        is ImportPublicationResult.Imported -> {
                            check(result.committedRevision == Math.addExact(revision, 1)) {
                                "Import publication returned an incoherent project revision."
                            }
                            revision = result.committedRevision
                        }
                        is ImportPublicationResult.Duplicate -> check(result.revision == revision) {
                            "Project changed during duplicate detection."
                        }
                        is ImportPublicationResult.Rejected -> Unit
                        is ImportPublicationResult.PendingRecovery -> Unit
                    }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SecurityException) {
                    ImportPublicationResult.Rejected("SOURCE_PERMISSION_LOST",
                        "Photo access was revoked. Select this photograph again.", revision)
                } catch (failure: ImportStorageException) {
                    ImportPublicationResult.Rejected("INSUFFICIENT_STORAGE", failure.message!!, revision)
                } catch (failure: Exception) {
                    ImportPublicationResult.Rejected("IMPORT_REJECTED",
                        (failure.message ?: "This photograph could not be imported.").take(512), revision)
                } finally {
                    // Publication has either moved/copied this file or declined it. Its final
                    // destination is never in the import staging directory and is never deleted here.
                    temporary.delete()
                }
                outcomes += ImportPhotoResult(index, source.uri, outcome)
                if (outcome is ImportPublicationResult.PendingRecovery) {
                    // The current revision is uncertain. Do not open another source, assume a
                    // commit, or retry a publication until its durable journal has been resolved.
                    for (remaining in index + 1 until sources.size) {
                        outcomes += ImportPhotoResult(remaining, sources[remaining].uri,
                            ImportPublicationResult.Rejected("BATCH_PAUSED_FOR_RECOVERY",
                                "Not attempted: an earlier photograph needs recovery. Resolve it, then select the remaining photographs again.",
                                revision))
                    }
                    break
                }
            }
            ImportBatchResult(request.operationId, request.projectId, request.expectedRevision,
                revision, outcomes.toList())
        } finally {
            try { removeOwnedDirectory(root, directory) } finally { activeOperations.remove(key) }
        }
    }

    /** Called at startup before durable work is resumed; live same-process imports are excluded. */
    suspend fun recoverAbandoned(): Int = withContext(Dispatchers.IO) {
        val root = prepareRoot()
        var removed = 0
        val directories = Files.newDirectoryStream(root.toPath()).use { paths ->
            paths.asSequence().take(100).map { it.toFile() }.toList()
        }
        directories.forEach { directory ->
            coroutineContext.ensureActive()
            if (runCatching { requireUuid(directory.name) }.isSuccess &&
                Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                activeOperations.add(directory.path)) {
                try { removeOwnedDirectory(root, directory); removed++ }
                finally { activeOperations.remove(directory.path) }
            }
        }
        removed
    }

    private fun prepareRoot(): File {
        check(stagingRoot.exists() || stagingRoot.mkdirs()) { "Cannot create private import staging." }
        check(stagingRoot.isDirectory && !Files.isSymbolicLink(stagingRoot.toPath()))
        return stagingRoot.canonicalFile
    }

    private fun removeOwnedDirectory(root: File, directory: File) {
        check(directory.parentFile == root && !Files.isSymbolicLink(directory.toPath()))
        check(directory.canonicalFile.parentFile == root)
        if (!directory.exists()) return
        // Generated operation directories have no child directories. Preserve anything unexpected.
        val files = Files.newDirectoryStream(directory.toPath()).use { paths ->
            paths.asSequence().take(251).map { it.toFile() }.toList()
        }
        check(files.size <= 250 && files.all { file ->
            val index = file.name.removeSuffix(".partial").toIntOrNull()
            index != null && index in 0..249 && file.name == "$index.partial" &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }) { "Import recovery found unexpected or excessive staging files." }
        files.forEach { file ->
            check(file.delete()) { "Cannot recover staged photograph." }
        }
        check(directory.delete()) { "Import recovery found unexpected or inaccessible staging files." }
    }

    companion object { private val activeOperations = ConcurrentHashMap.newKeySet<String>() }
}

class ImportStorageException : IOException("Free storage is below the safe import reserve.")
