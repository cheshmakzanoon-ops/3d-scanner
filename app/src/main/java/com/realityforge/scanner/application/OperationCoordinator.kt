package com.realityforge.scanner.application

import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.JobId
import com.realityforge.scanner.domain.OperationContext
import com.realityforge.scanner.domain.OperationId
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.domain.RequestId
import com.realityforge.scanner.domain.SessionId
import java.util.UUID

/**
 * Serializes operation ownership without depending on Android lifecycle classes.
 * Selection is intentionally absent: callers must pass an already-bound ProjectRevision.
 */
class OperationCoordinator(
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, BoundExternalRequest>()
    private var active: OperationContext? = null
    private var archiveRestore: com.realityforge.scanner.domain.CaptureArchiveRestoreRequest? = null

    fun activeOperation(): OperationContext? = synchronized(lock) { active }

    fun pendingRequests(): List<BoundExternalRequest> = synchronized(lock) { pending.values.toList() }

    fun restorePending(requests: Iterable<BoundExternalRequest>) = synchronized(lock) {
        check(active == null) { "Cannot restore requests while an operation is active." }
        pending.clear()
        requests.forEach { request ->
            check(pending.put(request.requestId.value, request) == null) { "Duplicate request identity." }
        }
    }

    fun pendingArchiveRestore() = synchronized(lock) { archiveRestore }

    fun restoreArchiveRequest(request: com.realityforge.scanner.domain.CaptureArchiveRestoreRequest?) = synchronized(lock) {
        check(active == null && (request == null || pending.isEmpty()))
        archiveRestore = request
    }

    fun bindArchiveRestore(generation: Long) = synchronized(lock) {
        if (active != null || pending.isNotEmpty() || archiveRestore != null) return@synchronized null
        com.realityforge.scanner.domain.CaptureArchiveRestoreRequest(RequestId(nextId()), generation, nowEpochMillis())
            .also { archiveRestore = it }
    }

    fun cancelArchiveRestore(requestId: RequestId): Boolean = synchronized(lock) {
        if (archiveRestore?.requestId != requestId) return@synchronized false
        archiveRestore = null
        true
    }

    fun claimArchiveRestore(requestId: RequestId): OperationContext? = synchronized(lock) {
        if (active != null) return@synchronized null
        val request = archiveRestore?.takeIf { it.requestId == requestId } ?: return@synchronized null
        val context = newContext(OperationKind.RESTORE_CAPTURE_ARCHIVE, null,
            request.selectionGeneration, request.requestId)
        archiveRestore = null
        active = context
        context
    }

    fun bindImport(project: ProjectRevision, generation: Long): BoundExternalRequest.ImportPhotos? =
        bind { id, created -> BoundExternalRequest.ImportPhotos(id, project, generation, created) }

    fun bindCapture(project: ProjectRevision, generation: Long): BoundExternalRequest.CapturePhoto? =
        bind { id, created ->
            BoundExternalRequest.CapturePhoto(
                id, project, generation, created, SessionId(nextId()),
            )
        }

    fun bindCalibration(project: ProjectRevision, generation: Long): BoundExternalRequest.CalibrateLens? =
        bind { id, created -> BoundExternalRequest.CalibrateLens(id, project, generation, created) }

    fun bindCaptureBundle(project: ProjectRevision, generation: Long): BoundExternalRequest.CaptureBundleExport? =
        bind { id, created -> BoundExternalRequest.CaptureBundleExport(id, project, generation, created) }

    fun bindArtifact(
        project: ProjectRevision,
        generation: Long,
        artifactKey: String,
        resultRevision: Long,
        extension: String,
        derivedSelection: com.realityforge.scanner.domain.DerivedExportSelection? = null,
    ): BoundExternalRequest.ArtifactExport? = bind { id, created ->
        BoundExternalRequest.ArtifactExport(
            id, project, generation, created, artifactKey, resultRevision, extension, derivedSelection,
        )
    }

    private fun <T : BoundExternalRequest> bind(
        factory: (RequestId, Long) -> T,
    ): T? = synchronized(lock) {
        if (active != null || pending.isNotEmpty() || archiveRestore != null) return@synchronized null
        val request = factory(RequestId(nextId()), nowEpochMillis())
        pending[request.requestId.value] = request
        request
    }

    fun cancelPending(requestId: RequestId): BoundExternalRequest? = synchronized(lock) {
        pending.remove(requestId.value)
    }

    fun claimPending(requestId: RequestId, expectedKind: OperationKind): Pair<BoundExternalRequest, OperationContext>? =
        synchronized(lock) {
            if (active != null) return@synchronized null
            val request = pending[requestId.value] ?: return@synchronized null
            if (request.kind != expectedKind) return@synchronized null
            val context = newContext(
                kind = request.kind,
                source = request.project,
                selectionGeneration = request.selectionGeneration,
                requestId = request.requestId,
                sessionId = request.sessionId,
            )
            pending.remove(requestId.value)
            active = context
            request to context
        }

    /** A camera session remains bound while each successful shot advances its batch revision. */
    fun claimCapture(sessionToken: String, projectId: String, expectedRevision: Long): Pair<BoundExternalRequest.CapturePhoto, OperationContext>? =
        synchronized(lock) {
            if (active != null) return@synchronized null
            val request = pending.values.filterIsInstance<BoundExternalRequest.CapturePhoto>()
                .singleOrNull { it.sessionId.value == sessionToken } ?: return@synchronized null
            if (request.project != ProjectRevision(projectId, expectedRevision)) return@synchronized null
            val context = newContext(request.kind, request.project, request.selectionGeneration,
                request.requestId, request.sessionId)
            active = context
            request to context
        }

    fun advanceCapture(context: OperationContext, committedRevision: Long): Boolean = synchronized(lock) {
        if (active?.operationId != context.operationId) return@synchronized false
        val request = pending[context.requestId?.value] as? BoundExternalRequest.CapturePhoto
            ?: return@synchronized false
        if (request.sessionId != context.sessionId || request.project != context.source ||
            committedRevision < request.project.revision) return@synchronized false
        pending[request.requestId.value] = request.copy(
            project = request.project.copy(revision = committedRevision))
        true
    }

    fun reserveDirect(
        kind: OperationKind,
        source: ProjectRevision?,
        selectionGeneration: Long,
    ): OperationContext? = synchronized(lock) {
        if (active != null || pending.isNotEmpty() || archiveRestore != null) return@synchronized null
        newContext(kind, source, selectionGeneration).also { active = it }
    }

    fun finish(operationId: OperationId): Boolean = synchronized(lock) {
        if (active?.operationId != operationId) return@synchronized false
        active = null
        true
    }

    private fun newContext(
        kind: OperationKind,
        source: ProjectRevision?,
        selectionGeneration: Long,
        requestId: RequestId? = null,
        sessionId: SessionId? = null,
    ) = OperationContext(
        operationId = OperationId(nextId()),
        jobId = JobId(nextId()),
        kind = kind,
        source = source,
        requestId = requestId,
        sessionId = sessionId,
        selectionGeneration = selectionGeneration,
        startedAtEpochMillis = nowEpochMillis(),
    )
}
