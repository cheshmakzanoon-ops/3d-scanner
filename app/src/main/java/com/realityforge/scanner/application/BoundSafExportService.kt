package com.realityforge.scanner.application

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.data.publication.ArtifactPresence
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.data.safexport.*
import com.realityforge.scanner.data.storagebudget.ReservationToken
import com.realityforge.scanner.data.storagebudget.StorageEstimate
import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.ProjectRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

/** Format encoders remain with their owners. This adapter binds their exact bytes to C1 and SAF. */
class BoundSafExportService(
    private val context: Context,
    private val root: ScannerCompositionRoot,
) {
    private val repository get() = root.repository

    suspend fun artifact(
        request: BoundExternalRequest.ArtifactExport,
        destination: Uri,
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferOutcome = withContext(Dispatchers.IO) {
        repository.withProjectAccess(ProjectOperationRequest(request.project.projectId, request.requestId.value,
            ProjectOperationKind.EXPORT_RESULT, request.project.revision)) {
            requireArtifactCurrent(request)
            val logicalName = requireNotNull(LOGICAL_NAMES[request.extension]) { "Unsupported export representation." }
            val published = root.publications.current(request.project.projectId)
            val head = root.database.publicationHeadDao().head(request.project.projectId)?.currentPointer
            val file: File
            val source: VerifiedExportSource
            when (published) {
                is PublicationReadResult.Invalid -> error("Completed result failed verification: ${published.reason}")
                is PublicationReadResult.Verified -> {
                    val set = published.value
                    check(set.manifest.identity.projectRevision == request.project.revision) { "Result revision changed." }
                    val entry = set.manifest.entries.singleOrNull { it.declaration.logicalName == logicalName }
                    check(entry != null && entry.presence == ArtifactPresence.PRESENT) { "This completed result has no requested representation." }
                    file = requireNotNull(set.artifact(logicalName))
                    source = VerifiedExportSource(ExportSourceIdentity(request.project.projectId, request.project.revision,
                        request.artifactKey, entry.declaration.representation), entry.bytes, requireNotNull(entry.sha256),
                        "scan.${request.extension}", mimeType(request.extension))
                }
                PublicationReadResult.None -> {
                    // Legacy results have no publication manifest. Strict reopening still checks their revision and format;
                    // the transfer receipt proves these selected bytes, without inventing historical publication evidence.
                    val preview = repository.currentPreview(request.project.projectId)
                    check(preview?.getString("artifactKey") == request.artifactKey) { "The selected legacy result is unavailable." }
                    file = repository.artifact(request.project.projectId, request.artifactKey, request.extension)
                    source = describe(file, ExportSourceIdentity(request.project.projectId, request.project.revision,
                        request.artifactKey, "legacy-${request.extension}"), "scan.${request.extension}", mimeType(request.extension))
                }
            }
            val access = FileVerifiedExportSourceAccess(ExportSourceFileResolver { identity ->
                requireArtifactCurrent(request)
                check(root.database.publicationHeadDao().head(request.project.projectId)?.currentPointer == head) {
                    "The completed artifact set changed during export."
                }
                if (identity == source.identity) ResolvedExportFile(identity, file) else null
            })
            transfer(request.requestId.value, source, destination, access, onProgress)
        }
    }

    suspend fun captureArchive(
        request: BoundExternalRequest.CaptureBundleExport,
        destination: Uri,
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferOutcome = withContext(Dispatchers.IO) {
        repository.withProjectAccess(ProjectOperationRequest(request.project.projectId, request.requestId.value,
            ProjectOperationKind.EXPORT_CAPTURE, request.project.revision)) {
            requireCurrent(request.project)
            val staging = OwnedCaptureExportStaging(context.cacheDir)
            staging.recoverAbandoned()
            staging.create(request.requestId.value).use { stage ->
                FileOutputStream(stage.file).use { output ->
                    // The ZIP encoder closes its wrapper. Keep this owned descriptor open until its bytes are synced.
                    val encoderStream = object : java.io.OutputStream() {
                        override fun write(value: Int) = output.write(value)
                        override fun write(bytes: ByteArray, offset: Int, count: Int) = output.write(bytes, offset, count)
                        override fun flush() = output.flush()
                        override fun close() = flush()
                    }
                    repository.exportCaptureBundle(request.project.projectId, encoderStream)
                    output.fd.sync()
                }
                requireCurrent(request.project)
                val source = describe(stage.file, ExportSourceIdentity(request.project.projectId, request.project.revision,
                    request.requestId.value, "capture-archive-v2"), "scan-captures.zip", "application/zip")
                val access = FileVerifiedExportSourceAccess(ExportSourceFileResolver { identity ->
                    requireCurrent(request.project)
                    if (identity == source.identity) ResolvedExportFile(identity, stage.file) else null
                })
                transfer(request.requestId.value, source, destination, access, onProgress)
            }
        }
    }

    private suspend fun transfer(requestId: String, source: VerifiedExportSource, destination: Uri,
        access: VerifiedExportSourceAccess, onProgress: (TransferProgress) -> Unit): TransferOutcome {
        val token = ReservationToken(requestId, source.identity.projectId, source.identity.revision)
        try {
            // Staged bytes already exist and are reflected in free space. Reserve the potential local destination.
            root.storageBudget.requireReservation(token, StorageEstimate.export(source.expectedByteCount, localStaging = false))
            val bound = BoundDestinationRequest(requestId, source.identity, destination.toString(),
                DestinationOwnership.NEWLY_CREATED, destinationCapabilities(destination))
            return DestinationTransferService(access, AndroidSafDestinationStore(context)).transfer(source, bound, onProgress = onProgress)
        } finally {
            withContext(NonCancellable) { root.storageBudget.release(token) }
        }
    }

    private suspend fun requireCurrent(expected: ProjectRevision) {
        val project = requireNotNull(repository.dao.project(expected.projectId)) { "Project no longer exists." }
        check(project.revision == expected.revision && project.state !in setOf("DELETING", "DELETING_PHOTO", "DELETED")) {
            "Project changed after the destination picker opened. Select the current source and try again."
        }
    }

    private suspend fun requireArtifactCurrent(request: BoundExternalRequest.ArtifactExport) {
        requireCurrent(request.project)
        val project = requireNotNull(repository.dao.project(request.project.projectId))
        check(project.resultRevision == request.resultRevision && project.resultRevision == request.project.revision &&
            project.resultKey == request.artifactKey) { "The completed result changed after the destination picker opened." }
    }

    private suspend fun describe(file: File, identity: ExportSourceIdentity, name: String, mime: String): VerifiedExportSource {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Export source is missing." }
        val bytes = file.length()
        check(bytes in 1..MAX_EXPORT_BYTES) { "Export source exceeds its supported byte bound." }
        val digest = MessageDigest.getInstance("SHA-256")
        var observed = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "Export source stopped making progress." }
                observed = Math.addExact(observed, count.toLong())
                check(observed <= bytes) { "Export source grew during verification." }
                digest.update(buffer, 0, count)
            }
        }
        check(observed == bytes) { "Export source changed during verification." }
        return VerifiedExportSource(identity, bytes, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, name, mime)
    }

    /** Probe only supported read/query/delete operations; generic SAF has no atomic replacement contract. */
    private suspend fun destinationCapabilities(uri: Uri): DestinationCapabilities {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a document destination." }
        currentCoroutineContext().ensureActive()
        val resolver = context.contentResolver
        val readable = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        val queryable = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                it.getColumnIndex(OpenableColumns.SIZE) >= 0 && it.moveToFirst()
            } ?: false
        }.getOrDefault(false)
        val deletable = runCatching {
            DocumentsContract.isDocumentUri(context, uri) && resolver.query(uri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                column >= 0 && cursor.moveToFirst() && !cursor.isNull(column) &&
                    cursor.getInt(column) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
            } == true
        }.getOrDefault(false)
        currentCoroutineContext().ensureActive()
        return DestinationCapabilities(readable, queryable, deletable, false, false)
    }

    companion object {
        private const val MAX_EXPORT_BYTES = 40L * 1024 * 1024 * 1024
        private val LOGICAL_NAMES = mapOf("json" to "preview", "glb" to "sparse-glb", "ply" to "sparse-ply",
            "dense.glb" to "dense-glb", "dense.ply" to "dense-ply", "mesh.glb" to "mesh-glb", "mesh.ply" to "mesh-ply",
            "textured.glb" to "textured-glb", "texture.png" to "texture-png")
        private fun mimeType(extension: String) = when {
            extension == "json" -> "application/json"
            extension.endsWith("glb") -> "model/gltf-binary"
            extension == "texture.png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}

/** Crash cleanup requires this component's exact marker and an unlocked operation; age is never proof. */
private class OwnedCaptureExportStaging(cacheDir: File) {
    private val root = File(cacheDir, "capture-export-staging-v1").also {
        check(!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.mkdir()))
    }

    fun create(id: String): Stage {
        require(UUID.fromString(id).toString() == id)
        val directory = File(root, id)
        check(directory.mkdir()) { "An export with this request identity already exists." }
        val handle = RandomAccessFile(File(directory, "lease"), "rw")
        val lock = handle.channel.lock()
        try {
            FileOutputStream(File(directory, "owner")).use { output ->
                output.write("capture-export-v1:$id".toByteArray(Charsets.US_ASCII)); output.fd.sync()
            }
            return Stage(directory, handle, lock)
        } catch (error: Throwable) {
            lock.close(); handle.close()
            throw error
        }
    }

    fun recoverAbandoned() {
        Files.newDirectoryStream(root.toPath()).use { entries ->
            var checked = 0
            for (path in entries) {
                check(++checked <= 128) { "Too many private export staging entries; storage review is required." }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                val id = path.fileName.toString()
                if (!runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) continue
                val directory = path.toFile()
                val owner = File(directory, "owner")
                if (!Files.isRegularFile(owner.toPath(), LinkOption.NOFOLLOW_LINKS) || owner.length() > 64 ||
                    owner.readText(Charsets.US_ASCII) != "capture-export-v1:$id") continue
                val lease = File(directory, "lease")
                if (!Files.isRegularFile(lease.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                RandomAccessFile(lease, "rw").use { handle ->
                    val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) lock.use { removeOwnedFiles(directory) }
                }
            }
        }
    }

    class Stage(private val directory: File, private val handle: RandomAccessFile,
        private val lock: java.nio.channels.FileLock) : AutoCloseable {
        val file = File(directory, "capture.zip")
        override fun close() {
            try { removeOwnedFiles(directory) } finally { lock.close(); handle.close() }
        }
    }

    companion object {
        private fun removeOwnedFiles(directory: File) {
            val names = mutableListOf<String>()
            Files.newDirectoryStream(directory.toPath()).use { members ->
                for (member in members) {
                    check(names.size < 3 && Files.isRegularFile(member, LinkOption.NOFOLLOW_LINKS) &&
                        member.fileName.toString() in setOf("capture.zip", "lease", "owner")) { "Unrecognized export staging content." }
                    names.add(member.fileName.toString())
                }
            }
            // Preserve ownership evidence when a payload cannot be removed.
            for (name in listOf("capture.zip", "lease", "owner")) if (name in names)
                check(File(directory, name).delete()) { "Cannot remove private export staging." }
            check(directory.delete()) { "Cannot remove private export staging directory." }
        }
    }
}
