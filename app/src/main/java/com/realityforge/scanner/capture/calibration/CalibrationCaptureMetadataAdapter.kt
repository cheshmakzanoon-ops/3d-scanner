package com.realityforge.scanner.capture.calibration

import com.realityforge.scanner.capture.metadata.CaptureMetadata

/**
 * Uses the same 013 capture identities for calibration and scan compatibility. Device-local camera IDs are
 * namespaced; descriptive EXIF identities remain explicitly descriptive. Missing/conflicting evidence stays null.
 */
object CalibrationCaptureMetadataAdapter {
    fun optical(metadata: CaptureMetadata,
        source: CalibrationEvidenceSource = CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED): CalibrationOpticalMetadata {
        val device = metadata.captureDeviceId.value
        val camera = metadata.logicalCameraId.value
        val physical = metadata.physicalCameraId.value
        val cameraIdentity = if (device != null && camera != null) "camera:${sha256Utf8("$device|$camera")}" else {
            val make = metadata.reportedCameraMake.value
            val model = metadata.reportedCameraModel.value
            if (make != null && model != null) "reported:${sha256Utf8("$make|$model")}" else null
        }
        val lensIdentity = if (device != null && physical != null) "lens:${sha256Utf8("$device|$physical")}" else {
            metadata.reportedLensModel.value?.let { "reported:${sha256Utf8("${metadata.reportedLensMake.value ?: "unknown"}|$it")}" }
        }
        val cropIdentity = metadata.cropRegion.value?.let { crop ->
            sha256Utf8(listOf(crop.left, crop.top, crop.right, crop.bottom, crop.referenceLeft, crop.referenceTop,
                crop.referenceRight, crop.referenceBottom).joinToString(","))
        }
        return CalibrationOpticalMetadata(cameraIdentity, lensIdentity,
            metadata.zoomRatio.value?.takeIf { it in 0.1..100.0 }, cropIdentity,
            CalibrationOpticalMetadata.EXIF_UPRIGHT,
            metadata.focal35mmEquivalent.value?.takeIf { it in 1.0..1000.0 }, source)
    }

    fun forScan(metadata: CaptureMetadata, uprightWidth: Int, uprightHeight: Int) =
        CalibrationCaptureMetadata(uprightWidth, uprightHeight, optical(metadata))
}
