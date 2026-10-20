package com.realityforge.scanner.capture.metadata

import com.realityforge.scanner.capture.controls.AppliedOpticsMetadata
import com.realityforge.scanner.capture.controls.CameraCapabilities

/** Maps one observed still result. Missing still results stay missing; preview values are excluded. */
object CaptureOpticsMetadataAdapter {
    fun fromStill(
        deviceNamespace: String?,
        capabilities: CameraCapabilities,
        still: AppliedOpticsMetadata?,
    ): CaptureMetadata {
        require(still == null || still.camera.logicalCameraId == capabilities.camera.logicalCameraId)
        val base = CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE)
        val array = capabilities.activeArray
        val crop = if (still?.cropRegion != null && array != null) runCatching {
            val value = still.cropRegion
            SensorCrop(value.left, value.top, value.right, value.bottom,
                array.left, array.top, array.right, array.bottom)
        }.getOrNull() else null
        return base.copy(
            captureDeviceId = deviceNamespace?.let {
                FieldEvidence.observed(it, EvidenceOrigin.CAPTURE_DEVICE_BINDING)
            } ?: FieldEvidence.missing(EvidenceOrigin.CAPTURE_DEVICE_BINDING),
            logicalCameraId = FieldEvidence.observed(capabilities.camera.logicalCameraId,
                EvidenceOrigin.CAPTURE_SESSION_BINDING),
            physicalCameraId = still?.activePhysicalCameraId?.let {
                FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            zoomRatio = still?.zoomRatio?.toDouble()?.let {
                FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            focalLengthMm = still?.focalLengthMm?.toDouble()?.let {
                FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            cropRegion = crop?.let {
                FieldEvidence.observed(it, EvidenceOrigin.CAMERA_CAPTURE_RESULT)
            } ?: FieldEvidence.missing(EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            issues = if (still == null) setOf(CaptureMetadataIssue.CAMERA_CAPTURE_RESULT_UNAVAILABLE) else emptySet(),
        )
    }
}
