package com.realityforge.scanner.capture.calibration

import com.realityforge.scanner.capture.metadata.*
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.imaging.*
import org.junit.Assert.*
import org.junit.Test

/** Header geometry/compatibility only. Real decoder agreement has a separate Android fixture. */
class CalibrationInspectedScanTest {
    private fun inspection(orientation: ExifOrientation) = ImageInspection(
        ImageBounds(80,64,ImageFormat.JPEG,1000),orientation,OrientationEvidence.PRESENT,emptySet())
    private fun profile(width:Int,height:Int): CalibrationProfileRecord {
        val optical=CalibrationOpticalMetadata(focal35mm=26.0)
        return CalibrationProfileRecord(LensProfile(width,height,70.0,70.0,(width-1)/2.0,(height-1)/2.0,
            0.0,0.0,0.0,0.0,0.0,.1,8,26.0),"",
            CalibrationProfileProvenance(0,CalibrationEvidenceSource.SYNTHETIC,false,null,9,6,optical,false),
            CalibrationCoverageEvidence(8,null,null,null,false),emptyList())
    }
    @Test fun allEightFileOrientationsUseUprightDimensionsWithoutChangingEncodedBounds() {
        for(orientation in ExifOrientation.entries) {
            val inspected=inspection(orientation)
            val capture=CalibrationCaptureMetadataAdapter.forInspectedScan(null,inspected,26.0)
            val expected=if(orientation.exifValue in 5..8)64 to 80 else 80 to 64
            assertEquals(expected,capture.uprightWidth to capture.uprightHeight)
            assertEquals(80 to 64,inspected.source.width to inspected.source.height)
            val result=CalibrationProfileCompatibility.evaluate(profile(expected.first,expected.second),capture)
            assertEquals(CalibrationCompatibilityStatus.COMPATIBLE_WITH_UNVERIFIED_METADATA,result.status)
            assertFalse(CalibrationCompatibilityReason.ASPECT_OR_CROP_MISMATCH in result.reasons)
        }
    }
    @Test fun matchingPortraitAcceptedButWrongAspectStillRejected() {
        val capture=CalibrationCaptureMetadataAdapter.forInspectedScan(null,inspection(ExifOrientation.ROTATE_90_CW),26.0)
        assertFalse(CalibrationCompatibilityReason.ASPECT_OR_CROP_MISMATCH in
            CalibrationProfileCompatibility.evaluate(profile(64,80),capture).reasons)
        val wrong=CalibrationProfileCompatibility.evaluate(profile(80,64),capture)
        assertEquals(CalibrationCompatibilityStatus.INCOMPATIBLE,wrong.status)
        assertTrue(CalibrationCompatibilityReason.ASPECT_OR_CROP_MISMATCH in wrong.reasons)
    }
    @Test fun missingAndContradictoryMergedOrientationNeverOverridesFileGeometry() {
        val empty=CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA)
        val normal=empty.copy(orientation=FieldEvidence.observed(ImageOrientation.NORMAL,EvidenceOrigin.EXIF))
        val rotated=empty.copy(orientation=FieldEvidence.observed(ImageOrientation.ROTATE_90,EvidenceOrigin.ENCODED_IMAGE_METADATA))
        val conflict=CaptureMetadataMerger.merge(normal,rotated)
        assertEquals(EvidenceStatus.CONFLICTING,conflict.orientation.status)
        for(metadata in listOf(null,empty,normal,conflict)) {
            val capture=CalibrationCaptureMetadataAdapter.forInspectedScan(metadata,inspection(ExifOrientation.TRANSVERSE),26.0)
            assertEquals(64 to 80,capture.uprightWidth to capture.uprightHeight)
        }
        // Nor may a metadata rotation rotate a file that the decoder delivers unrotated.
        val plain=CalibrationCaptureMetadataAdapter.forInspectedScan(rotated,inspection(ExifOrientation.NORMAL),26.0)
        assertEquals(80 to 64,plain.uprightWidth to plain.uprightHeight)
    }
    @Test fun opticalEvidenceAndLegacyFallbackRemainUnchanged() {
        val inspected=inspection(ExifOrientation.ROTATE_270_CW)
        val legacy=CalibrationCaptureMetadataAdapter.forInspectedScan(null,inspected,35.0)
        assertEquals(35.0,legacy.optical.focal35mm!!,0.0)
        val metadata=CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE).copy(
            focal35mmEquivalent=FieldEvidence.observed(26.0,EvidenceOrigin.EXIF),
            logicalCameraId=FieldEvidence.observed("0",EvidenceOrigin.CAPTURE_SESSION_BINDING),
            captureDeviceId=FieldEvidence.observed("device",EvidenceOrigin.CAPTURE_DEVICE_BINDING))
        val actual=CalibrationCaptureMetadataAdapter.forInspectedScan(metadata,inspected,35.0)
        assertEquals(CalibrationCaptureMetadataAdapter.optical(metadata),actual.optical)
        assertEquals(26.0,actual.optical.focal35mm!!,0.0)
    }
}
