package com.realityforge.scanner.agents.a027

import com.realityforge.scanner.capture.calibration.CalibrationCaptureMetadata
import com.realityforge.scanner.capture.calibration.CalibrationCompatibilityReason
import com.realityforge.scanner.capture.calibration.CalibrationCompatibilityStatus
import com.realityforge.scanner.capture.calibration.CalibrationCoverageEvidence
import com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource
import com.realityforge.scanner.capture.calibration.CalibrationOpticalMetadata
import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.capture.calibration.CalibrationProfileCompatibility
import com.realityforge.scanner.capture.calibration.CalibrationViewEvidence
import com.realityforge.scanner.engine.LensProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProfileCodecTest {
    @Test
    fun schemaTwoRoundTripPreservesPerViewEvidenceAndChecksum() {
        val record = fullRecord(12)
        val encoded = CalibrationProfileCodec.encode(record)
        val decoded = CalibrationProfileCodec.decode(encoded)

        assertEquals(2, JSONObject(encoded).getInt("schema"))
        assertEquals(record.profileId, decoded.profileId)
        assertEquals(record.profile, decoded.profile)
        assertEquals(record.views.map { it.sourceSha256 }, decoded.views.map { it.sourceSha256 })
        assertEquals(record.views.map { it.reprojectionRmsPixels }, decoded.views.map { it.reprojectionRmsPixels })
        assertTrue(decoded.provenance.physicalSameLensVerified)
        assertTrue(encoded.toByteArray().size <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
    }

    @Test
    fun fortyViewEvidenceStaysInsideExistingRepositoryProfileBound() {
        val encoded = CalibrationProfileCodec.encode(fullRecord(40))
        assertTrue(encoded.toByteArray().size <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
        assertEquals(40, CalibrationProfileCodec.decode(encoded).views.size)
    }

    @Test
    fun legacySchemaImportsAsExplicitlyUnverified() {
        val legacy = JSONObject()
            .put("schema", 1)
            .put("model", "brown-conrady-5")
            .put("orientation", "exif-upright")
            .put("width", 1600)
            .put("height", 1200)
            .put("fx", 1350.0)
            .put("fy", 1348.0)
            .put("cx", 799.5)
            .put("cy", 599.5)
            .put("k1", -0.08)
            .put("k2", 0.02)
            .put("p1", 0.001)
            .put("p2", -0.001)
            .put("k3", 0.0)
            .put("reprojectionRmsPixels", 0.42)
            .put("acceptedViews", 12)
            .put("focal35mm", 26.0)
            .toString()

        val decoded = CalibrationProfileCodec.decode(legacy)

        assertEquals(CalibrationEvidenceSource.LEGACY_UNVERIFIED, decoded.provenance.evidenceSource)
        assertFalse(decoded.provenance.viewEvidenceComplete)
        assertFalse(decoded.provenance.physicalSameLensVerified)
        assertTrue(decoded.views.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedSchemaTwoProfileIsRejected() {
        val json = JSONObject(CalibrationProfileCodec.encode(fullRecord(12)))
        json.put("fx", json.getDouble("fx") + 100.0)
        CalibrationProfileCodec.decode(json.toString())
    }

    @Test
    fun compatibilitySeparatesMismatchFromMissingEvidence() {
        val record = fullRecord(12)
        val verified = CalibrationProfileCompatibility.evaluate(
            record,
            CalibrationCaptureMetadata(1600, 1200, optical),
        )
        assertEquals(CalibrationCompatibilityStatus.VERIFIED_COMPATIBLE, verified.status)

        val wrongLens = CalibrationProfileCompatibility.evaluate(
            record,
            CalibrationCaptureMetadata(1600, 1200, optical.copy(lensId = "rear-ultrawide", focal35mm = 14.0)),
        )
        assertEquals(CalibrationCompatibilityStatus.INCOMPATIBLE, wrongLens.status)
        assertTrue(CalibrationCompatibilityReason.LENS_ID_MISMATCH in wrongLens.reasons)
        assertTrue(CalibrationCompatibilityReason.FOCAL_LENGTH_MISMATCH in wrongLens.reasons)

        val unknown = CalibrationProfileCompatibility.evaluate(
            record,
            CalibrationCaptureMetadata(
                1600,
                1200,
                CalibrationOpticalMetadata(evidenceSource = CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED),
            ),
        )
        assertEquals(CalibrationCompatibilityStatus.COMPATIBLE_WITH_UNVERIFIED_METADATA, unknown.status)
        assertTrue(CalibrationCompatibilityReason.LENS_ID_UNAVAILABLE in unknown.reasons)
    }

    private fun fullRecord(viewCount: Int) = CalibrationProfileCodec.fromSession(
        profile = profile.copy(acceptedViews = viewCount),
        coverage = CalibrationCoverageEvidence(
            acceptedViews = viewCount,
            xRangeFraction = 0.64,
            yRangeFraction = 0.51,
            boardAreaRatio = 4.0,
            metadataComplete = true,
        ),
        views = (0 until viewCount).map { index ->
            CalibrationViewEvidence(
                index = index,
                sourceId = "v$index",
                sourceSha256 = "%064x".format(index + 1),
                uprightWidth = 1600,
                uprightHeight = 1200,
                boardCenterXFraction = 0.18 + (index % 5) * 0.15,
                boardCenterYFraction = 0.20 + (index % 4) * 0.16,
                boardAreaFraction = 0.03 + index * 0.005,
                reprojectionRmsPixels = 0.20 + index * 0.003,
                optical = optical,
                rejection = null,
            )
        },
        createdAtEpochMs = 1_700_000_000_000,
    )

    private companion object {
        val profile = LensProfile(
            width = 1600,
            height = 1200,
            fx = 1350.0,
            fy = 1348.0,
            cx = 799.5,
            cy = 599.5,
            k1 = -0.08,
            k2 = 0.02,
            p1 = 0.001,
            p2 = -0.001,
            k3 = 0.0,
            reprojectionRmsPixels = 0.42,
            acceptedViews = 12,
            focal35mm = 26.0,
        )
        val optical = CalibrationOpticalMetadata(
            cameraId = "camera-back-0",
            lensId = "rear-wide",
            zoomRatio = 1.0,
            cropFingerprint = "crop-wide-00000001",
            focal35mm = 26.0,
            evidenceSource = CalibrationEvidenceSource.PHYSICAL_DEVICE,
        )
    }
}
