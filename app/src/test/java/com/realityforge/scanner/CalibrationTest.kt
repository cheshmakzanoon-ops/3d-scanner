package com.realityforge.scanner

import com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource
import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.LensProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CalibrationTest {
    @Test
    fun equivalentFocalIsOrientationInvariant() {
        assertEquals(ImageLoader.focalPixels(1600, 1200, 26.0), ImageLoader.focalPixels(1200, 1600, 26.0), 1e-10)
    }

    @Test
    fun resizingScalesFocalExactly() {
        assertEquals(2 * ImageLoader.focalPixels(800, 600, 35.0), ImageLoader.focalPixels(1600, 1200, 35.0), 1e-10)
    }

    @Test
    fun fullFrameDiagonalConvention() {
        assertEquals(50.0 * 2000.0 / hypot(36.0, 24.0), ImageLoader.focalPixels(1600, 1200, 50.0), 1e-10)
    }

    @Test
    fun legacyAggregateAdapterWritesBoundedSchemaTwoWithoutInventingPhysicalEvidence() {
        val profile = LensProfile(
            1600,
            1200,
            1350.0,
            1348.0,
            799.5,
            599.5,
            -0.08,
            0.02,
            0.001,
            -0.001,
            0.0,
            0.42,
            12,
            26.0,
        )

        val encoded = LensCalibration.json(profile)
        val decoded = CalibrationProfileCodec.decode(encoded)

        assertEquals(2, JSONObject(encoded).getInt("schema"))
        assertEquals(profile, decoded.profile)
        assertEquals(CalibrationEvidenceSource.LEGACY_UNVERIFIED, decoded.provenance.evidenceSource)
        assertFalse(decoded.provenance.physicalSameLensVerified)
        assertFalse(decoded.provenance.viewEvidenceComplete)
        assertTrue(encoded.toByteArray().size <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
    }
}
