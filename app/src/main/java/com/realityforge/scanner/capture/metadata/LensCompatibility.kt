package com.realityforge.scanner.capture.metadata

import kotlin.math.abs
import kotlin.math.max

enum class LensCompatibilityLevel {
    COMPATIBLE,
    COMPATIBLE_WITH_UNCERTAINTY,
    INSUFFICIENT_EVIDENCE,
    INCOMPATIBLE,
}

enum class LensCompatibilityReason {
    CONFLICTING_OPTICAL_EVIDENCE,
    CAPTURE_DEVICE_MISMATCH,
    CAMERA_DEVICE_MODEL_MISMATCH,
    PHYSICAL_LENS_MISMATCH,
    LOGICAL_CAMERA_MISMATCH,
    REPORTED_LENS_MISMATCH,
    ZOOM_MISMATCH,
    CROP_MISMATCH,
    ORIENTATION_MISMATCH,
    IMAGE_ASPECT_MISMATCH,
    SENSOR_ORIENTATION_MISMATCH,
    FOCAL_LENGTH_MISMATCH,
    FOCAL_35MM_MISMATCH,
    SENSOR_SIZE_MISMATCH,
    CAPTURE_DEVICE_ID_UNAVAILABLE,
    CAMERA_DEVICE_IDENTITY_UNAVAILABLE,
    PHYSICAL_LENS_ID_UNAVAILABLE,
    CAMERA_IDENTITY_UNAVAILABLE,
    REPORTED_LENS_ID_UNAVAILABLE,
    IMAGE_SIZE_UNAVAILABLE,
    ORIENTATION_UNAVAILABLE,
    ZOOM_UNAVAILABLE,
    CROP_UNAVAILABLE,
    FOCAL_LENGTH_UNAVAILABLE,
    DERIVED_OPTICAL_EVIDENCE,
}

data class LensCompatibilityPolicy(
    val zoomRelativeTolerance: Double = 0.01,
    val focalRelativeTolerance: Double = 0.025,
    val focal35RelativeTolerance: Double = 0.03,
    val sensorSizeRelativeTolerance: Double = 0.01,
    val cropNormalizedTolerance: Double = 0.002,
    val uprightAspectRelativeTolerance: Double = 0.005,
) {
    init {
        listOf(
            zoomRelativeTolerance,
            focalRelativeTolerance,
            focal35RelativeTolerance,
            sensorSizeRelativeTolerance,
            cropNormalizedTolerance,
            uprightAspectRelativeTolerance,
        ).forEach { require(it.isFinite() && it in 0.0..0.25) }
    }
}

data class LensCompatibilityResult(
    val level: LensCompatibilityLevel,
    val reasons: List<LensCompatibilityReason>,
    /** Strict profile reuse is allowed only with direct, complete, matching optical evidence. */
    val mayShareCalibrationProfile: Boolean,
    /** Provisional grouping is allowed only when there is no contradictory observation. */
    val mayGroupAsSameOptics: Boolean,
    val requiresIndependentIntrinsics: Boolean,
) {
    init {
        require(reasons == reasons.distinct().sortedBy { it.ordinal }) { "Compatibility reasons must be deterministic" }
        require(!mayShareCalibrationProfile || level == LensCompatibilityLevel.COMPATIBLE)
        require(!mayShareCalibrationProfile || mayGroupAsSameOptics)
        require(!mayGroupAsSameOptics || level in setOf(
            LensCompatibilityLevel.COMPATIBLE,
            LensCompatibilityLevel.COMPATIBLE_WITH_UNCERTAINTY,
        ))
    }
}

/**
 * Conservative same-optics decision.
 *
 * Camera2 logical/physical IDs are interpreted only inside the opaque capture-device
 * namespace supplied by the capture owner. Equal local camera IDs without that namespace
 * can support an uncertain grouping, but can never authorize automatic calibration reuse.
 */
object LensCompatibilityAssessor {
    fun assess(
        reference: CaptureMetadata,
        candidate: CaptureMetadata,
        policy: LensCompatibilityPolicy = LensCompatibilityPolicy(),
    ): LensCompatibilityResult {
        val reasons = linkedSetOf<LensCompatibilityReason>()
        var hardMismatch = false

        val criticalEvidence = listOf(
            reference.captureDeviceId, candidate.captureDeviceId,
            reference.imageSize, candidate.imageSize,
            reference.orientation, candidate.orientation,
            reference.logicalCameraId, candidate.logicalCameraId,
            reference.physicalCameraId, candidate.physicalCameraId,
            reference.reportedCameraMake, candidate.reportedCameraMake,
            reference.reportedCameraModel, candidate.reportedCameraModel,
            reference.reportedLensModel, candidate.reportedLensModel,
            reference.zoomRatio, candidate.zoomRatio,
            reference.cropRegion, candidate.cropRegion,
            reference.sensorSizeMm, candidate.sensorSizeMm,
            reference.sensorOrientationDegrees, candidate.sensorOrientationDegrees,
            reference.focalLengthMm, candidate.focalLengthMm,
            reference.focal35mmEquivalent, candidate.focal35mmEquivalent,
        )
        if (criticalEvidence.any { it.status == EvidenceStatus.CONFLICTING }) {
            reasons += LensCompatibilityReason.CONFLICTING_OPTICAL_EVIDENCE
            hardMismatch = true
        }

        val captureDeviceA = reference.captureDeviceId.value
        val captureDeviceB = candidate.captureDeviceId.value
        val sameCaptureDevice = captureDeviceA != null && captureDeviceB != null && captureDeviceA == captureDeviceB
        if (captureDeviceA != null && captureDeviceB != null && captureDeviceA != captureDeviceB) {
            reasons += LensCompatibilityReason.CAPTURE_DEVICE_MISMATCH
            hardMismatch = true
        } else if (captureDeviceA == null || captureDeviceB == null) {
            reasons += LensCompatibilityReason.CAPTURE_DEVICE_ID_UNAVAILABLE
        }

        val cameraDeviceA = normalizedCameraDevice(reference)
        val cameraDeviceB = normalizedCameraDevice(candidate)
        val cameraDeviceMatch = cameraDeviceA != null && cameraDeviceB != null && cameraDeviceA == cameraDeviceB
        if (cameraDeviceA != null && cameraDeviceB != null && cameraDeviceA != cameraDeviceB) {
            reasons += LensCompatibilityReason.CAMERA_DEVICE_MODEL_MISMATCH
            hardMismatch = true
        } else if (cameraDeviceA == null || cameraDeviceB == null) {
            reasons += LensCompatibilityReason.CAMERA_DEVICE_IDENTITY_UNAVAILABLE
        }

        val physicalA = reference.physicalCameraId.value
        val physicalB = candidate.physicalCameraId.value
        val physicalMatch = physicalA != null && physicalB != null && physicalA == physicalB
        if (physicalA != null && physicalB != null && physicalA != physicalB) {
            reasons += LensCompatibilityReason.PHYSICAL_LENS_MISMATCH
            hardMismatch = true
        } else if (physicalA == null || physicalB == null) {
            reasons += LensCompatibilityReason.PHYSICAL_LENS_ID_UNAVAILABLE
        }

        val logicalA = reference.logicalCameraId.value
        val logicalB = candidate.logicalCameraId.value
        val logicalMatch = logicalA != null && logicalB != null && logicalA == logicalB
        if (!physicalMatch && logicalA != null && logicalB != null && logicalA != logicalB) {
            reasons += LensCompatibilityReason.LOGICAL_CAMERA_MISMATCH
            hardMismatch = true
        }

        val reportedLensA = normalizedLensName(reference)
        val reportedLensB = normalizedLensName(candidate)
        val reportedLensMatch = reportedLensA != null && reportedLensB != null && reportedLensA == reportedLensB
        if (!physicalMatch && reportedLensA != null && reportedLensB != null && reportedLensA != reportedLensB) {
            reasons += LensCompatibilityReason.REPORTED_LENS_MISMATCH
            hardMismatch = true
        } else if (!physicalMatch && (reportedLensA == null || reportedLensB == null)) {
            reasons += LensCompatibilityReason.REPORTED_LENS_ID_UNAVAILABLE
        }

        if (!physicalMatch && (logicalA == null || logicalB == null) && (reportedLensA == null || reportedLensB == null)) {
            reasons += LensCompatibilityReason.CAMERA_IDENTITY_UNAVAILABLE
        }

        hardMismatch = compareKnown(
            reference.zoomRatio,
            candidate.zoomRatio,
            LensCompatibilityReason.ZOOM_MISMATCH,
            reasons,
            { a, b -> relativeDifference(a, b) <= policy.zoomRelativeTolerance },
        ) || hardMismatch
        if (reference.zoomRatio.value == null || candidate.zoomRatio.value == null) reasons += LensCompatibilityReason.ZOOM_UNAVAILABLE

        hardMismatch = compareKnown(
            reference.cropRegion,
            candidate.cropRegion,
            LensCompatibilityReason.CROP_MISMATCH,
            reasons,
            { a, b -> CaptureMetadataMerger.cropDistance(a, b) <= policy.cropNormalizedTolerance },
        ) || hardMismatch
        if (reference.cropRegion.value == null || candidate.cropRegion.value == null) reasons += LensCompatibilityReason.CROP_UNAVAILABLE

        hardMismatch = compareKnown(
            reference.orientation,
            candidate.orientation,
            LensCompatibilityReason.ORIENTATION_MISMATCH,
            reasons,
            { a, b -> a == b },
        ) || hardMismatch
        if (reference.orientation.value == null || candidate.orientation.value == null) reasons += LensCompatibilityReason.ORIENTATION_UNAVAILABLE

        val uprightA = uprightSize(reference)
        val uprightB = uprightSize(candidate)
        if (uprightA == null || uprightB == null) {
            reasons += LensCompatibilityReason.IMAGE_SIZE_UNAVAILABLE
        } else if (relativeDifference(uprightA.aspectRatio, uprightB.aspectRatio) > policy.uprightAspectRelativeTolerance) {
            reasons += LensCompatibilityReason.IMAGE_ASPECT_MISMATCH
            hardMismatch = true
        }

        hardMismatch = compareKnown(
            reference.sensorOrientationDegrees,
            candidate.sensorOrientationDegrees,
            LensCompatibilityReason.SENSOR_ORIENTATION_MISMATCH,
            reasons,
            { a, b -> a == b },
        ) || hardMismatch

        hardMismatch = compareKnown(
            reference.focalLengthMm,
            candidate.focalLengthMm,
            LensCompatibilityReason.FOCAL_LENGTH_MISMATCH,
            reasons,
            { a, b -> relativeDifference(a, b) <= policy.focalRelativeTolerance },
        ) || hardMismatch
        hardMismatch = compareKnown(
            reference.focal35mmEquivalent,
            candidate.focal35mmEquivalent,
            LensCompatibilityReason.FOCAL_35MM_MISMATCH,
            reasons,
            { a, b -> relativeDifference(a, b) <= policy.focal35RelativeTolerance },
        ) || hardMismatch
        if (
            (reference.focalLengthMm.value == null || candidate.focalLengthMm.value == null) &&
            (reference.focal35mmEquivalent.value == null || candidate.focal35mmEquivalent.value == null)
        ) reasons += LensCompatibilityReason.FOCAL_LENGTH_UNAVAILABLE

        hardMismatch = compareKnown(
            reference.sensorSizeMm,
            candidate.sensorSizeMm,
            LensCompatibilityReason.SENSOR_SIZE_MISMATCH,
            reasons,
            { a, b ->
                relativeDifference(a.width, b.width) <= policy.sensorSizeRelativeTolerance &&
                    relativeDifference(a.height, b.height) <= policy.sensorSizeRelativeTolerance
            },
        ) || hardMismatch

        val focalEvidence = if (reference.focalLengthMm.value != null && candidate.focalLengthMm.value != null) {
            listOf(reference.focalLengthMm, candidate.focalLengthMm)
        } else {
            listOf(reference.focal35mmEquivalent, candidate.focal35mmEquivalent)
        }
        val relevantEvidence = listOf(
            reference.captureDeviceId, candidate.captureDeviceId,
            reference.imageSize, candidate.imageSize,
            reference.orientation, candidate.orientation,
            reference.zoomRatio, candidate.zoomRatio,
            reference.cropRegion, candidate.cropRegion,
        ) + focalEvidence
        val hasDerived = relevantEvidence.any { it.status == EvidenceStatus.DERIVED }
        if (hasDerived) reasons += LensCompatibilityReason.DERIVED_OPTICAL_EVIDENCE

        val knownOpticalPairs = listOf(
            reference.imageSize.value != null && candidate.imageSize.value != null,
            reference.orientation.value != null && candidate.orientation.value != null,
            reference.zoomRatio.value != null && candidate.zoomRatio.value != null,
            reference.cropRegion.value != null && candidate.cropRegion.value != null,
            (reference.focalLengthMm.value != null && candidate.focalLengthMm.value != null) ||
                (reference.focal35mmEquivalent.value != null && candidate.focal35mmEquivalent.value != null),
        ).count { it }
        val weakIdentityMatch = logicalMatch || reportedLensMatch || (cameraDeviceMatch && physicalMatch)
        val directComplete = sameCaptureDevice && physicalMatch && knownOpticalPairs == 5 && !hasDerived

        val level = when {
            hardMismatch -> LensCompatibilityLevel.INCOMPATIBLE
            directComplete -> LensCompatibilityLevel.COMPATIBLE
            (sameCaptureDevice && (physicalMatch || weakIdentityMatch) && knownOpticalPairs >= 3) ||
                (cameraDeviceMatch && physicalMatch && knownOpticalPairs >= 4) ||
                (weakIdentityMatch && knownOpticalPairs >= 4) -> LensCompatibilityLevel.COMPATIBLE_WITH_UNCERTAINTY
            else -> LensCompatibilityLevel.INSUFFICIENT_EVIDENCE
        }
        return LensCompatibilityResult(
            level = level,
            reasons = reasons.distinct().sortedBy { it.ordinal },
            mayShareCalibrationProfile = level == LensCompatibilityLevel.COMPATIBLE,
            mayGroupAsSameOptics = level in setOf(
                LensCompatibilityLevel.COMPATIBLE,
                LensCompatibilityLevel.COMPATIBLE_WITH_UNCERTAINTY,
            ),
            requiresIndependentIntrinsics = level != LensCompatibilityLevel.COMPATIBLE,
        )
    }

    private fun normalizedCameraDevice(metadata: CaptureMetadata): String? {
        val make = metadata.reportedCameraMake.value?.trim()?.lowercase()
        val model = metadata.reportedCameraModel.value?.trim()?.lowercase()
        return if (make != null && model != null) "$make|$model" else null
    }

    private fun normalizedLensName(metadata: CaptureMetadata): String? {
        val make = metadata.reportedLensMake.value?.trim()?.lowercase()
        val model = metadata.reportedLensModel.value?.trim()?.lowercase()
        return when {
            make != null && model != null -> "$make|$model"
            model != null -> model
            else -> null
        }
    }

    private fun uprightSize(metadata: CaptureMetadata): PixelSize? {
        val size = metadata.imageSize.value ?: return null
        val orientation = metadata.orientation.value ?: return null
        return size.upright(orientation)
    }

    private fun <T : Any> compareKnown(
        left: FieldEvidence<T>,
        right: FieldEvidence<T>,
        mismatch: LensCompatibilityReason,
        reasons: MutableSet<LensCompatibilityReason>,
        compatible: (T, T) -> Boolean,
    ): Boolean {
        val a = left.value ?: return false
        val b = right.value ?: return false
        if (compatible(a, b)) return false
        reasons += mismatch
        return true
    }

    private fun relativeDifference(a: Double, b: Double): Double = abs(a - b) / max(max(abs(a), abs(b)), 1e-12)
}
