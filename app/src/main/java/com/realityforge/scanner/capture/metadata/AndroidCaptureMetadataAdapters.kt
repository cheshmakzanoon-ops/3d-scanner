package com.realityforge.scanner.capture.metadata

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Read-only EXIF adapter. It never calls saveAttributes or modifies the supplied file. */
object ExifCaptureMetadataAdapter {
    fun read(file: File, fallbackImageSize: PixelSize? = null): CaptureMetadata {
        require(file.isFile) { "Capture metadata source is not a regular file" }
        val exif = ExifInterface(file)
        val issues = linkedSetOf<CaptureMetadataIssue>()

        val widthRead = firstPositiveInt(exif, ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_IMAGE_WIDTH)
        val heightRead = firstPositiveInt(exif, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_IMAGE_LENGTH)
        if (widthRead.hadInvalidValue || heightRead.hadInvalidValue) {
            issues += CaptureMetadataIssue.INVALID_EXIF_DIMENSIONS
        }
        val width = widthRead.value
        val height = heightRead.value
        val imageSize = when {
            width != null && height != null -> runCatching { PixelSize(width, height) }
                .fold(
                    onSuccess = { FieldEvidence.observed(it, EvidenceOrigin.EXIF) },
                    onFailure = {
                        issues += CaptureMetadataIssue.INVALID_EXIF_DIMENSIONS
                        fallbackImageSize?.let { size -> FieldEvidence.observed(size, EvidenceOrigin.CALLER_IMAGE_DIMENSIONS) }
                            ?: FieldEvidence.missing(EvidenceOrigin.EXIF)
                    },
                )
            width != null || height != null -> {
                issues += CaptureMetadataIssue.INVALID_EXIF_DIMENSIONS
                fallbackImageSize?.let { FieldEvidence.observed(it, EvidenceOrigin.CALLER_IMAGE_DIMENSIONS) }
                    ?: FieldEvidence.missing(EvidenceOrigin.EXIF)
            }
            fallbackImageSize != null -> FieldEvidence.observed(fallbackImageSize, EvidenceOrigin.CALLER_IMAGE_DIMENSIONS)
            else -> FieldEvidence.missing(EvidenceOrigin.EXIF)
        }

        val rawOrientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
        val orientation = if (rawOrientation == null) {
            FieldEvidence.missing(EvidenceOrigin.EXIF)
        } else {
            val numeric = rawOrientation.toIntOrNull()
            val parsed = numeric?.let(ImageOrientation::fromExifValue)
            if (parsed == null) {
                issues += CaptureMetadataIssue.INVALID_EXIF_ORIENTATION
                FieldEvidence.missing(EvidenceOrigin.EXIF)
            } else {
                FieldEvidence.observed(parsed, EvidenceOrigin.EXIF)
            }
        }

        val focalMm = positiveDouble(exif, ExifInterface.TAG_FOCAL_LENGTH, issues)
        val directFocal35 = positiveDouble(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, issues)
        val focal35 = if (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) != null) directFocal35
            else ExifFocalMetadata.read(file, exif)
        // CIPA Exif: a zero numerator means digital zoom was not used. Our
        // optical metadata stores a positive scale, so normalize to unit zoom
        // as derived evidence rather than flagging a valid EXIF value invalid.
        val zoom = if (exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO) != null &&
            runCatching { exif.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, Double.NaN) }.getOrNull() == 0.0)
            FieldEvidence.derived(1.0, setOf(EvidenceOrigin.EXIF))
        else positiveDouble(exif, ExifInterface.TAG_DIGITAL_ZOOM_RATIO, issues)
        val make = textEvidence(exif, ExifInterface.TAG_MAKE)
        val model = textEvidence(exif, ExifInterface.TAG_MODEL)
        val lensMake = textEvidence(exif, ExifInterface.TAG_LENS_MAKE)
        val lensModel = textEvidence(exif, ExifInterface.TAG_LENS_MODEL)
        val hasExifIdentity = listOf(make, model, lensMake, lensModel, focalMm, focal35, zoom, orientation)
            .any { it.status != EvidenceStatus.MISSING }

        return CaptureMetadata(
            captureSources = setOf(if (hasExifIdentity) CaptureSource.IMPORTED_EXIF else CaptureSource.IMPORTED_NO_METADATA),
            captureDeviceId = FieldEvidence.missing(EvidenceOrigin.EXIF),
            imageSize = imageSize,
            orientation = orientation,
            logicalCameraId = FieldEvidence.missing(EvidenceOrigin.EXIF),
            physicalCameraId = FieldEvidence.missing(EvidenceOrigin.EXIF),
            reportedCameraMake = make,
            reportedCameraModel = model,
            reportedLensMake = lensMake,
            reportedLensModel = lensModel,
            zoomRatio = zoom,
            cropRegion = FieldEvidence.missing(EvidenceOrigin.EXIF),
            sensorSizeMm = FieldEvidence.missing(EvidenceOrigin.EXIF),
            sensorOrientationDegrees = FieldEvidence.missing(EvidenceOrigin.EXIF),
            focalLengthMm = focalMm,
            focal35mmEquivalent = focal35,
            originalMetadataRetained = true,
            issues = issues,
        )
    }

    private fun textEvidence(exif: ExifInterface, tag: String): FieldEvidence<String> {
        val value = normalizedText(exif.getAttribute(tag))
        return value?.let { FieldEvidence.observed(it, EvidenceOrigin.EXIF) }
            ?: FieldEvidence.missing(EvidenceOrigin.EXIF)
    }

    private data class PositiveIntRead(val value: Int?, val hadInvalidValue: Boolean)

    private fun firstPositiveInt(exif: ExifInterface, vararg tags: String): PositiveIntRead {
        var invalid = false
        for (tag in tags) {
            val raw = exif.getAttribute(tag) ?: continue
            val parsed = raw.toIntOrNull()
            if (parsed != null && parsed > 0) return PositiveIntRead(parsed, invalid)
            invalid = true
        }
        return PositiveIntRead(null, invalid)
    }

    private fun positiveDouble(
        exif: ExifInterface,
        tag: String,
        issues: MutableSet<CaptureMetadataIssue>,
    ): FieldEvidence<Double> {
        val raw = exif.getAttribute(tag) ?: return FieldEvidence.missing(EvidenceOrigin.EXIF)
        val value = runCatching { exif.getAttributeDouble(tag, Double.NaN) }.getOrNull()
        if (value == null || !value.isFinite() || value <= 0.0) {
            issues += CaptureMetadataIssue.INVALID_EXIF_NUMERIC_VALUE
            return FieldEvidence.missing(EvidenceOrigin.EXIF)
        }
        // Keep the raw lookup above: a missing tag must not become ExifInterface's numeric default.
        check(raw.isNotEmpty())
        return FieldEvidence.observed(value, EvidenceOrigin.EXIF)
    }
}

/**
 * Camera2/CameraX adapter. A physical camera id is accepted only from the capture-session
 * binding/callback; the logical camera's set of possible physical ids is never guessed.
 *
 * Camera IDs are interpreted inside [captureDeviceId], an opaque app-local namespace supplied
 * by the session owner. The adapter never reads a hardware serial or invents that namespace.
 */
object CameraCaptureMetadataAdapter {
    fun fromCamera2(
        logicalCameraId: String,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        imageSize: PixelSize,
        encodedOrientation: ImageOrientation?,
        fallbackZoomRatio: Double? = null,
        captureDeviceId: String? = null,
        deviceManufacturer: String? = Build.MANUFACTURER,
        deviceModel: String? = Build.MODEL,
    ): CaptureMetadata = build(
        sources = setOf(CaptureSource.CAMERA2_CAPTURE),
        captureDeviceId = captureDeviceId,
        logicalCameraId = logicalCameraId,
        physicalCameraId = physicalCameraId,
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
        sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
        availableFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
        captureResult = captureResult,
        imageSize = imageSize,
        encodedOrientation = encodedOrientation,
        fallbackZoomRatio = fallbackZoomRatio,
    )

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @Suppress("DEPRECATION")
    fun fromCameraX(
        cameraInfo: CameraInfo,
        physicalCameraId: String?,
        captureResult: CaptureResult?,
        imageSize: PixelSize,
        encodedOrientation: ImageOrientation?,
        captureDeviceId: String? = null,
        deviceManufacturer: String? = Build.MANUFACTURER,
        deviceModel: String? = Build.MODEL,
    ): CaptureMetadata {
        val camera2 = Camera2CameraInfo.from(cameraInfo)
        return build(
            sources = setOf(CaptureSource.CAMERAX_CAPTURE) + if (captureResult != null) setOf(CaptureSource.CAMERA2_CAPTURE) else emptySet(),
            captureDeviceId = captureDeviceId,
            logicalCameraId = camera2.cameraId,
            physicalCameraId = physicalCameraId,
            deviceManufacturer = deviceManufacturer,
            deviceModel = deviceModel,
            activeArray = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            sensorSize = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            sensorOrientation = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION),
            availableFocalLengths = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
            captureResult = captureResult,
            imageSize = imageSize,
            encodedOrientation = encodedOrientation,
            fallbackZoomRatio = cameraInfo.zoomState.value?.zoomRatio?.toDouble(),
        )
    }

    private fun build(
        sources: Set<CaptureSource>,
        captureDeviceId: String?,
        logicalCameraId: String,
        physicalCameraId: String?,
        deviceManufacturer: String?,
        deviceModel: String?,
        activeArray: Rect?,
        sensorSize: SizeF?,
        sensorOrientation: Int?,
        availableFocalLengths: FloatArray?,
        captureResult: CaptureResult?,
        imageSize: PixelSize,
        encodedOrientation: ImageOrientation?,
        fallbackZoomRatio: Double?,
    ): CaptureMetadata {
        val issues = linkedSetOf<CaptureMetadataIssue>()
        if (captureResult == null) issues += CaptureMetadataIssue.CAMERA_CAPTURE_RESULT_UNAVAILABLE
        val deviceBinding = normalizedText(captureDeviceId)?.let {
            FieldEvidence.observed(it, EvidenceOrigin.CAPTURE_DEVICE_BINDING)
        } ?: FieldEvidence.missing(EvidenceOrigin.CAPTURE_DEVICE_BINDING)
        val logical = normalizedText(logicalCameraId)?.let {
            FieldEvidence.observed(it, EvidenceOrigin.CAPTURE_SESSION_BINDING)
        } ?: throw IllegalArgumentException("Logical camera id is empty or invalid")
        val physical = normalizedText(physicalCameraId)?.let {
            FieldEvidence.observed(it, EvidenceOrigin.CAPTURE_SESSION_BINDING)
        } ?: FieldEvidence.missing(EvidenceOrigin.CAPTURE_SESSION_BINDING)
        val cameraMake = normalizedText(deviceManufacturer)?.let {
            FieldEvidence.observed(it, EvidenceOrigin.ANDROID_DEVICE_BUILD)
        } ?: FieldEvidence.missing(EvidenceOrigin.ANDROID_DEVICE_BUILD)
        val cameraModel = normalizedText(deviceModel)?.let {
            FieldEvidence.observed(it, EvidenceOrigin.ANDROID_DEVICE_BUILD)
        } ?: FieldEvidence.missing(EvidenceOrigin.ANDROID_DEVICE_BUILD)
        val orientation = encodedOrientation?.let {
            FieldEvidence.observed(it, EvidenceOrigin.ENCODED_IMAGE_METADATA)
        } ?: FieldEvidence.missing(EvidenceOrigin.ENCODED_IMAGE_METADATA)

        val sensorEvidence = sensorSize?.let { size ->
            runCatching { SensorSizeMm(size.width.toDouble(), size.height.toDouble()) }
                .fold(
                    onSuccess = { FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CHARACTERISTICS) },
                    onFailure = {
                        issues += CaptureMetadataIssue.INVALID_CAMERA_METADATA_VALUE
                        FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS)
                    },
                )
        } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS)
        val sensorOrientationEvidence = sensorOrientation?.takeIf { it in 0..359 }?.let {
            FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CHARACTERISTICS)
        } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS)

        val resultFocal = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val focal = when {
            resultFocal != null -> FieldEvidence.observed(resultFocal, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            availableFocalLengths?.size == 1 -> {
                val candidate = availableFocalLengths[0].toDouble()
                if (candidate.isFinite() && candidate > 0.0) {
                    FieldEvidence.derived(candidate, setOf(EvidenceOrigin.CAMERA_CHARACTERISTICS), relativeUncertainty = 0.05)
                } else {
                    issues += CaptureMetadataIssue.INVALID_CAMERA_METADATA_VALUE
                    FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS)
                }
            }
            else -> FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT, EvidenceOrigin.CAMERA_CHARACTERISTICS)
        }

        val resultZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureResult?.get(CaptureResult.CONTROL_ZOOM_RATIO)?.toDouble()
        } else null
        val zoom = when {
            resultZoom != null && resultZoom.isFinite() && resultZoom > 0.0 ->
                FieldEvidence.observed(resultZoom, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            fallbackZoomRatio != null && fallbackZoomRatio.isFinite() && fallbackZoomRatio > 0.0 ->
                FieldEvidence.derived(fallbackZoomRatio, setOf(EvidenceOrigin.CAMERAX_RUNTIME_STATE), relativeUncertainty = 0.02)
            else -> FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT, EvidenceOrigin.CAMERAX_RUNTIME_STATE)
        }

        val resultCrop = captureResult?.get(CaptureResult.SCALER_CROP_REGION)
        val crop = if (resultCrop != null && activeArray != null) {
            runCatching { resultCrop.toSensorCrop(activeArray) }
                .fold(
                    onSuccess = { FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CAPTURE_RESULT) },
                    onFailure = {
                        issues += CaptureMetadataIssue.INVALID_CAMERA_METADATA_VALUE
                        FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT, EvidenceOrigin.CAMERA_CHARACTERISTICS)
                    },
                )
        } else {
            FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT, EvidenceOrigin.CAMERA_CHARACTERISTICS)
        }

        val focal35 = if (focal.value != null && sensorEvidence.value != null) {
            val equivalent = focal.value * FULL_FRAME_DIAGONAL_MM / sensorEvidence.value.diagonal
            FieldEvidence.derived(
                equivalent,
                focal.checkedOrigins + sensorEvidence.checkedOrigins + EvidenceOrigin.DERIVED_SENSOR_GEOMETRY,
                relativeUncertainty = maxOf(focal.relativeUncertainty ?: 0.0, sensorEvidence.relativeUncertainty ?: 0.0, 0.02),
            )
        } else {
            FieldEvidence.missing(EvidenceOrigin.DERIVED_SENSOR_GEOMETRY)
        }

        return CaptureMetadata(
            captureSources = sources,
            captureDeviceId = deviceBinding,
            imageSize = FieldEvidence.observed(imageSize, EvidenceOrigin.CALLER_IMAGE_DIMENSIONS),
            orientation = orientation,
            logicalCameraId = logical,
            physicalCameraId = physical,
            reportedCameraMake = cameraMake,
            reportedCameraModel = cameraModel,
            reportedLensMake = FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS),
            reportedLensModel = FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS),
            zoomRatio = zoom,
            cropRegion = crop,
            sensorSizeMm = sensorEvidence,
            sensorOrientationDegrees = sensorOrientationEvidence,
            focalLengthMm = focal,
            focal35mmEquivalent = focal35,
            originalMetadataRetained = true,
            issues = issues,
        )
    }

    private fun Rect.toSensorCrop(reference: Rect): SensorCrop = SensorCrop(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        referenceLeft = reference.left,
        referenceTop = reference.top,
        referenceRight = reference.right,
        referenceBottom = reference.bottom,
    )

    private const val FULL_FRAME_DIAGONAL_MM = 43.266615305567875
}

private fun normalizedText(value: String?): String? = value?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none { c -> c.code < 0x20 || c.code == 0x7f } }
