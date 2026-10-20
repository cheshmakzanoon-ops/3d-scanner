package com.realityforge.scanner.capture.controls

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only Camera2 callback. It never submits, stops, or closes requests/sessions. The newest
 * result is used to confirm that a CameraX/Camera2 control reached the active repeating request.
 */
class Camera2CaptureResultObserver(
    private val camera: CameraIdentity,
    private val activeArray: IntCropRegion?,
) : CameraCaptureSession.CaptureCallback() {
    private val mutableMetadata = MutableStateFlow<AppliedOpticsMetadata?>(null)
    val metadata: StateFlow<AppliedOpticsMetadata?> = mutableMetadata.asStateFlow()
    @Volatile private var stillMetadata: AppliedOpticsMetadata? = null
    @Volatile var latestFrameNumber: Long = -1L
        private set

    /** A repeating preview frame is never used as if it belonged to a saved still photograph. */
    fun stillAfter(frameExclusive: Long): AppliedOpticsMetadata? = stillMetadata?.takeIf {
        (it.frameNumber ?: -1L) > frameExclusive
    }

    private val mutableFailure = MutableStateFlow<CaptureFailureSnapshot?>(null)
    val failure: StateFlow<CaptureFailureSnapshot?> = mutableFailure.asStateFlow()

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        latestFrameNumber = maxOf(latestFrameNumber, result.frameNumber)
        // Invalid vendor metadata must not crash the camera callback thread.
        val observed = runCatching { result.toMetadata(camera, activeArray) }.getOrNull()
        mutableMetadata.value = observed
        if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
            stillMetadata = observed
        }
    }

    override fun onCaptureFailed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        failure: CaptureFailure,
    ) {
        mutableFailure.value = CaptureFailureSnapshot(
            frameNumber = failure.frameNumber.takeIf { it >= 0L },
            reason = failure.reason,
            wasImageCaptured = failure.wasImageCaptured(),
        )
    }

    suspend fun awaitLocks(
        aeLocked: Boolean?,
        awbLocked: Boolean?,
        afterFrameExclusive: Long?,
        timeoutMillis: Long,
    ): AppliedOpticsMetadata? = withTimeoutOrNull(timeoutMillis) {
        metadata
            .filterNotNull()
            .first { value ->
                val newer = afterFrameExclusive == null ||
                    (value.frameNumber != null && value.frameNumber > afterFrameExclusive)
                val aeMatches = aeLocked == null || value.aeLocked == aeLocked
                val awbMatches = awbLocked == null || value.awbLocked == awbLocked
                newer && aeMatches && awbMatches
            }
    }
}

data class CaptureFailureSnapshot(
    val frameNumber: Long?,
    val reason: Int,
    val wasImageCaptured: Boolean,
)

private fun TotalCaptureResult.toMetadata(
    camera: CameraIdentity,
    activeArray: IntCropRegion?,
): AppliedOpticsMetadata {
    val crop = get(CaptureResult.SCALER_CROP_REGION)?.let { rect ->
        if (rect.width() > 0 && rect.height() > 0) {
            IntCropRegion(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            null
        }
    }
    val reportedZoom = if (Build.VERSION.SDK_INT >= 30) {
        get(CaptureResult.CONTROL_ZOOM_RATIO)
    } else {
        null
    }
    val cropZoom = if (reportedZoom == null && crop != null && activeArray != null) {
        val widthRatio = activeArray.width.toFloat() / crop.width.toFloat()
        val heightRatio = activeArray.height.toFloat() / crop.height.toFloat()
        minOf(widthRatio, heightRatio).takeIf { it.isFinite() && it > 0f }
    } else {
        null
    }
    val activePhysicalId = runCatching {
        get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
    }.getOrNull()

    return AppliedOpticsMetadata(
        camera = camera,
        activePhysicalCameraId = activePhysicalId,
        focalLengthMm = get(CaptureResult.LENS_FOCAL_LENGTH),
        zoomRatio = reportedZoom ?: cropZoom,
        cropRegion = crop,
        exposureTimeNanos = get(CaptureResult.SENSOR_EXPOSURE_TIME),
        sensitivityIso = get(CaptureResult.SENSOR_SENSITIVITY),
        aperture = get(CaptureResult.LENS_APERTURE),
        focusDistanceDiopters = get(CaptureResult.LENS_FOCUS_DISTANCE),
        aeLocked = get(CaptureResult.CONTROL_AE_LOCK),
        awbLocked = get(CaptureResult.CONTROL_AWB_LOCK),
        sensorTimestampNanos = get(CaptureResult.SENSOR_TIMESTAMP),
        frameNumber = frameNumber.takeIf { it >= 0L },
        source = OpticsConfirmationSource.CAMERA2_CAPTURE_RESULT,
    )
}
