package com.realityforge.scanner.capture.controls

data class FloatControlRange(
    val minimum: Float,
    val maximum: Float,
    val current: Float,
    val enabled: Boolean,
)

data class IntControlRange(
    val minimum: Int,
    val maximum: Int,
    val current: Int,
    val stepEv: Float?,
    val enabled: Boolean,
)

enum class LockControlPresentation {
    UNAVAILABLE,
    UNLOCKED,
    LOCKING,
    LOCKED,
    UNLOCKING,
    ACKNOWLEDGED_UNCONFIRMED,
    FAILED,
}

/**
 * Rendering-ready, platform-neutral state for Agent 014's Compose screen. Text is represented
 * by stable diagnostic/status values so localization remains with the UI/string owner.
 */
data class CameraOpticsUiModel(
    val camera: CameraIdentity,
    val zoom: FloatControlRange,
    val exposureCompensation: IntControlRange,
    val focusMeteringEnabled: Boolean,
    val aeLock: LockControlPresentation,
    val awbLock: LockControlPresentation,
    val commandInFlight: Boolean,
    val controlsEnabled: Boolean,
    val diagnosticCode: String?,
)

object CameraOpticsUiModelFactory {
    fun create(
        capabilities: CameraCapabilities,
        state: AppliedOpticsState,
    ): CameraOpticsUiModel {
        val commandInFlight = state.phase == AppliedOpticsPhase.PENDING
        val disposed = state.phase == AppliedOpticsPhase.DISPOSED
        val metadataZoom = state.metadata?.zoomRatio
        val appliedZoom = state.zoom.applied
        val zoomCurrent = sequenceOf(appliedZoom, metadataZoom, capabilities.zoom.currentRatio)
            .filterNotNull()
            .first()
            .coerceIn(capabilities.zoom.minimumRatio, capabilities.zoom.maximumRatio)
        val appliedExposure = state.exposureCompensation.applied
            ?: capabilities.exposureCompensation.currentIndex

        return CameraOpticsUiModel(
            camera = capabilities.camera,
            zoom = FloatControlRange(
                minimum = capabilities.zoom.minimumRatio,
                maximum = capabilities.zoom.maximumRatio,
                current = zoomCurrent,
                enabled = !disposed && capabilities.zoom.maximumRatio > capabilities.zoom.minimumRatio,
            ),
            exposureCompensation = IntControlRange(
                minimum = capabilities.exposureCompensation.minimumIndex,
                maximum = capabilities.exposureCompensation.maximumIndex,
                current = appliedExposure.coerceIn(
                    capabilities.exposureCompensation.minimumIndex,
                    capabilities.exposureCompensation.maximumIndex,
                ),
                stepEv = capabilities.exposureCompensation.stepEv,
                enabled = !disposed && capabilities.exposureCompensation.supported,
            ),
            focusMeteringEnabled = !disposed && capabilities.focusMeteringSupported,
            aeLock = lockPresentation(
                available = capabilities.aeLockAvailable,
                application = state.aeLock,
            ),
            awbLock = lockPresentation(
                available = capabilities.awbLockAvailable,
                application = state.awbLock,
            ),
            commandInFlight = commandInFlight,
            controlsEnabled = !disposed && state.phase != AppliedOpticsPhase.CANCELLED,
            diagnosticCode = state.diagnosticCode,
        )
    }

    private fun lockPresentation(
        available: Boolean,
        application: ControlApplication<Boolean>,
    ): LockControlPresentation {
        if (!available || application.status == ControlStatus.UNAVAILABLE) {
            return LockControlPresentation.UNAVAILABLE
        }
        return when (application.status) {
            ControlStatus.NOT_REQUESTED -> LockControlPresentation.UNLOCKED
            ControlStatus.PENDING -> if (application.requested == true) {
                LockControlPresentation.LOCKING
            } else {
                LockControlPresentation.UNLOCKING
            }
            ControlStatus.ACKNOWLEDGED_UNCONFIRMED ->
                LockControlPresentation.ACKNOWLEDGED_UNCONFIRMED
            ControlStatus.APPLIED -> if (application.applied == true) {
                LockControlPresentation.LOCKED
            } else {
                LockControlPresentation.UNLOCKED
            }
            ControlStatus.FAILED,
            ControlStatus.CANCELLED,
            -> LockControlPresentation.FAILED
            ControlStatus.UNAVAILABLE -> LockControlPresentation.UNAVAILABLE
        }
    }
}
