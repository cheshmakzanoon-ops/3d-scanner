package com.realityforge.scanner.capture.controls

/**
 * Stable identity for one bound capture session. A token must never be reused after an
 * unbind, permission loss, lifecycle disposal, or project/revision change.
 */
@JvmInline
value class CaptureSessionToken(val value: String) {
    init {
        require(value.isNotBlank()) { "Capture session token must not be blank." }
    }
}

@JvmInline
value class OpticalCommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "Optical command id must not be blank." }
    }
}

data class CameraIdentity(
    val logicalCameraId: String,
    val physicalCameraId: String? = null,
) {
    init {
        require(logicalCameraId.isNotBlank()) { "Logical camera id must not be blank." }
        require(physicalCameraId == null || physicalCameraId.isNotBlank()) {
            "Physical camera id must be null or non-blank."
        }
    }
}

enum class LensFacing {
    BACK,
    FRONT,
    EXTERNAL,
    UNKNOWN,
}

enum class FocusMode {
    AUTO,
    CONTINUOUS_PICTURE,
    CONTINUOUS_VIDEO,
    MACRO,
    EXTENDED_DEPTH_OF_FIELD,
    MANUAL,
    FIXED,
    UNKNOWN,
}

enum class StabilizationMode {
    OPTICAL,
    VIDEO,
    PREVIEW,
}

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Pixel dimensions must be positive." }
    }

    val area: Long get() = width.toLong() * height.toLong()
}

data class IntCropRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left && bottom > top) { "Crop region must have positive area." }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && x in 0f..1f) { "x must be finite and normalized." }
        require(y.isFinite() && y in 0f..1f) { "y must be finite and normalized." }
    }
}

data class ZoomCapability(
    val minimumRatio: Float,
    val maximumRatio: Float,
    val currentRatio: Float,
) {
    init {
        require(minimumRatio.isFinite() && minimumRatio > 0f) {
            "Minimum zoom ratio must be finite and positive."
        }
        require(maximumRatio.isFinite() && maximumRatio >= minimumRatio) {
            "Maximum zoom ratio must be finite and at least the minimum."
        }
        require(currentRatio.isFinite() && currentRatio in minimumRatio..maximumRatio) {
            "Current zoom ratio must be inside the supported range."
        }
    }

    fun supports(ratio: Float): Boolean = ratio.isFinite() && ratio in minimumRatio..maximumRatio
}

data class ExposureCompensationCapability(
    val minimumIndex: Int,
    val maximumIndex: Int,
    val currentIndex: Int,
    val stepEv: Float?,
) {
    init {
        require(maximumIndex >= minimumIndex) { "Exposure range is inverted." }
        require(currentIndex in minimumIndex..maximumIndex) {
            "Current exposure index must be inside the supported range."
        }
        require(stepEv == null || (stepEv.isFinite() && stepEv > 0f)) {
            "Exposure step must be positive when reported."
        }
    }

    val supported: Boolean get() = minimumIndex != 0 || maximumIndex != 0

    fun supports(index: Int): Boolean = index in minimumIndex..maximumIndex
}

data class CameraCapabilities(
    val camera: CameraIdentity,
    val lensFacing: LensFacing,
    val physicalCameraIds: List<String>,
    val focalLengthsMm: List<Float>,
    val activeArray: IntCropRegion?,
    val jpegOutputSizes: List<PixelSize>,
    val zoom: ZoomCapability,
    val exposureCompensation: ExposureCompensationCapability,
    val focusModes: Set<FocusMode>,
    val focusMeteringSupported: Boolean,
    val aeMeteringSupported: Boolean,
    val awbMeteringSupported: Boolean,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val stabilizationModes: Set<StabilizationMode>,
) {
    init {
        require(physicalCameraIds.none { it.isBlank() }) {
            "Physical camera ids must not contain blank values."
        }
        require(physicalCameraIds.distinct().size == physicalCameraIds.size) {
            "Physical camera ids must be unique."
        }
        require(focalLengthsMm.all { it.isFinite() && it > 0f }) {
            "Focal lengths must be finite and positive."
        }
        require(jpegOutputSizes.distinct().size == jpegOutputSizes.size) {
            "JPEG output sizes must be unique."
        }
    }

    val largestJpegOutput: PixelSize? = jpegOutputSizes.maxByOrNull { it.area }

    fun supportsPhysicalCamera(cameraId: String): Boolean = cameraId in physicalCameraIds
}

sealed class FocusRequest {
    object KeepCurrent : FocusRequest()
    object Cancel : FocusRequest()

    data class MeterAt(
        val point: NormalizedPoint,
        val relativeSize: Float = 0.15f,
        val autoCancelMillis: Long = 3_000L,
    ) : FocusRequest() {
        init {
            require(relativeSize.isFinite() && relativeSize in 0.01f..1f) {
                "Metering region size must be in [0.01, 1]."
            }
            require(autoCancelMillis in 500L..10_000L) {
                "Focus auto-cancel must remain bounded."
            }
        }
    }
}

enum class LockDirective {
    KEEP,
    LOCK,
    UNLOCK,
}

data class OpticalControlRequest(
    val sessionToken: CaptureSessionToken,
    val commandId: OpticalCommandId,
    val expectedCamera: CameraIdentity,
    val zoomRatio: Float? = null,
    val exposureCompensationIndex: Int? = null,
    val focus: FocusRequest = FocusRequest.KeepCurrent,
    val aeLock: LockDirective = LockDirective.KEEP,
    val awbLock: LockDirective = LockDirective.KEEP,
    val timeoutMillis: Long = 3_000L,
) {
    init {
        require(zoomRatio == null || (zoomRatio.isFinite() && zoomRatio > 0f)) {
            "Requested zoom must be finite and positive."
        }
        require(timeoutMillis in 250L..15_000L) {
            "Optical command timeout must be bounded."
        }
    }
}

enum class OpticsConfirmationSource {
    CAMERA2_CAPTURE_RESULT,
    CAMERAX_CONTROL_ACKNOWLEDGEMENT,
    CAMERA_INFO_SNAPSHOT,
}

/**
 * Capture-time optical metadata. Agent 013 can map this value into the canonical photo
 * metadata record without depending on CameraX or Camera2 classes.
 */
data class AppliedOpticsMetadata(
    val camera: CameraIdentity,
    val activePhysicalCameraId: String?,
    val focalLengthMm: Float?,
    val zoomRatio: Float?,
    val cropRegion: IntCropRegion?,
    val exposureTimeNanos: Long?,
    val sensitivityIso: Int?,
    val aperture: Float?,
    val focusDistanceDiopters: Float?,
    val aeLocked: Boolean?,
    val awbLocked: Boolean?,
    val sensorTimestampNanos: Long?,
    val frameNumber: Long?,
    val source: OpticsConfirmationSource,
) {
    init {
        require(activePhysicalCameraId == null || activePhysicalCameraId.isNotBlank()) {
            "Active physical camera id must be null or non-blank."
        }
        require(focalLengthMm == null || (focalLengthMm.isFinite() && focalLengthMm > 0f)) {
            "Focal length must be finite and positive when present."
        }
        require(zoomRatio == null || (zoomRatio.isFinite() && zoomRatio > 0f)) {
            "Zoom ratio must be finite and positive when present."
        }
        require(exposureTimeNanos == null || exposureTimeNanos > 0L) {
            "Exposure time must be positive when present."
        }
        require(sensitivityIso == null || sensitivityIso > 0) {
            "ISO sensitivity must be positive when present."
        }
        require(aperture == null || (aperture.isFinite() && aperture > 0f)) {
            "Aperture must be finite and positive when present."
        }
        require(
            focusDistanceDiopters == null ||
                (focusDistanceDiopters.isFinite() && focusDistanceDiopters >= 0f),
        ) { "Focus distance must be finite and non-negative when present." }
        require(sensorTimestampNanos == null || sensorTimestampNanos >= 0L) {
            "Sensor timestamp must be non-negative when present."
        }
        require(frameNumber == null || frameNumber >= 0L) {
            "Frame number must be non-negative when present."
        }
    }
}

enum class ControlStatus {
    NOT_REQUESTED,
    PENDING,
    ACKNOWLEDGED_UNCONFIRMED,
    APPLIED,
    UNAVAILABLE,
    FAILED,
    CANCELLED,
}

data class ControlApplication<T>(
    val status: ControlStatus,
    val requested: T? = null,
    val applied: T? = null,
    val detail: String? = null,
) {
    init {
        if (status == ControlStatus.APPLIED) {
            require(applied != null) { "Applied control state requires an applied value." }
        }
        if (status == ControlStatus.PENDING || status == ControlStatus.ACKNOWLEDGED_UNCONFIRMED) {
            require(requested != null) { "Pending or acknowledged state requires a requested value." }
        }
    }

    companion object {
        fun <T> notRequested(): ControlApplication<T> = ControlApplication(ControlStatus.NOT_REQUESTED)

        fun <T> pending(value: T): ControlApplication<T> =
            ControlApplication(status = ControlStatus.PENDING, requested = value)

        fun <T> acknowledged(value: T, detail: String? = null): ControlApplication<T> =
            ControlApplication(
                status = ControlStatus.ACKNOWLEDGED_UNCONFIRMED,
                requested = value,
                detail = detail,
            )

        fun <T> applied(requested: T, applied: T, detail: String? = null): ControlApplication<T> =
            ControlApplication(
                status = ControlStatus.APPLIED,
                requested = requested,
                applied = applied,
                detail = detail,
            )

        fun <T> unavailable(value: T, detail: String): ControlApplication<T> =
            ControlApplication(
                status = ControlStatus.UNAVAILABLE,
                requested = value,
                detail = detail,
            )

        fun <T> failed(value: T, detail: String): ControlApplication<T> =
            ControlApplication(
                status = ControlStatus.FAILED,
                requested = value,
                detail = detail,
            )

        fun <T> cancelled(value: T?, detail: String): ControlApplication<T> =
            ControlApplication(
                status = ControlStatus.CANCELLED,
                requested = value,
                detail = detail,
            )
    }
}

enum class AppliedOpticsPhase {
    IDLE,
    PENDING,
    ACKNOWLEDGED_UNCONFIRMED,
    APPLIED,
    PARTIAL,
    UNAVAILABLE,
    FAILED,
    CANCELLED,
    DISPOSED,
}

data class AppliedOpticsState(
    val sessionToken: CaptureSessionToken?,
    val commandId: OpticalCommandId?,
    val camera: CameraIdentity?,
    val phase: AppliedOpticsPhase,
    val zoom: ControlApplication<Float> = ControlApplication.notRequested(),
    val exposureCompensation: ControlApplication<Int> = ControlApplication.notRequested(),
    val focus: ControlApplication<FocusRequest> = ControlApplication.notRequested(),
    val aeLock: ControlApplication<Boolean> = ControlApplication.notRequested(),
    val awbLock: ControlApplication<Boolean> = ControlApplication.notRequested(),
    val metadata: AppliedOpticsMetadata? = null,
    val diagnosticCode: String? = null,
    val diagnosticDetail: String? = null,
) {
    companion object {
        val Unbound = AppliedOpticsState(
            sessionToken = null,
            commandId = null,
            camera = null,
            phase = AppliedOpticsPhase.IDLE,
        )
    }
}
