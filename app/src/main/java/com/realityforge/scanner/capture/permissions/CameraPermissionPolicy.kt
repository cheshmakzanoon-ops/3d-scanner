package com.realityforge.scanner.capture.permissions

enum class CameraPermissionStatus {
    GRANTED,
    REQUESTABLE,
    RATIONALE_REQUIRED,
    PERMANENTLY_DENIED,
    CAMERA_UNAVAILABLE,
}

data class CameraPermissionObservation(
    val granted: Boolean,
    val shouldShowRationale: Boolean,
    val hasRequestedBefore: Boolean,
    val cameraAvailable: Boolean,
)

/** Pure permission interpretation; Android callbacks only supply observations. */
object CameraPermissionPolicy {
    fun evaluate(observation: CameraPermissionObservation): CameraPermissionStatus = when {
        !observation.cameraAvailable -> CameraPermissionStatus.CAMERA_UNAVAILABLE
        observation.granted -> CameraPermissionStatus.GRANTED
        observation.shouldShowRationale -> CameraPermissionStatus.RATIONALE_REQUIRED
        observation.hasRequestedBefore -> CameraPermissionStatus.PERMANENTLY_DENIED
        else -> CameraPermissionStatus.REQUESTABLE
    }
}
