package com.realityforge.scanner.agents.a027

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.capture.calibration.CalibrationCaptureMetadata
import com.realityforge.scanner.capture.calibration.CalibrationCompatibilityStatus
import com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource
import com.realityforge.scanner.capture.calibration.CalibrationOpticalMetadata
import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.capture.calibration.CalibrationProfileCompatibility
import com.realityforge.scanner.engine.LensProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationProfileDeviceTest {
    @Test
    fun schemaTwoAggregateRoundTripRemainsUnverifiedOnAndroidRuntime() {
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
        val decoded = CalibrationProfileCodec.decode(
            CalibrationProfileCodec.encode(CalibrationProfileCodec.aggregateOnly(profile)),
        )
        assertEquals(profile, decoded.profile)
        assertFalse(decoded.provenance.physicalSameLensVerified)

        val compatibility = CalibrationProfileCompatibility.evaluate(
            decoded,
            CalibrationCaptureMetadata(
                1600,
                1200,
                CalibrationOpticalMetadata(
                    focal35mm = 26.0,
                    evidenceSource = CalibrationEvidenceSource.EMULATOR,
                ),
            ),
        )
        assertEquals(
            CalibrationCompatibilityStatus.COMPATIBLE_WITH_UNVERIFIED_METADATA,
            compatibility.status,
        )
    }
}
