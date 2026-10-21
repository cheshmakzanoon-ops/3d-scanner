package com.realityforge.scanner.agents.a016

import com.realityforge.scanner.capture.analysis.*
import org.junit.Assert.*
import org.junit.Test

class RetainedCaptureQualityThresholdsTest {
    @Test fun onlyDistinctAcceptedPhotosFromThisSessionCanProduceABoundedThresholdProfile() {
        val session = CaptureAnalysisSessionId("session-a")
        val provider = RetainedCaptureQualityThresholds(session, StaticCaptureAnalysisBudgetProvider())
        assertFalse(provider.recordAccepted(CaptureAnalysisSessionId("foreign"), hash(1), hash(99), 320, metrics()))
        repeat(4) { index -> assertTrue(provider.recordAccepted(session, hash(index), hash(99), 320, metrics())) }
        assertFalse(provider.recordAccepted(session, hash(0), hash(99), 320, metrics()))
        assertEquals(CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS, provider.currentProfile().source)
        provider.recordAccepted(session, hash(4), hash(99), 320, metrics())
        assertEquals(CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS, provider.currentProfile().source)
        assertEquals((0..4).map { "saved-${hash(it)}" }, provider.currentProfile().evidenceIds)
        for (index in 5..80) provider.recordAccepted(session, hash(index), hash(99), 320, metrics())
        assertEquals(32, provider.retainedSampleCount())
        assertEquals((49..80).map { "saved-${hash(it)}" }, provider.currentProfile().evidenceIds)
        provider.close()
        assertEquals(0, provider.retainedSampleCount())
        assertEquals(CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS, provider.currentProfile().source)
        assertFalse(provider.recordAccepted(session, hash(90), hash(99), 320, metrics()))
    }

    @Test fun opticalChangeOrResourceScaleChangeCannotReuseAnUnrelatedThresholdSample() {
        val session = CaptureAnalysisSessionId("session-a")
        var budget = CaptureAnalysisBudget()
        val provider = RetainedCaptureQualityThresholds(session, CaptureAnalysisBudgetProvider { budget })
        repeat(5) { provider.recordAccepted(session, hash(it), hash(99), 320, metrics()) }
        assertEquals(CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS, provider.currentProfile().source)
        provider.recordAccepted(session, hash(6), hash(98), 320, metrics())
        assertEquals(1, provider.retainedSampleCount())
        assertEquals(CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS, provider.currentProfile().source)
        budget = CaptureAnalysisBudget(maxAnalysisEdge = 160, maxRetainedFrameBytes = 25_600)
        assertEquals(CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS, provider.currentProfile().source)
        assertEquals(0, provider.retainedSampleCount())
        assertFalse(provider.recordAccepted(session, hash(7), hash(98), 320, metrics()))
        assertEquals(0, provider.retainedSampleCount())
    }

    private fun hash(index: Int): String = index.toString(16).padStart(64, '0')
    private fun metrics() = CaptureQualityMetrics(320 * 240, .5, .15, .12, .01, .02, null)
}
