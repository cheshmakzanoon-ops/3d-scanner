package com.realityforge.scanner.agents.a013

import com.realityforge.scanner.capture.metadata.*
import org.junit.Assert.*
import org.junit.Test

class CaptureMetadataIdentityTest {
    @Test fun sameDevicePhysicalLensAndOpticsAreStrictlyCompatible() {
        val reference = fixture()
        val result = LensCompatibilityAssessor.assess(reference, fixture())
        assertEquals(LensCompatibilityLevel.COMPATIBLE, result.level)
        assertTrue(result.reasons.isEmpty())
        assertTrue(result.mayShareCalibrationProfile)
        assertFalse(result.requiresIndependentIntrinsics)
    }

    @Test fun physicalLensMismatchIsRejectedWithoutRelyingOnReportedNames() {
        val reference = fixture().copy(
            reportedLensMake = FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS),
            reportedLensModel = FieldEvidence.missing(EvidenceOrigin.CAMERA_CHARACTERISTICS),
        )
        val same = LensCompatibilityAssessor.assess(reference, reference)
        assertEquals(LensCompatibilityLevel.COMPATIBLE, same.level)
        assertTrue(same.reasons.isEmpty())
        val other = reference.copy(
            physicalCameraId = FieldEvidence.observed("0-tele", EvidenceOrigin.CAPTURE_SESSION_BINDING),
        )
        val mismatch = LensCompatibilityAssessor.assess(reference, other)
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, mismatch.level)
        assertTrue(LensCompatibilityReason.PHYSICAL_LENS_MISMATCH in mismatch.reasons)
    }

    @Test fun equalCamera2IdsOnDifferentCaptureDevicesAreRejected() {
        val reference = fixture(captureDeviceId = "install-a")
        val candidate = fixture(captureDeviceId = "install-b")
        val result = LensCompatibilityAssessor.assess(reference, candidate)
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, result.level)
        assertTrue(LensCompatibilityReason.CAPTURE_DEVICE_MISMATCH in result.reasons)
        assertFalse(result.mayShareCalibrationProfile)
        assertFalse(result.mayGroupAsSameOptics)
    }

    @Test fun missingCaptureDeviceNamespaceCannotAuthorizeStrictCalibrationReuse() {
        val reference = fixture().copy(
            captureDeviceId = FieldEvidence.missing(EvidenceOrigin.CAPTURE_DEVICE_BINDING),
        )
        val result = LensCompatibilityAssessor.assess(reference, reference)
        assertEquals(LensCompatibilityLevel.COMPATIBLE_WITH_UNCERTAINTY, result.level)
        assertTrue(LensCompatibilityReason.CAPTURE_DEVICE_ID_UNAVAILABLE in result.reasons)
        assertFalse(result.mayShareCalibrationProfile)
        assertTrue(result.mayGroupAsSameOptics)
        assertTrue(result.requiresIndependentIntrinsics)
    }

    @Test fun cameraDeviceModelMismatchIsRejectedEvenWhenLocalIdsMatch() {
        val reference = fixture().copy(
            captureDeviceId = FieldEvidence.missing(EvidenceOrigin.CAPTURE_DEVICE_BINDING),
        )
        val candidate = reference.copy(
            reportedCameraModel = FieldEvidence.observed("Other Phone", EvidenceOrigin.ANDROID_DEVICE_BUILD),
        )
        val result = LensCompatibilityAssessor.assess(reference, candidate)
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, result.level)
        assertTrue(LensCompatibilityReason.CAMERA_DEVICE_MODEL_MISMATCH in result.reasons)
    }

    @Test fun changedZoomCropAndOrientationHaveDeterministicReasons() {
        val reference = fixture()
        val changedZoom = LensCompatibilityAssessor.assess(reference, fixture(zoom = 1.25))
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, changedZoom.level)
        assertTrue(LensCompatibilityReason.ZOOM_MISMATCH in changedZoom.reasons)

        val changedCrop = LensCompatibilityAssessor.assess(reference, fixture(crop = SensorCrop(80, 40, 3920, 2960, 0, 0, 4000, 3000)))
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, changedCrop.level)
        assertTrue(LensCompatibilityReason.CROP_MISMATCH in changedCrop.reasons)

        val changedOrientation = LensCompatibilityAssessor.assess(reference, fixture(orientation = ImageOrientation.ROTATE_90))
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, changedOrientation.level)
        assertTrue(LensCompatibilityReason.ORIENTATION_MISMATCH in changedOrientation.reasons)
        assertEquals(changedOrientation.reasons.sortedBy { it.ordinal }, changedOrientation.reasons)
    }

    @Test fun absentIdentityIsNotPromotedToSamePhysicalLens() {
        val unknown = CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA).copy(
            imageSize = FieldEvidence.observed(PixelSize(4000, 3000), EvidenceOrigin.CALLER_IMAGE_DIMENSIONS),
            originalMetadataRetained = true,
        )
        val result = LensCompatibilityAssessor.assess(unknown, unknown)
        assertEquals(LensCompatibilityLevel.INSUFFICIENT_EVIDENCE, result.level)
        assertFalse(result.mayShareCalibrationProfile)
        assertFalse(result.mayGroupAsSameOptics)
        assertTrue(LensCompatibilityReason.CAPTURE_DEVICE_ID_UNAVAILABLE in result.reasons)
        assertTrue(LensCompatibilityReason.PHYSICAL_LENS_ID_UNAVAILABLE in result.reasons)
        assertTrue(LensCompatibilityReason.CAMERA_IDENTITY_UNAVAILABLE in result.reasons)
        assertTrue(LensCompatibilityReason.ORIENTATION_UNAVAILABLE in result.reasons)
    }

    @Test fun conflictingObservationsAreRetainedAndRejected() {
        val camera = fixture()
        val exif = fixture().copy(
            captureSources = setOf(CaptureSource.IMPORTED_EXIF),
            captureDeviceId = FieldEvidence.missing(EvidenceOrigin.EXIF),
            physicalCameraId = FieldEvidence.missing(EvidenceOrigin.EXIF),
            focalLengthMm = FieldEvidence.observed(8.0, EvidenceOrigin.EXIF),
        )
        val merged = CaptureMetadataMerger.merge(camera, exif)
        assertEquals(EvidenceStatus.CONFLICTING, merged.focalLengthMm.status)
        assertEquals(setOf(4.25, 8.0), merged.focalLengthMm.observations.map { it.value }.toSet())
        assertTrue(CaptureMetadataIssue.CONFLICTING_OBSERVATIONS in merged.issues)
        val result = LensCompatibilityAssessor.assess(camera, merged)
        assertEquals(LensCompatibilityLevel.INCOMPATIBLE, result.level)
        assertTrue(LensCompatibilityReason.CONFLICTING_OPTICAL_EVIDENCE in result.reasons)
    }

    @Test fun upstreamConflictCannotBeErasedByNumericMergeTolerance() {
        val conflict = fixture().copy(
            focalLengthMm = FieldEvidence.conflicting(
                listOf(
                    EvidenceObservation(4.25, EvidenceOrigin.CAMERA_CAPTURE_RESULT),
                    EvidenceObservation(4.26, EvidenceOrigin.EXIF),
                ),
            ),
        )
        val merged = CaptureMetadataMerger.merge(conflict, fixture())
        assertEquals(EvidenceStatus.CONFLICTING, merged.focalLengthMm.status)
        assertEquals(setOf(4.25, 4.26), merged.focalLengthMm.observations.map { it.value }.toSet())
        assertTrue(CaptureMetadataIssue.CONFLICTING_OBSERVATIONS in merged.issues)
    }

    @Test fun canonicalCodecRoundTripsWithoutLoss() {
        val original = CaptureMetadataMerger.merge(
            fixture(),
            fixture().copy(
                captureSources = setOf(CaptureSource.IMPORTED_EXIF),
                captureDeviceId = FieldEvidence.missing(EvidenceOrigin.EXIF),
                physicalCameraId = FieldEvidence.missing(EvidenceOrigin.EXIF),
                focalLengthMm = FieldEvidence.observed(4.26, EvidenceOrigin.EXIF),
            ),
        )
        val encoded = CaptureMetadataCodec.encode(original)
        val decoded = CaptureMetadataCodec.decode(encoded)
        assertEquals(original, decoded)
        assertArrayEquals(encoded, CaptureMetadataCodec.encode(decoded))
        assertTrue(encoded.size < 64 * 1024)
    }

    @Test fun opticalIdentityChangesOnlyForRelevantMetadata() {
        val original = fixture()
        val identity = CaptureMetadataCodec.opticalIdentitySha256(original)
        assertNotEquals(identity, CaptureMetadataCodec.opticalIdentitySha256(fixture(captureDeviceId = "other-device")))
        assertNotEquals(identity, CaptureMetadataCodec.opticalIdentitySha256(fixture(zoom = 1.1)))
        assertNotEquals(identity, CaptureMetadataCodec.opticalIdentitySha256(fixture(orientation = ImageOrientation.ROTATE_90)))
        assertNotEquals(identity, CaptureMetadataCodec.opticalIdentitySha256(
            fixture(crop = SensorCrop(10, 0, 3990, 3000, 0, 0, 4000, 3000)),
        ))
        assertEquals(identity, CaptureMetadataCodec.opticalIdentitySha256(original.copy(originalMetadataRetained = false)))
    }

    @Test fun unknownVersionAndNonCanonicalPayloadsAreRejected() {
        val valid = CaptureMetadataCodec.encode(fixture()).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            CaptureMetadataCodec.decode(valid.replace("schema=1", "schema=2").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureMetadataCodec.decode(valid.replace("zoomRatio.uncertainty=-", "zoomRatio.uncertainty=0.00").toByteArray())
        }
    }

    private fun fixture(
        captureDeviceId: String = "installation-7f45",
        zoom: Double = 1.0,
        crop: SensorCrop = SensorCrop(0, 0, 4000, 3000, 0, 0, 4000, 3000),
        orientation: ImageOrientation = ImageOrientation.NORMAL,
    ): CaptureMetadata = CaptureMetadata(
        captureSources = setOf(CaptureSource.CAMERA2_CAPTURE),
        captureDeviceId = FieldEvidence.observed(captureDeviceId, EvidenceOrigin.CAPTURE_DEVICE_BINDING),
        imageSize = FieldEvidence.observed(PixelSize(4000, 3000), EvidenceOrigin.CALLER_IMAGE_DIMENSIONS),
        orientation = FieldEvidence.observed(orientation, EvidenceOrigin.ENCODED_IMAGE_METADATA),
        logicalCameraId = FieldEvidence.observed("0", EvidenceOrigin.CAPTURE_SESSION_BINDING),
        physicalCameraId = FieldEvidence.observed("0-wide", EvidenceOrigin.CAPTURE_SESSION_BINDING),
        reportedCameraMake = FieldEvidence.observed("RealityForge", EvidenceOrigin.ANDROID_DEVICE_BUILD),
        reportedCameraModel = FieldEvidence.observed("Fixture Phone", EvidenceOrigin.ANDROID_DEVICE_BUILD),
        reportedLensMake = FieldEvidence.observed("RealityForge", EvidenceOrigin.EXIF),
        reportedLensModel = FieldEvidence.observed("Wide 24mm", EvidenceOrigin.EXIF),
        zoomRatio = FieldEvidence.observed(zoom, EvidenceOrigin.CAMERA_CAPTURE_RESULT),
        cropRegion = FieldEvidence.observed(crop, EvidenceOrigin.CAMERA_CAPTURE_RESULT),
        sensorSizeMm = FieldEvidence.observed(SensorSizeMm(6.4, 4.8), EvidenceOrigin.CAMERA_CHARACTERISTICS),
        sensorOrientationDegrees = FieldEvidence.observed(90, EvidenceOrigin.CAMERA_CHARACTERISTICS),
        focalLengthMm = FieldEvidence.observed(4.25, EvidenceOrigin.CAMERA_CAPTURE_RESULT),
        focal35mmEquivalent = FieldEvidence.derived(
            22.99,
            setOf(EvidenceOrigin.CAMERA_CAPTURE_RESULT, EvidenceOrigin.CAMERA_CHARACTERISTICS, EvidenceOrigin.DERIVED_SENSOR_GEOMETRY),
            relativeUncertainty = 0.02,
        ),
        originalMetadataRetained = true,
    )
}
