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

    fun activeOperation(): OperationContext? = synchronized(lock) { active }

    fun pendingRequests(): List<BoundExternalRequest> = synchronized(lock) { pending.values.toList() }

    fun restorePending(requests: Iterable<BoundExternalRequest>) = synchronized(lock) {
        check(active == null) { "Cannot restore requests while an operation is active." }
        pending.clear()
        requests.forEach { request ->
            check(pending.put(request.requestId.value, request) == null) { "Duplicate request identity." }
        }
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
    ): BoundExternalRequest.ArtifactExport? = bind { id, created ->
        BoundExternalRequest.ArtifactExport(
            id, project, generation, created, artifactKey, resultRevision, extension,
        )
    }

    private fun <T : BoundExternalRequest> bind(
        factory: (RequestId, Long) -> T,
    ): T? = synchronized(lock) {
        if (active != null || pending.isNotEmpty()) return@synchronized null
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

    fun reserveDirect(
        kind: OperationKind,
        source: ProjectRevision?,
        selectionGeneration: Long,
    ): OperationContext? = synchronized(lock) {
        if (active != null || pending.isNotEmpty()) return@synchronized null
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
