package com.realityforge.scanner.agents.a016

import com.realityforge.scanner.capture.analysis.CaptureQualityMetrics
import com.realityforge.scanner.capture.analysis.CaptureQualityThresholdDeriver
import com.realityforge.scanner.capture.analysis.CaptureQualityThresholdSource
import com.realityforge.scanner.capture.analysis.RetainedPhotoQualitySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityThresholdDeriverTest {
    @Test
    fun insufficientEvidenceKeepsNamedConservativeDefaults() {
        val profile = CaptureQualityThresholdDeriver.derive((0 until 4).map(::sample))
        assertEquals(CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS, profile.source)
        assertTrue(profile.evidenceIds.isEmpty())
    }

    @Test
    fun acceptedRetainedPhotosProduceBoundedTraceableThresholds() {
        val samples = (0 until 7).map(::sample) +
            sample(7).copy(acceptedForReconstruction = false) +
            sample(2) // duplicate evidence ID is ignored
        val profile = CaptureQualityThresholdDeriver.derive(samples)

        assertEquals(CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS, profile.source)
        assertEquals((0 until 7).map { "retained-$it" }, profile.evidenceIds)
        assertTrue(profile.thresholds.minMeanLuma < profile.thresholds.maxMeanLuma)
        assertTrue(profile.thresholds.minSharpness in 0.005..0.30)
        assertTrue(profile.thresholds.maxInterFrameResidual in 0.03..0.35)
    }

    private fun sample(index: Int) = RetainedPhotoQualitySample(
        evidenceId = "retained-$index",
        acceptedForReconstruction = true,
        metrics = CaptureQualityMetrics(
            sampledPixelCount = 20_000,
            meanLuma = 0.42 + index * 0.02,
            contrast = 0.12 + index * 0.005,
            sharpness = 0.08 + index * 0.004,
            darkClippedFraction = 0.04 + index * 0.005,
            brightClippedFraction = 0.03 + index * 0.004,
            interFrameResidual = 0.025 + index * 0.003,
        ),
    )
}
