package com.realityforge.scanner.export

import com.realityforge.scanner.data.safexport.DestinationBindingResult
import com.realityforge.scanner.data.safexport.DestinationDocumentStore
import com.realityforge.scanner.data.safexport.DestinationSelectionCallback
import com.realityforge.scanner.data.safexport.DestinationSelectionMode
import com.realityforge.scanner.data.safexport.DestinationSelectionRequest
import com.realityforge.scanner.data.safexport.DestinationTransferService
import com.realityforge.scanner.data.safexport.ExportSourceFileResolver
import com.realityforge.scanner.data.safexport.ExportSourceIdentity
import com.realityforge.scanner.data.safexport.FileVerifiedExportSourceAccess
import com.realityforge.scanner.data.safexport.ResolvedExportFile
import com.realityforge.scanner.data.safexport.SourceChangedException
import com.realityforge.scanner.data.safexport.TransferCancellation
import com.realityforge.scanner.data.safexport.TransferOutcome
import com.realityforge.scanner.data.safexport.TransferProgress
import com.realityforge.scanner.data.safexport.VerifiedExportSource
import com.realityforge.scanner.data.safexport.bind
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface SafCoordinatorOutcome {
    /** No provider destination was prepared: request/staging validation failed. */
    data class Rejected(val requestId: String, val code: String) : SafCoordinatorOutcome

    /** Preserve 026's exact verified/unverified/cancel/failure result and cleanup evidence. */
    data class Transferred(val outcome: TransferOutcome) : SafCoordinatorOutcome
}

/**
 * Bridges canonical export materialization to Agent 026's actual document transfer.
 * The caller supplies a request restored from PendingExportStore, never current UI
 * selection. Call selection(restoredRequest) after recreation without launching a
 * new picker. Global project/job reservation still belongs to owner 001/006.
 */
class SafExportCoordinator(
    private val source: ExportSource,
    private val stagingRoot: File,
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
    private val active = AtomicBoolean(false)
    private val selectionLock = Any()
    private val selected = mutableMapOf<String, ExportRequest>()

    init { require(maxBytes > 0) }

    /** Validates availability and binds all format/options fields before opening the picker. */
    suspend fun selection(request: ExportRequest): DestinationSelectionRequest = withContext(Dispatchers.IO) {
        check(!active.get()) { "BUSY" }
        checkCatalog(request)
        synchronized(selectionLock) {
            val previous = selected[request.requestId]
            check(previous == null || previous == request) { "REQUEST_CHANGED" }
            check(previous != null || selected.size < MAX_PENDING) { "TOO_MANY_PENDING_REQUESTS" }
            selected[request.requestId] = request
        }
        selectionFor(request)
    }

    /** Release a cancelled system picker, without touching a provider or any project files. */
    fun abandon(requestId: String) { synchronized(selectionLock) { selected.remove(requestId) } }

    suspend fun transfer(
        request: ExportRequest,
        callback: DestinationSelectionCallback,
        store: DestinationDocumentStore,
        cancelled: () -> Boolean = { false },
        progress: (TransferProgress) -> Unit = {},
    ): SafCoordinatorOutcome = withContext(Dispatchers.IO) {
        if (callback.requestId != request.requestId) return@withContext reject(request, "REQUEST_ID_MISMATCH")
        val bound = selectionFor(request).bind(callback)
        if (bound is DestinationBindingResult.Rejected) return@withContext reject(request, bound.reason.name)
        bound as DestinationBindingResult.Bound
        if (!active.compareAndSet(false, true)) return@withContext reject(request, "BUSY")
        var staged: File? = null
        val context = currentCoroutineContext()
        val checkpoint = {
            context.ensureActive()
            if (cancelled()) throw ExportCancelled()
            staged?.let { check(it.length() <= request.option.bytes && it.length() <= maxBytes) { "INVALID_SIZE" } }
            Unit
        }
        try {
            synchronized(selectionLock) {
                val fixed = selected[request.requestId]
                    ?: return@withContext reject(request, "UNKNOWN_REQUEST")
                if (fixed != request) return@withContext reject(request, "REQUEST_CHANGED")
                selected.remove(request.requestId) // One picker response may materialize at most once.
            }
            checkpoint()
            checkCatalog(request)
            check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "STAGING_UNAVAILABLE" }
            val target = File.createTempFile("saf-export-", ".partial", stagingRoot)
            staged = target
            source.materialize(request, target, checkpoint)
            checkpoint()
            check(target.isFile && target.canonicalFile.parentFile == stagingRoot.canonicalFile) { "STAGING_PATH_CHANGED" }
            check(target.length() == request.option.bytes && target.length() <= maxBytes) { "INVALID_SIZE" }
            source.validate(request, target)
            checkpoint()
            check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
            val hash = ExportCoordinator.digest(target, checkpoint)
            check(request.option.contentSha256 == null || hash == request.option.contentSha256) { "ARTIFACT_BYTES_CHANGED" }
            checkpoint()
            check(target.length() == request.option.bytes) { "INVALID_SIZE" }
            check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
            val expectedIdentity = sourceIdentity(request)
            val verified = VerifiedExportSource(
                identity = expectedIdentity,
                expectedByteCount = request.option.bytes,
                sha256 = hash,
                displayName = selectionFor(request).displayName,
                mimeType = request.option.format.mime,
            )
            val access = FileVerifiedExportSourceAccess(ExportSourceFileResolver { identity ->
                currentCoroutineContext().ensureActive()
                if (identity != expectedIdentity || !source.isCurrent(request.artifact)) {
                    throw SourceChangedException("The fixed export artifact/revision is no longer current.")
                }
                if (!target.isFile || target.canonicalFile.parentFile != stagingRoot.canonicalFile || target.length() != request.option.bytes) {
                    throw SourceChangedException("The validated private staging artifact changed.")
                }
                ResolvedExportFile(expectedIdentity, target)
            })
            // 026 checks source bytes again before opening, after copying, and
            // after commit. A provider accepting close is not verified success.
            SafCoordinatorOutcome.Transferred(
                DestinationTransferService(access, store).transfer(
                    source = verified,
                    destination = bound.request,
                    cancellation = TransferCancellation { cancelled() },
                    onProgress = progress,
                ),
            )
        } catch (cancelledJob: CancellationException) {
            // 026 performs provider cleanup in NonCancellable before rethrowing.
            throw cancelledJob
        } catch (_: ExportCancelled) {
            reject(request, "CANCELLED")
        } catch (error: Exception) {
            val code = error.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "STAGING_FAILED"
            reject(request, code)
        } finally {
            staged?.delete()
            active.set(false)
        }
    }

    private suspend fun checkCatalog(request: ExportRequest) {
        check(request.option.bytes in 1..maxBytes) { "EXPORT_LIMIT" }
        check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
        val catalog = source.available(request.artifact)
        check(catalog.map { it.id }.distinct().size == catalog.size) { "INVALID_CATALOG" }
        check(catalog.contains(request.option)) { "OPTION_UNAVAILABLE" }
        check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
    }

    private fun sourceIdentity(request: ExportRequest) = ExportSourceIdentity(
        projectId = request.artifact.projectId,
        revision = request.artifact.revision,
        artifactKey = request.artifact.artifactKey,
        representation = request.option.representation.name.lowercase(Locale.ROOT),
    )

    private fun selectionFor(request: ExportRequest) = DestinationSelectionRequest(
        requestId = request.requestId,
        sourceIdentity = sourceIdentity(request),
        displayName = "scan.${request.option.format.extension}",
        mimeType = request.option.format.mime,
        mode = DestinationSelectionMode.CREATE_NEW_DOCUMENT,
    )

    private fun reject(request: ExportRequest, code: String) = SafCoordinatorOutcome.Rejected(request.requestId, code)

    private companion object { const val MAX_PENDING = 8 }
}
