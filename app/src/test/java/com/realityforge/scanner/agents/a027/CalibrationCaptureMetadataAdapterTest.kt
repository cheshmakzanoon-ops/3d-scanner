package com.realityforge.scanner.agents.a027

import com.realityforge.scanner.capture.calibration.*
import com.realityforge.scanner.capture.metadata.*
import org.junit.Assert.*
import org.junit.Test

class CalibrationCaptureMetadataAdapterTest {
    @Test fun missingIdentityDoesNotBecomeAnAssumedSameLens() {
        val optical = CalibrationCaptureMetadataAdapter.optical(CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA))
        assertNull(optical.cameraId)
        assertNull(optical.lensId)
        assertNull(optical.zoomRatio)
        assertNull(optical.cropFingerprint)
        assertEquals(CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED, optical.evidenceSource)
    }

    @Test fun identicalNumericCameraIdsOnDifferentDevicesStayDifferent() {
        val base = CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE).copy(
            captureDeviceId = FieldEvidence.observed("installation-a", EvidenceOrigin.CAPTURE_DEVICE_BINDING),
            logicalCameraId = FieldEvidence.observed("0", EvidenceOrigin.CAPTURE_SESSION_BINDING),
            physicalCameraId = FieldEvidence.observed("0", EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            zoomRatio = FieldEvidence.observed(1.0, EvidenceOrigin.CAMERA_CAPTURE_RESULT),
            cropRegion = FieldEvidence.observed(SensorCrop(0, 0, 4000, 3000, 0, 0, 4000, 3000), EvidenceOrigin.CAMERA_CAPTURE_RESULT))
        val first = CalibrationCaptureMetadataAdapter.optical(base)
        val second = CalibrationCaptureMetadataAdapter.optical(base.copy(
            captureDeviceId = FieldEvidence.observed("installation-b", EvidenceOrigin.CAPTURE_DEVICE_BINDING)))
        assertNotEquals(first.cameraId, second.cameraId)
        assertNotEquals(first.lensId, second.lensId)
        assertEquals(first.cropFingerprint, second.cropFingerprint)
        val cropped = CalibrationCaptureMetadataAdapter.optical(base.copy(cropRegion = FieldEvidence.observed(
            SensorCrop(500, 375, 3500, 2625, 0, 0, 4000, 3000), EvidenceOrigin.CAMERA_CAPTURE_RESULT)))
        assertNotEquals(first.cropFingerprint, cropped.cropFingerprint)
        assertEquals(CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED, first.evidenceSource)
    }
}
