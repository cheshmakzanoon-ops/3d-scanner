package com.realityforge.scanner.capture.metadata

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Versioned origin of a capture-metadata observation. */
enum class EvidenceOrigin {
    CALLER_IMAGE_DIMENSIONS,
    ENCODED_IMAGE_METADATA,
    EXIF,
    ANDROID_DEVICE_BUILD,
    CAMERA_CHARACTERISTICS,
    CAMERA_CAPTURE_RESULT,
    CAMERAX_RUNTIME_STATE,
    CAPTURE_DEVICE_BINDING,
    CAPTURE_SESSION_BINDING,
    DERIVED_SENSOR_GEOMETRY,
}

enum class EvidenceStatus { MISSING, OBSERVED, DERIVED, CONFLICTING }

data class EvidenceObservation<T : Any>(
    val value: T,
    val origin: EvidenceOrigin,
)

/**
 * A value together with the evidence used to resolve it.
 *
 * Missing and conflicting fields intentionally have no resolved value. Observations are
 * retained so a later adapter can diagnose disagreement rather than silently selecting one.
 */
data class FieldEvidence<T : Any>(
    val status: EvidenceStatus,
    val value: T?,
    val observations: List<EvidenceObservation<T>>,
    val checkedOrigins: Set<EvidenceOrigin>,
    val relativeUncertainty: Double? = null,
) {
    init {
        require(observations.size <= 32) { "Too many metadata observations for one field" }
        require(checkedOrigins.size <= EvidenceOrigin.entries.size)
        require(checkedOrigins.containsAll(observations.map { it.origin })) {
            "Every observation origin must be included in checkedOrigins"
        }
        relativeUncertainty?.let {
            require(it.isFinite() && it in 0.0..1.0) { "Relative uncertainty must be finite and between 0 and 1" }
        }
        when (status) {
            EvidenceStatus.MISSING -> {
                require(value == null && observations.isEmpty() && relativeUncertainty == null)
            }
            EvidenceStatus.OBSERVED, EvidenceStatus.DERIVED -> {
                require(value != null && observations.isNotEmpty())
            }
            EvidenceStatus.CONFLICTING -> {
                require(value == null && observations.map { it.value }.distinct().size >= 2)
                require(relativeUncertainty == null)
            }
        }
    }

    val isKnown: Boolean get() = status == EvidenceStatus.OBSERVED || status == EvidenceStatus.DERIVED

    companion object {
        fun <T : Any> missing(vararg checked: EvidenceOrigin): FieldEvidence<T> = FieldEvidence(
            EvidenceStatus.MISSING,
            null,
            emptyList(),
            checked.toSet(),
        )

        fun <T : Any> observed(
            value: T,
            origin: EvidenceOrigin,
            relativeUncertainty: Double? = null,
        ): FieldEvidence<T> = FieldEvidence(
            EvidenceStatus.OBSERVED,
            value,
            listOf(EvidenceObservation(value, origin)),
            setOf(origin),
            relativeUncertainty,
        )

        fun <T : Any> derived(
            value: T,
            origins: Set<EvidenceOrigin>,
            relativeUncertainty: Double? = null,
        ): FieldEvidence<T> {
            require(origins.isNotEmpty())
            val observationOrigin = if (EvidenceOrigin.DERIVED_SENSOR_GEOMETRY in origins) {
                EvidenceOrigin.DERIVED_SENSOR_GEOMETRY
            } else {
                origins.minBy { it.ordinal }
            }
            return FieldEvidence(
                EvidenceStatus.DERIVED,
                value,
                listOf(EvidenceObservation(value, observationOrigin)),
                origins + observationOrigin,
                relativeUncertainty,
            )
        }

        fun <T : Any> conflicting(
            observations: List<EvidenceObservation<T>>,
            checkedOrigins: Set<EvidenceOrigin> = observations.mapTo(linkedSetOf()) { it.origin },
        ): FieldEvidence<T> = FieldEvidence(
            EvidenceStatus.CONFLICTING,
            null,
            observations,
            checkedOrigins + observations.map { it.origin },
        )
    }
}

enum class CaptureSource {
    CAMERA2_CAPTURE,
    CAMERAX_CAPTURE,
    IMPORTED_EXIF,
    IMPORTED_NO_METADATA,
}

enum class ImageOrientation(
    val exifValue: Int,
    val rotationDegreesClockwise: Int,
    val mirrored: Boolean,
    val swapsAxes: Boolean,
) {
    NORMAL(1, 0, false, false),
    FLIP_HORIZONTAL(2, 0, true, false),
    ROTATE_180(3, 180, false, false),
    FLIP_VERTICAL(4, 180, true, false),
    TRANSPOSE(5, 90, true, true),
    ROTATE_90(6, 90, false, true),
    TRANSVERSE(7, 270, true, true),
    ROTATE_270(8, 270, false, true);

    companion object {
        fun fromExifValue(value: Int): ImageOrientation? = entries.firstOrNull { it.exifValue == value }
    }
}

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width in 1..32_768 && height in 1..32_768) { "Image dimensions are outside supported metadata bounds" }
        require(width.toLong() * height <= 200_000_000L) { "Image metadata exceeds the 200-megapixel safety bound" }
    }

    fun upright(orientation: ImageOrientation): PixelSize =
        if (orientation.swapsAxes) PixelSize(height, width) else this

    val aspectRatio: Double get() = width.toDouble() / height
}

data class SensorSizeMm(val width: Double, val height: Double) {
    init {
        require(width.isFinite() && height.isFinite() && width in 0.1..100.0 && height in 0.1..100.0)
    }

    val diagonal: Double get() = hypot(width, height)
}

/** Exact crop coordinates and the active-array rectangle they are relative to. */
data class SensorCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val referenceLeft: Int,
    val referenceTop: Int,
    val referenceRight: Int,
    val referenceBottom: Int,
) {
    init {
        require(referenceRight > referenceLeft && referenceBottom > referenceTop)
        require(right > left && bottom > top)
        require(left >= referenceLeft && top >= referenceTop)
        require(right <= referenceRight && bottom <= referenceBottom)
        require((referenceRight - referenceLeft).toLong() * (referenceBottom - referenceTop) <= 1_000_000_000L)
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val referenceWidth: Int get() = referenceRight - referenceLeft
    val referenceHeight: Int get() = referenceBottom - referenceTop

    fun normalizedEdges(): DoubleArray = doubleArrayOf(
        (left - referenceLeft).toDouble() / referenceWidth,
        (top - referenceTop).toDouble() / referenceHeight,
        (right - referenceLeft).toDouble() / referenceWidth,
        (bottom - referenceTop).toDouble() / referenceHeight,
    )
}

enum class CaptureMetadataIssue {
    INVALID_EXIF_ORIENTATION,
    INVALID_EXIF_DIMENSIONS,
    INVALID_EXIF_NUMERIC_VALUE,
    CAMERA_CAPTURE_RESULT_UNAVAILABLE,
    INVALID_CAMERA_METADATA_VALUE,
    CONFLICTING_OBSERVATIONS,
}

/** Immutable, evidence-bearing capture metadata. Original photograph bytes are never owned here. */
data class CaptureMetadata(
    val schemaVersion: Int = SCHEMA_VERSION,
    val captureSources: Set<CaptureSource>,
    /** Opaque app-local installation/device namespace supplied by the capture owner, never a hardware serial. */
    val captureDeviceId: FieldEvidence<String>,
    val imageSize: FieldEvidence<PixelSize>,
    val orientation: FieldEvidence<ImageOrientation>,
    val logicalCameraId: FieldEvidence<String>,
    val physicalCameraId: FieldEvidence<String>,
    val reportedCameraMake: FieldEvidence<String>,
    val reportedCameraModel: FieldEvidence<String>,
    val reportedLensMake: FieldEvidence<String>,
    val reportedLensModel: FieldEvidence<String>,
    val zoomRatio: FieldEvidence<Double>,
    val cropRegion: FieldEvidence<SensorCrop>,
    val sensorSizeMm: FieldEvidence<SensorSizeMm>,
    val sensorOrientationDegrees: FieldEvidence<Int>,
    val focalLengthMm: FieldEvidence<Double>,
    val focal35mmEquivalent: FieldEvidence<Double>,
    val originalMetadataRetained: Boolean,
    val issues: Set<CaptureMetadataIssue> = emptySet(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported in-memory capture metadata schema" }
        require(captureSources.isNotEmpty())
        validateEvidence(captureDeviceId, ::validText)
        validateEvidence(imageSize) { true }
        validateEvidence(orientation) { true }
        listOf(logicalCameraId, physicalCameraId, reportedCameraMake, reportedCameraModel, reportedLensMake, reportedLensModel)
            .forEach { evidence -> validateEvidence(evidence, ::validText) }
        validateEvidence(zoomRatio) { it.isFinite() && it in 0.05..100.0 }
        validateEvidence(cropRegion) { true }
        validateEvidence(sensorSizeMm) { true }
        validateEvidence(sensorOrientationDegrees) { it in 0..359 }
        validateEvidence(focalLengthMm) { it.isFinite() && it in 0.1..2_000.0 }
        validateEvidence(focal35mmEquivalent) { it.isFinite() && it in 0.1..5_000.0 }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun empty(source: CaptureSource): CaptureMetadata = CaptureMetadata(
            captureSources = setOf(source),
            captureDeviceId = FieldEvidence.missing(),
            imageSize = FieldEvidence.missing(),
            orientation = FieldEvidence.missing(),
            logicalCameraId = FieldEvidence.missing(),
            physicalCameraId = FieldEvidence.missing(),
            reportedCameraMake = FieldEvidence.missing(),
            reportedCameraModel = FieldEvidence.missing(),
            reportedLensMake = FieldEvidence.missing(),
            reportedLensModel = FieldEvidence.missing(),
            zoomRatio = FieldEvidence.missing(),
            cropRegion = FieldEvidence.missing(),
            sensorSizeMm = FieldEvidence.missing(),
            sensorOrientationDegrees = FieldEvidence.missing(),
            focalLengthMm = FieldEvidence.missing(),
            focal35mmEquivalent = FieldEvidence.missing(),
            originalMetadataRetained = true,
        )

        private fun validText(value: String): Boolean =
            value.isNotBlank() && value == value.trim() && value.length <= 256 && value.none { it.code < 0x20 || it.code == 0x7f }

        private fun <T : Any> validateEvidence(evidence: FieldEvidence<T>, validator: (T) -> Boolean) {
            evidence.value?.let { require(validator(it)) { "Invalid resolved capture metadata value" } }
            evidence.observations.forEach { require(validator(it.value)) { "Invalid capture metadata observation" } }
        }
    }
}

/** Combines independent EXIF/camera observations without hiding disagreements. */
object CaptureMetadataMerger {
    fun merge(left: CaptureMetadata, right: CaptureMetadata): CaptureMetadata {
        val captureDeviceId = mergeField(left.captureDeviceId, right.captureDeviceId, String::equals, { it })
        val imageSize = mergeField(left.imageSize, right.imageSize, { a, b -> a == b }, { it.toString() })
        val orientation = mergeField(left.orientation, right.orientation, { a, b -> a == b }, { it.name })
        val logicalCameraId = mergeField(left.logicalCameraId, right.logicalCameraId, String::equals, { it })
        val physicalCameraId = mergeField(left.physicalCameraId, right.physicalCameraId, String::equals, { it })
        val cameraMake = mergeField(left.reportedCameraMake, right.reportedCameraMake, ::sameReportedText, { it.lowercase() })
        val cameraModel = mergeField(left.reportedCameraModel, right.reportedCameraModel, ::sameReportedText, { it.lowercase() })
        val lensMake = mergeField(left.reportedLensMake, right.reportedLensMake, ::sameReportedText, { it.lowercase() })
        val lensModel = mergeField(left.reportedLensModel, right.reportedLensModel, ::sameReportedText, { it.lowercase() })
        val zoom = mergeNumeric(left.zoomRatio, right.zoomRatio, 0.005)
        val crop = mergeField(left.cropRegion, right.cropRegion, { a, b -> cropDistance(a, b) <= 0.001 }, { it.toString() })
        val sensorSize = mergeField(left.sensorSizeMm, right.sensorSizeMm, { a, b ->
            relativeDifference(a.width, b.width) <= 0.01 && relativeDifference(a.height, b.height) <= 0.01
        }, { "${it.width},${it.height}" })
        val sensorOrientation = mergeField(left.sensorOrientationDegrees, right.sensorOrientationDegrees, { a, b -> a == b }, { it.toString() })
        val focal = mergeNumeric(left.focalLengthMm, right.focalLengthMm, 0.02)
        val focal35 = mergeNumeric(left.focal35mmEquivalent, right.focal35mmEquivalent, 0.03)
        val mergedFields = listOf(
            captureDeviceId, imageSize, orientation, logicalCameraId, physicalCameraId, cameraMake, cameraModel,
            lensMake, lensModel, zoom, crop, sensorSize, sensorOrientation, focal, focal35,
        )
        val hasConflict = mergedFields.any { it.status == EvidenceStatus.CONFLICTING }
        return CaptureMetadata(
            captureSources = left.captureSources + right.captureSources,
            captureDeviceId = captureDeviceId,
            imageSize = imageSize,
            orientation = orientation,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            reportedCameraMake = cameraMake,
            reportedCameraModel = cameraModel,
            reportedLensMake = lensMake,
            reportedLensModel = lensModel,
            zoomRatio = zoom,
            cropRegion = crop,
            sensorSizeMm = sensorSize,
            sensorOrientationDegrees = sensorOrientation,
            focalLengthMm = focal,
            focal35mmEquivalent = focal35,
            originalMetadataRetained = left.originalMetadataRetained && right.originalMetadataRetained,
            issues = left.issues + right.issues + if (hasConflict) setOf(CaptureMetadataIssue.CONFLICTING_OBSERVATIONS) else emptySet(),
        )
    }

    private fun sameReportedText(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    private fun mergeNumeric(
        left: FieldEvidence<Double>,
        right: FieldEvidence<Double>,
        tolerance: Double,
    ): FieldEvidence<Double> = mergeField(
        left,
        right,
        { a, b -> relativeDifference(a, b) <= tolerance },
        { it.toString() },
        spread = { a, b -> relativeDifference(a, b) },
    )

    private fun <T : Any> mergeField(
        left: FieldEvidence<T>,
        right: FieldEvidence<T>,
        equivalent: (T, T) -> Boolean,
        valueKey: (T) -> String,
        spread: (T, T) -> Double = { _, _ -> 0.0 },
    ): FieldEvidence<T> {
        val observations = (left.observations + right.observations)
            .distinctBy { it.origin to valueKey(it.value) }
            .sortedWith(compareBy<EvidenceObservation<T>>({ it.origin.ordinal }, { valueKey(it.value) }))
        val checked = left.checkedOrigins + right.checkedOrigins + observations.map { it.origin }
        if (observations.isEmpty()) return FieldEvidence.missing(*checked.toTypedArray())
        val first = observations.first().value
        if (observations.any { !equivalent(first, it.value) }) {
            return FieldEvidence.conflicting(observations, checked)
        }
        val selected = observations.minWith(compareBy<EvidenceObservation<T>>({ originRank(it.origin) }, { valueKey(it.value) }))
        val uncertainty = listOfNotNull(
            left.relativeUncertainty,
            right.relativeUncertainty,
            observations.maxOfOrNull { spread(selected.value, it.value) },
        ).maxOrNull()?.takeIf { it > 0.0 }
        val status = if (
            selected.origin == EvidenceOrigin.DERIVED_SENSOR_GEOMETRY ||
            (left.status == EvidenceStatus.DERIVED && right.status in setOf(EvidenceStatus.DERIVED, EvidenceStatus.MISSING)) ||
            (right.status == EvidenceStatus.DERIVED && left.status == EvidenceStatus.MISSING)
        ) EvidenceStatus.DERIVED else EvidenceStatus.OBSERVED
        return FieldEvidence(status, selected.value, observations, checked, uncertainty)
    }

    private fun originRank(origin: EvidenceOrigin): Int = when (origin) {
        EvidenceOrigin.CAMERA_CAPTURE_RESULT -> 0
        EvidenceOrigin.CAPTURE_DEVICE_BINDING -> 1
        EvidenceOrigin.CAPTURE_SESSION_BINDING -> 2
        EvidenceOrigin.EXIF -> 3
        EvidenceOrigin.ENCODED_IMAGE_METADATA -> 4
        EvidenceOrigin.CAMERAX_RUNTIME_STATE -> 5
        EvidenceOrigin.CAMERA_CHARACTERISTICS -> 6
        EvidenceOrigin.ANDROID_DEVICE_BUILD -> 7
        EvidenceOrigin.CALLER_IMAGE_DIMENSIONS -> 8
        EvidenceOrigin.DERIVED_SENSOR_GEOMETRY -> 9
    }

    internal fun relativeDifference(a: Double, b: Double): Double = abs(a - b) / max(max(abs(a), abs(b)), 1e-12)

    internal fun cropDistance(a: SensorCrop, b: SensorCrop): Double {
        val x = a.normalizedEdges()
        val y = b.normalizedEdges()
        return x.indices.maxOf { abs(x[it] - y[it]) }
    }
}
