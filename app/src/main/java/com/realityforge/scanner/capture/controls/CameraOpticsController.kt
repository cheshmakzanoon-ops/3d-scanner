package com.realityforge.scanner.capture.controls

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Serializes camera controls and rejects every completion that no longer belongs to the
 * currently bound capture session. Platform acknowledgements are intentionally distinct from
 * capture-result confirmation: a requested AE/AWB lock is never reported as applied merely
 * because a control future completed.
 */
class CameraOpticsController {
    private data class Binding(
        val generation: Long,
        val sessionToken: CaptureSessionToken,
        val camera: CameraIdentity,
        val capabilities: CameraCapabilities,
        val backend: OpticalControlBackend,
        val observedPhysicalCameraId: AtomicReference<String?> = AtomicReference(camera.physicalCameraId),
    )

    private val commandMutex = Mutex()
    private val stateGuard = Any()
    private val generation = AtomicLong(0L)

    @Volatile
    private var binding: Binding? = null

    private val mutableState = MutableStateFlow(AppliedOpticsState.Unbound)
    val state: StateFlow<AppliedOpticsState> = mutableState.asStateFlow()

    fun bind(
        sessionToken: CaptureSessionToken,
        camera: CameraIdentity,
        capabilities: CameraCapabilities,
        backend: OpticalControlBackend,
    ) {
        require(camera.logicalCameraId == capabilities.camera.logicalCameraId) {
            "Bound camera and capability camera must have the same logical id."
        }
        require(
            camera.physicalCameraId == null ||
                capabilities.supportsPhysicalCamera(camera.physicalCameraId),
        ) { "Selected physical camera is not advertised by the logical camera." }

        val old: Binding?
        synchronized(stateGuard) {
            old = binding
            val next = Binding(
                generation = generation.incrementAndGet(),
                sessionToken = sessionToken,
                camera = camera,
                capabilities = capabilities,
                backend = backend,
            )
            binding = next
            mutableState.value = AppliedOpticsState(
                sessionToken = sessionToken,
                commandId = null,
                camera = camera,
                phase = AppliedOpticsPhase.IDLE,
                metadata = backend.latestMetadata(),
            )
        }
        old?.backend?.cancelPending("camera binding replaced")
    }

    /**
     * Invalidates the current generation before cancellation is requested from CameraX. A
     * future that completes after this call cannot publish into a later session.
     */
    fun unbind(reason: String) {
        require(reason.isNotBlank()) { "Unbind reason must not be blank." }
        val old: Binding?
        synchronized(stateGuard) {
            old = binding
            generation.incrementAndGet()
            binding = null
            mutableState.value = AppliedOpticsState(
                sessionToken = old?.sessionToken,
                commandId = null,
                camera = old?.camera,
                phase = AppliedOpticsPhase.DISPOSED,
                metadata = old?.backend?.latestMetadata(),
                diagnosticCode = "OPTICS_SESSION_DISPOSED",
                diagnosticDetail = reason,
            )
        }
        old?.backend?.cancelPending(reason)
    }

    suspend fun submit(request: OpticalControlRequest): AppliedOpticsState = commandMutex.withLock {
        val active = binding
            ?: return@withLock rejected(
                request,
                "OPTICS_NO_ACTIVE_SESSION",
                "No camera session is currently bound.",
            )

        if (request.sessionToken != active.sessionToken) {
            return@withLock rejected(
                request,
                "OPTICS_STALE_SESSION",
                "The command belongs to a disposed or different capture session.",
            )
        }
        if (request.expectedCamera != active.camera) {
            val failure = rejected(
                request,
                "OPTICS_CAMERA_IDENTITY_MISMATCH",
                "The requested logical/physical camera does not match the active binding.",
            )
            publishIfCurrent(active, failure)
            return@withLock failure
        }

        var working = initialState(active, request)
        if (!publishIfCurrent(active, working)) {
            return@withLock cancelledForStaleGeneration(request, active.camera)
        }
        if (!hasPendingControl(working)) {
            return@withLock working.copy(phase = derivePhase(working)).also {
                publishIfCurrent(active, it)
            }
        }

        try {
            withTimeout(request.timeoutMillis) {
                if (working.focus.status == ControlStatus.PENDING) {
                    val result = active.backend.applyFocus(requireNotNull(working.focus.requested))
                    ensureCurrentOrCancel(active, request)
                    working = working.withFocusResult(result)
                    working = acceptMetadataOrFail(active, working)
                    publishIfCurrent(active, working.copy(phase = derivePhase(working)))
                }

                if (working.exposureCompensation.status == ControlStatus.PENDING) {
                    val result = active.backend.setExposureCompensation(
                        requireNotNull(working.exposureCompensation.requested),
                    )
                    ensureCurrentOrCancel(active, request)
                    working = working.withExposureResult(result)
                    working = acceptMetadataOrFail(active, working)
                    publishIfCurrent(active, working.copy(phase = derivePhase(working)))
                }

                if (working.zoom.status == ControlStatus.PENDING) {
                    val result = active.backend.setZoomRatio(requireNotNull(working.zoom.requested))
                    ensureCurrentOrCancel(active, request)
                    working = working.withZoomResult(result)
                    working = acceptMetadataOrFail(active, working)
                    publishIfCurrent(active, working.copy(phase = derivePhase(working)))
                }

                if (
                    working.aeLock.status == ControlStatus.PENDING ||
                        working.awbLock.status == ControlStatus.PENDING
                ) {
                    val ae = working.aeLock.requested.takeIf {
                        working.aeLock.status == ControlStatus.PENDING
                    }
                    val awb = working.awbLock.requested.takeIf {
                        working.awbLock.status == ControlStatus.PENDING
                    }
                    val result = active.backend.setLocks(
                        aeLocked = ae,
                        awbLocked = awb,
                        confirmationTimeoutMillis = request.timeoutMillis.coerceAtMost(2_500L),
                    )
                    ensureCurrentOrCancel(active, request)
                    working = working.withLockResults(result)
                    working = acceptMetadataOrFail(active, working)
                    publishIfCurrent(active, working.copy(phase = derivePhase(working)))
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            active.backend.cancelPending("optical command timed out")
            if (!isCurrent(active)) {
                return@withLock cancelledForStaleGeneration(request, active.camera)
            }
            working = working.failPending(
                code = "OPTICS_COMMAND_TIMEOUT",
                detail = "Camera controls did not complete within ${request.timeoutMillis} ms.",
            )
        } catch (cancelled: CancellationException) {
            active.backend.cancelPending("optical command coroutine cancelled")
            if (isCurrent(active)) {
                val cancellationState = working.cancelPending("Optical command was cancelled.")
                publishIfCurrent(active, cancellationState)
            }
            throw cancelled
        } catch (failure: Throwable) {
            active.backend.cancelPending("optical command failed")
            if (!isCurrent(active)) {
                return@withLock cancelledForStaleGeneration(request, active.camera)
            }
            working = working.failPending(
                code = "OPTICS_BACKEND_FAILURE",
                detail = failure.message ?: failure::class.java.simpleName,
            )
        }

        if (!isCurrent(active)) {
            return@withLock cancelledForStaleGeneration(request, active.camera)
        }
        val final = acceptMetadataOrFail(
            active,
            working.copy(
                phase = derivePhase(working),
                metadata = working.metadata ?: active.backend.latestMetadata(),
            ),
        ).let { it.copy(phase = derivePhase(it)) }
        publishIfCurrent(active, final)
        final
    }

    private fun initialState(
        active: Binding,
        request: OpticalControlRequest,
    ): AppliedOpticsState {
        val capabilities = active.capabilities
        val zoom = when (val value = request.zoomRatio) {
            null -> ControlApplication.notRequested()
            else -> if (capabilities.zoom.supports(value)) {
                ControlApplication.pending(value)
            } else {
                ControlApplication.unavailable(
                    value,
                    "Requested zoom is outside ${capabilities.zoom.minimumRatio}..${capabilities.zoom.maximumRatio}.",
                )
            }
        }
        val exposure = when (val value = request.exposureCompensationIndex) {
            null -> ControlApplication.notRequested()
            else -> if (
                capabilities.exposureCompensation.supported &&
                    capabilities.exposureCompensation.supports(value)
            ) {
                ControlApplication.pending(value)
            } else {
                ControlApplication.unavailable(
                    value,
                    "Exposure compensation index is unsupported or outside the advertised range.",
                )
            }
        }
        val focus: ControlApplication<FocusRequest> = when (val value = request.focus) {
            FocusRequest.KeepCurrent -> ControlApplication.notRequested()
            FocusRequest.Cancel -> ControlApplication.pending(value)
            is FocusRequest.MeterAt -> if (capabilities.focusMeteringSupported) {
                ControlApplication.pending(value)
            } else {
                ControlApplication.unavailable(value, "AF metering is not available on this camera.")
            }
        }
        val aeLock = lockApplication(request.aeLock, capabilities.aeLockAvailable, "AE")
        val awbLock = lockApplication(request.awbLock, capabilities.awbLockAvailable, "AWB")

        return AppliedOpticsState(
            sessionToken = request.sessionToken,
            commandId = request.commandId,
            camera = active.camera,
            phase = AppliedOpticsPhase.PENDING,
            zoom = zoom,
            exposureCompensation = exposure,
            focus = focus,
            aeLock = aeLock,
            awbLock = awbLock,
            metadata = active.backend.latestMetadata(),
        ).let { it.copy(phase = derivePhase(it)) }
    }

    private fun lockApplication(
        directive: LockDirective,
        available: Boolean,
        label: String,
    ): ControlApplication<Boolean> = when (directive) {
        LockDirective.KEEP -> ControlApplication.notRequested()
        LockDirective.LOCK,
        LockDirective.UNLOCK,
        -> {
            val requested = directive == LockDirective.LOCK
            if (available) {
                ControlApplication.pending(requested)
            } else {
                ControlApplication.unavailable(requested, "$label lock is unavailable.")
            }
        }
    }

    private fun ensureCurrentOrCancel(active: Binding, request: OpticalControlRequest) {
        if (!isCurrent(active)) {
            throw StaleGenerationCancellation(request.commandId)
        }
    }

    private fun publishIfCurrent(active: Binding, next: AppliedOpticsState): Boolean =
        synchronized(stateGuard) {
            if (!isCurrentLocked(active)) {
                false
            } else {
                mutableState.value = next
                true
            }
        }

    private fun isCurrent(active: Binding): Boolean = synchronized(stateGuard) {
        isCurrentLocked(active)
    }

    private fun isCurrentLocked(active: Binding): Boolean {
        val current = binding
        return current === active && current.generation == active.generation
    }

    private fun acceptMetadataOrFail(
        active: Binding,
        state: AppliedOpticsState,
    ): AppliedOpticsState {
        val metadata = state.metadata ?: active.backend.latestMetadata() ?: return state
        if (metadata.camera.logicalCameraId != active.camera.logicalCameraId) {
            active.backend.cancelPending("logical camera changed")
            return state.failRequestedControls(
                code = "OPTICS_LOGICAL_CAMERA_CHANGED",
                detail = "Capture result came from logical camera ${metadata.camera.logicalCameraId}; expected ${active.camera.logicalCameraId}.",
            ).copy(metadata = null)
        }

        val observedPhysical = metadata.activePhysicalCameraId
        val expectedPhysical = active.camera.physicalCameraId
        if (expectedPhysical != null && observedPhysical != null && observedPhysical != expectedPhysical) {
            active.backend.cancelPending("physical camera changed")
            return state.failRequestedControls(
                code = "OPTICS_PHYSICAL_CAMERA_CHANGED",
                detail = "Capture result switched to physical camera $observedPhysical; expected $expectedPhysical.",
            ).copy(metadata = null)
        }
        if (expectedPhysical == null && observedPhysical != null) {
            val first = active.observedPhysicalCameraId.get()
            if (first == null) {
                active.observedPhysicalCameraId.compareAndSet(null, observedPhysical)
            } else if (first != observedPhysical) {
                active.backend.cancelPending("logical camera silently switched physical lens")
                return state.failRequestedControls(
                    code = "OPTICS_PHYSICAL_CAMERA_CHANGED",
                    detail = "Active physical camera changed from $first to $observedPhysical during the session.",
                ).copy(metadata = null)
            }
        }
        return state.copy(metadata = metadata)
    }

    private fun rejected(
        request: OpticalControlRequest,
        code: String,
        detail: String,
    ): AppliedOpticsState = AppliedOpticsState(
        sessionToken = request.sessionToken,
        commandId = request.commandId,
        camera = request.expectedCamera,
        phase = AppliedOpticsPhase.FAILED,
        diagnosticCode = code,
        diagnosticDetail = detail,
    )

    private fun cancelledForStaleGeneration(
        request: OpticalControlRequest,
        camera: CameraIdentity,
    ): AppliedOpticsState = AppliedOpticsState(
        sessionToken = request.sessionToken,
        commandId = request.commandId,
        camera = camera,
        phase = AppliedOpticsPhase.CANCELLED,
        diagnosticCode = "OPTICS_STALE_COMPLETION_DROPPED",
        diagnosticDetail = "The camera session changed before the control completed.",
    )

    private fun hasPendingControl(state: AppliedOpticsState): Boolean = statuses(state).any {
        it == ControlStatus.PENDING
    }

    private fun derivePhase(state: AppliedOpticsState): AppliedOpticsPhase {
        val requested = statuses(state).filterNot { it == ControlStatus.NOT_REQUESTED }
        if (requested.isEmpty()) return AppliedOpticsPhase.IDLE
        if (ControlStatus.PENDING in requested) return AppliedOpticsPhase.PENDING
        if (ControlStatus.FAILED in requested) {
            return if (requested.all { it == ControlStatus.FAILED }) {
                AppliedOpticsPhase.FAILED
            } else {
                AppliedOpticsPhase.PARTIAL
            }
        }
        if (ControlStatus.CANCELLED in requested) {
            return if (requested.all { it == ControlStatus.CANCELLED }) {
                AppliedOpticsPhase.CANCELLED
            } else {
                AppliedOpticsPhase.PARTIAL
            }
        }
        if (ControlStatus.ACKNOWLEDGED_UNCONFIRMED in requested) {
            return AppliedOpticsPhase.ACKNOWLEDGED_UNCONFIRMED
        }
        if (ControlStatus.UNAVAILABLE in requested) {
            return if (requested.all { it == ControlStatus.UNAVAILABLE }) {
                AppliedOpticsPhase.UNAVAILABLE
            } else {
                AppliedOpticsPhase.PARTIAL
            }
        }
        return if (requested.all { it == ControlStatus.APPLIED }) {
            AppliedOpticsPhase.APPLIED
        } else {
            AppliedOpticsPhase.PARTIAL
        }
    }

    private fun statuses(state: AppliedOpticsState): List<ControlStatus> = listOf(
        state.zoom.status,
        state.exposureCompensation.status,
        state.focus.status,
        state.aeLock.status,
        state.awbLock.status,
    )

    private class StaleGenerationCancellation(commandId: OpticalCommandId) : CancellationException(
        "Stale completion for ${commandId.value}",
    )
}

private fun AppliedOpticsState.withZoomResult(
    result: BackendControlResult<Float>,
): AppliedOpticsState {
    val requested = requireNotNull(zoom.requested)
    val (application, metadata, failure) = translateResult(requested, result)
    return copy(
        zoom = application,
        metadata = metadata ?: this.metadata,
        diagnosticCode = failure?.first ?: diagnosticCode,
        diagnosticDetail = failure?.second ?: diagnosticDetail,
    )
}

private fun AppliedOpticsState.withExposureResult(
    result: BackendControlResult<Int>,
): AppliedOpticsState {
    val requested = requireNotNull(exposureCompensation.requested)
    val (application, metadata, failure) = translateResult(requested, result)
    return copy(
        exposureCompensation = application,
        metadata = metadata ?: this.metadata,
        diagnosticCode = failure?.first ?: diagnosticCode,
        diagnosticDetail = failure?.second ?: diagnosticDetail,
    )
}

private fun AppliedOpticsState.withFocusResult(
    result: BackendControlResult<FocusRequest>,
): AppliedOpticsState {
    val requested = requireNotNull(focus.requested)
    val (application, metadata, failure) = translateResult(requested, result)
    return copy(
        focus = application,
        metadata = metadata ?: this.metadata,
        diagnosticCode = failure?.first ?: diagnosticCode,
        diagnosticDetail = failure?.second ?: diagnosticDetail,
    )
}

private fun AppliedOpticsState.withLockResults(result: LockBackendResult): AppliedOpticsState {
    var next = copy(metadata = result.metadata ?: metadata)
    if (aeLock.status == ControlStatus.PENDING) {
        val requested = requireNotNull(aeLock.requested)
        val translated = result.ae?.let { translateResult(requested, it) }
            ?: TranslatedResult(
                application = ControlApplication.failed(
                    requested,
                    "Backend omitted an AE lock result.",
                ),
                failure = "OPTICS_BACKEND_MISSING_RESULT" to "Backend omitted an AE lock result.",
            )
        next = next.copy(
            aeLock = translated.application,
            metadata = translated.metadata ?: next.metadata,
            diagnosticCode = translated.failure?.first ?: next.diagnosticCode,
            diagnosticDetail = translated.failure?.second ?: next.diagnosticDetail,
        )
    }
    if (awbLock.status == ControlStatus.PENDING) {
        val requested = requireNotNull(awbLock.requested)
        val translated = result.awb?.let { translateResult(requested, it) }
            ?: TranslatedResult(
                application = ControlApplication.failed(
                    requested,
                    "Backend omitted an AWB lock result.",
                ),
                failure = "OPTICS_BACKEND_MISSING_RESULT" to "Backend omitted an AWB lock result.",
            )
        next = next.copy(
            awbLock = translated.application,
            metadata = translated.metadata ?: next.metadata,
            diagnosticCode = translated.failure?.first ?: next.diagnosticCode,
            diagnosticDetail = translated.failure?.second ?: next.diagnosticDetail,
        )
    }
    return next
}

private data class TranslatedResult<T>(
    val application: ControlApplication<T>,
    val metadata: AppliedOpticsMetadata? = null,
    val failure: Pair<String, String>? = null,
)

private fun <T> translateResult(
    requested: T,
    result: BackendControlResult<T>,
): TranslatedResult<T> = when (result) {
    is BackendControlResult.Confirmed -> TranslatedResult(
        application = ControlApplication.applied(requested, result.value, result.detail),
        metadata = result.metadata,
    )
    is BackendControlResult.Acknowledged -> TranslatedResult(
        application = ControlApplication.acknowledged(requested, result.detail),
        metadata = result.metadata,
    )
    is BackendControlResult.Unsupported -> TranslatedResult(
        application = ControlApplication.unavailable(requested, result.detail),
    )
    is BackendControlResult.Failed -> TranslatedResult(
        application = ControlApplication.failed(requested, result.detail),
        failure = result.diagnosticCode to result.detail,
    )
    is BackendControlResult.Cancelled -> TranslatedResult(
        application = ControlApplication.cancelled(requested, result.detail),
        failure = "OPTICS_BACKEND_CANCELLED" to result.detail,
    )
}


private fun AppliedOpticsState.failRequestedControls(
    code: String,
    detail: String,
): AppliedOpticsState = copy(
    zoom = zoom.failIfRequested(detail),
    exposureCompensation = exposureCompensation.failIfRequested(detail),
    focus = focus.failIfRequested(detail),
    aeLock = aeLock.failIfRequested(detail),
    awbLock = awbLock.failIfRequested(detail),
    phase = AppliedOpticsPhase.FAILED,
    diagnosticCode = code,
    diagnosticDetail = detail,
)

private fun <T> ControlApplication<T>.failIfRequested(detail: String): ControlApplication<T> =
    if (
        status == ControlStatus.PENDING ||
            status == ControlStatus.ACKNOWLEDGED_UNCONFIRMED ||
            status == ControlStatus.APPLIED
    ) {
        val value = requested ?: applied
        if (value != null) ControlApplication.failed(value, detail) else this
    } else {
        this
    }

private fun AppliedOpticsState.failPending(code: String, detail: String): AppliedOpticsState = copy(
    zoom = zoom.failIfPending(detail),
    exposureCompensation = exposureCompensation.failIfPending(detail),
    focus = focus.failIfPending(detail),
    aeLock = aeLock.failIfPending(detail),
    awbLock = awbLock.failIfPending(detail),
    phase = AppliedOpticsPhase.FAILED,
    diagnosticCode = code,
    diagnosticDetail = detail,
)

private fun AppliedOpticsState.cancelPending(detail: String): AppliedOpticsState = copy(
    zoom = zoom.cancelIfPending(detail),
    exposureCompensation = exposureCompensation.cancelIfPending(detail),
    focus = focus.cancelIfPending(detail),
    aeLock = aeLock.cancelIfPending(detail),
    awbLock = awbLock.cancelIfPending(detail),
    phase = AppliedOpticsPhase.CANCELLED,
    diagnosticCode = "OPTICS_COMMAND_CANCELLED",
    diagnosticDetail = detail,
)

private fun <T> ControlApplication<T>.failIfPending(detail: String): ControlApplication<T> =
    if (status == ControlStatus.PENDING) {
        ControlApplication.failed(requireNotNull(requested), detail)
    } else {
        this
    }

private fun <T> ControlApplication<T>.cancelIfPending(detail: String): ControlApplication<T> =
    if (status == ControlStatus.PENDING) {
        ControlApplication.cancelled(requested, detail)
    } else {
        this
    }
