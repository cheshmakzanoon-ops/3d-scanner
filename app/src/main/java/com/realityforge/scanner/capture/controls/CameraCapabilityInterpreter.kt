package com.realityforge.scanner.capture.controls

/**
 * Platform-neutral capability input. Android Camera2 values are translated into this shape
 * before interpretation so malformed or vendor-specific characteristics cannot leak into UI
 * state as fabricated support.
 */
data class RawCameraCapabilities(
    val logicalCameraId: String,
    val selectedPhysicalCameraId: String? = null,
    val lensFacing: LensFacing = LensFacing.UNKNOWN,
    val physicalCameraIds: Collection<String> = emptyList(),
    val focalLengthsMm: Collection<Float> = emptyList(),
    val activeArray: IntCropRegion? = null,
    val jpegOutputSizes: Collection<PixelSize> = emptyList(),
    val minimumZoomRatio: Float? = null,
    val maximumZoomRatio: Float? = null,
    val currentZoomRatio: Float? = null,
    val exposureMinimumIndex: Int? = null,
    val exposureMaximumIndex: Int? = null,
    val exposureCurrentIndex: Int? = null,
    val exposureStepEv: Float? = null,
    val focusModes: Collection<FocusMode> = emptyList(),
    val maximumAfRegions: Int? = null,
    val maximumAeRegions: Int? = null,
    val maximumAwbRegions: Int? = null,
    val aeLockAvailable: Boolean? = null,
    val awbLockAvailable: Boolean? = null,
    val stabilizationModes: Collection<StabilizationMode> = emptyList(),
)

object CameraCapabilityInterpreter {
    private const val MAX_PHYSICAL_CAMERA_IDS = 16
    private const val MAX_FOCAL_LENGTHS = 16
    private const val MAX_JPEG_SIZES = 64
    private const val DEFAULT_ZOOM_RATIO = 1f

    fun interpret(raw: RawCameraCapabilities): CameraCapabilities {
        require(raw.logicalCameraId.isNotBlank()) { "Camera id must not be blank." }

        val physicalIds = raw.physicalCameraIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it == raw.logicalCameraId }
            .distinct()
            .sorted()
            .take(MAX_PHYSICAL_CAMERA_IDS)
            .toList()

        val selectedPhysicalId = raw.selectedPhysicalCameraId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.takeIf { it in physicalIds }

        val focalLengths = raw.focalLengthsMm
            .asSequence()
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
            .take(MAX_FOCAL_LENGTHS)
            .toList()

        val jpegSizes = raw.jpegOutputSizes
            .asSequence()
            .distinct()
            .sortedWith(compareByDescending<PixelSize> { it.area }.thenByDescending { it.width })
            .take(MAX_JPEG_SIZES)
            .toList()

        val zoom = sanitizeZoom(raw)
        val exposure = sanitizeExposure(raw)
        val focusModes = raw.focusModes.filterTo(linkedSetOf()) { it != FocusMode.UNKNOWN }
        val stabilization = raw.stabilizationModes.toSet()

        return CameraCapabilities(
            camera = CameraIdentity(raw.logicalCameraId, selectedPhysicalId),
            lensFacing = raw.lensFacing,
            physicalCameraIds = physicalIds,
            focalLengthsMm = focalLengths,
            activeArray = raw.activeArray,
            jpegOutputSizes = jpegSizes,
            zoom = zoom,
            exposureCompensation = exposure,
            focusModes = focusModes,
            focusMeteringSupported = positive(raw.maximumAfRegions) && focusModes.any {
                it != FocusMode.FIXED
            },
            aeMeteringSupported = positive(raw.maximumAeRegions),
            awbMeteringSupported = positive(raw.maximumAwbRegions),
            aeLockAvailable = raw.aeLockAvailable == true,
            awbLockAvailable = raw.awbLockAvailable == true,
            stabilizationModes = stabilization,
        )
    }

    private fun sanitizeZoom(raw: RawCameraCapabilities): ZoomCapability {
        val minimum = raw.minimumZoomRatio
            ?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_ZOOM_RATIO
        val maximumCandidate = raw.maximumZoomRatio
            ?.takeIf { it.isFinite() && it >= minimum }
            ?: minimum
        val maximum = maximumCandidate.coerceAtLeast(minimum)
        val current = raw.currentZoomRatio
            ?.takeIf(Float::isFinite)
            ?.coerceIn(minimum, maximum)
            ?: DEFAULT_ZOOM_RATIO.coerceIn(minimum, maximum)
        return ZoomCapability(minimum, maximum, current)
    }

    private fun sanitizeExposure(raw: RawCameraCapabilities): ExposureCompensationCapability {
        val minimum = raw.exposureMinimumIndex ?: 0
        val maximum = raw.exposureMaximumIndex ?: 0
        if (maximum < minimum) {
            return ExposureCompensationCapability(0, 0, 0, null)
        }
        val current = (raw.exposureCurrentIndex ?: 0).coerceIn(minimum, maximum)
        val step = raw.exposureStepEv?.takeIf { it.isFinite() && it > 0f }
        return ExposureCompensationCapability(minimum, maximum, current, step)
    }

    private fun positive(value: Int?): Boolean = value != null && value > 0
}
