package com.realityforge.scanner.capture.controls

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/** Reads CameraX state and Camera2 characteristics without assuming every key is present. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object CameraXCapabilityReader {
    fun read(
        cameraInfo: CameraInfo,
        selectedPhysicalCameraId: String? = null,
    ): CameraCapabilities {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val cameraId = camera2Info.cameraId
        val characteristics = Camera2CharacteristicSnapshot.from(camera2Info)
        val physicalIds = linkedSetOf<String>().apply {
            addAll(characteristics.physicalCameraIds)
            runCatching {
                cameraInfo.physicalCameraInfos.forEach { physicalInfo ->
                    add(Camera2CameraInfo.from(physicalInfo).cameraId)
                }
            }
        }
        val zoomState = cameraInfo.zoomState.value
        val exposureState = cameraInfo.exposureState
        return Camera2CapabilityMapper.interpret(
            cameraId = cameraId,
            characteristics = characteristics,
            selectedPhysicalCameraId = selectedPhysicalCameraId,
            physicalCameraIds = physicalIds,
            currentZoomRatio = zoomState?.zoomRatio,
            minimumZoomRatio = zoomState?.minZoomRatio,
            maximumZoomRatio = zoomState?.maxZoomRatio,
            exposureMinimumIndex = exposureState.exposureCompensationRange.lower,
            exposureMaximumIndex = exposureState.exposureCompensationRange.upper,
            exposureCurrentIndex = exposureState.exposureCompensationIndex,
            exposureStepEv = exposureState.exposureCompensationStep.toFloat()
                .takeIf { exposureState.isExposureCompensationSupported },
        )
    }
}

/**
 * Small immutable Camera2 snapshot used by instrumentation tests and the CameraX adapter.
 * It prevents callers from retaining a vendor CameraCharacteristics object beyond discovery.
 */
data class Camera2CharacteristicSnapshot(
    val lensFacing: Int?,
    val physicalCameraIds: Set<String>,
    val focalLengthsMm: List<Float>,
    val activeArray: Rect?,
    val jpegOutputSizes: List<android.util.Size>,
    val zoomRatioRange: Range<Float>?,
    val maximumDigitalZoom: Float?,
    val afModes: List<Int>,
    val minimumFocusDistance: Float?,
    val maximumAfRegions: Int?,
    val maximumAeRegions: Int?,
    val maximumAwbRegions: Int?,
    val aeLockAvailable: Boolean?,
    val awbLockAvailable: Boolean?,
    val opticalStabilizationModes: List<Int>,
    val videoStabilizationModes: List<Int>,
) {
    companion object {
        @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
        fun from(info: Camera2CameraInfo): Camera2CharacteristicSnapshot =
            Camera2CharacteristicSnapshot(
                lensFacing = info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING),
                physicalCameraIds = emptySet(),
                focalLengthsMm = info
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty(),
                activeArray = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                ),
                jpegOutputSizes = info
                    .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                    ?.toList()
                    .orEmpty(),
                zoomRatioRange = if (Build.VERSION.SDK_INT >= 30) {
                    readZoomRatioRange(info)
                } else {
                    null
                },
                maximumDigitalZoom = info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                ),
                afModes = info
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.toList()
                    .orEmpty(),
                minimumFocusDistance = info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                ),
                maximumAfRegions = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
                ),
                maximumAeRegions = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_MAX_REGIONS_AE,
                ),
                maximumAwbRegions = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_MAX_REGIONS_AWB,
                ),
                aeLockAvailable = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE,
                ),
                awbLockAvailable = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
                ),
                opticalStabilizationModes = info
                    .getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                    )
                    ?.toList()
                    .orEmpty(),
                videoStabilizationModes = info
                    .getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                    )
                    ?.toList()
                    .orEmpty(),
            )

        fun from(characteristics: CameraCharacteristics): Camera2CharacteristicSnapshot =
            Camera2CharacteristicSnapshot(
                lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
                physicalCameraIds = characteristics.physicalCameraIds,
                focalLengthsMm = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty(),
                activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                jpegOutputSizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                    ?.toList()
                    .orEmpty(),
                zoomRatioRange = if (Build.VERSION.SDK_INT >= 30) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else {
                    null
                },
                maximumDigitalZoom = characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                ),
                afModes = characteristics
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.toList()
                    .orEmpty(),
                minimumFocusDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                ),
                maximumAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),
                maximumAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE),
                maximumAwbRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB),
                aeLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE),
                awbLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE),
                opticalStabilizationModes = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.toList()
                    .orEmpty(),
                videoStabilizationModes = characteristics
                    .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.toList()
                    .orEmpty(),
            )

        @androidx.annotation.RequiresApi(30)
        @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
        private fun readZoomRatioRange(info: Camera2CameraInfo): Range<Float>? =
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    }
}

/**
 * Maps captured Camera2 values into the bounded public contract. Kept separate from discovery
 * so instrumentation can exercise real platform characteristics without binding a preview.
 */
object Camera2CapabilityMapper {
    fun interpret(
        cameraId: String,
        characteristics: Camera2CharacteristicSnapshot,
        selectedPhysicalCameraId: String? = null,
        physicalCameraIds: Collection<String> = characteristics.physicalCameraIds,
        currentZoomRatio: Float? = null,
        minimumZoomRatio: Float? = null,
        maximumZoomRatio: Float? = null,
        exposureMinimumIndex: Int? = null,
        exposureMaximumIndex: Int? = null,
        exposureCurrentIndex: Int? = null,
        exposureStepEv: Float? = null,
    ): CameraCapabilities {
        val zoomMinimum = minimumZoomRatio ?: characteristics.zoomRatioRange?.lower ?: 1f
        val zoomMaximum = maximumZoomRatio
            ?: characteristics.zoomRatioRange?.upper
            ?: characteristics.maximumDigitalZoom
            ?: zoomMinimum
        val activeArray = characteristics.activeArray?.toDomainCrop()
        val focusModes = characteristics.afModes.mapTo(linkedSetOf()) { mode ->
            mapFocusMode(mode, characteristics.minimumFocusDistance)
        }
        val stabilization = linkedSetOf<StabilizationMode>().apply {
            if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in
                characteristics.opticalStabilizationModes
            ) {
                add(StabilizationMode.OPTICAL)
            }
            if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in
                characteristics.videoStabilizationModes
            ) {
                add(StabilizationMode.VIDEO)
            }
            if (
                Build.VERSION.SDK_INT >= 33 &&
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in
                    characteristics.videoStabilizationModes
            ) {
                add(StabilizationMode.PREVIEW)
            }
        }

        return CameraCapabilityInterpreter.interpret(
            RawCameraCapabilities(
                logicalCameraId = cameraId,
                selectedPhysicalCameraId = selectedPhysicalCameraId,
                lensFacing = mapLensFacing(characteristics.lensFacing),
                physicalCameraIds = physicalCameraIds,
                focalLengthsMm = characteristics.focalLengthsMm,
                activeArray = activeArray,
                jpegOutputSizes = characteristics.jpegOutputSizes.mapNotNull { size ->
                    if (size.width > 0 && size.height > 0) PixelSize(size.width, size.height) else null
                },
                minimumZoomRatio = zoomMinimum,
                maximumZoomRatio = zoomMaximum,
                currentZoomRatio = currentZoomRatio,
                exposureMinimumIndex = exposureMinimumIndex,
                exposureMaximumIndex = exposureMaximumIndex,
                exposureCurrentIndex = exposureCurrentIndex,
                exposureStepEv = exposureStepEv,
                focusModes = focusModes,
                maximumAfRegions = characteristics.maximumAfRegions,
                maximumAeRegions = characteristics.maximumAeRegions,
                maximumAwbRegions = characteristics.maximumAwbRegions,
                aeLockAvailable = characteristics.aeLockAvailable,
                awbLockAvailable = characteristics.awbLockAvailable,
                stabilizationModes = stabilization,
            ),
        )
    }

    private fun mapLensFacing(value: Int?): LensFacing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }

    private fun mapFocusMode(value: Int, minimumFocusDistance: Float?): FocusMode = when (value) {
        CaptureRequest.CONTROL_AF_MODE_AUTO -> FocusMode.AUTO
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> FocusMode.CONTINUOUS_PICTURE
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> FocusMode.CONTINUOUS_VIDEO
        CaptureRequest.CONTROL_AF_MODE_MACRO -> FocusMode.MACRO
        CaptureRequest.CONTROL_AF_MODE_EDOF -> FocusMode.EXTENDED_DEPTH_OF_FIELD
        CaptureRequest.CONTROL_AF_MODE_OFF -> if (
            minimumFocusDistance != null && minimumFocusDistance > 0f
        ) {
            FocusMode.MANUAL
        } else {
            FocusMode.FIXED
        }
        else -> FocusMode.UNKNOWN
    }
}

private fun Rect.toDomainCrop(): IntCropRegion? =
    if (width() > 0 && height() > 0) {
        IntCropRegion(left, top, right, bottom)
    } else {
        null
    }
