package com.realityforge.scanner.agents.a016

import com.realityforge.scanner.capture.analysis.*
import org.junit.Assert.*
import org.junit.Test

class AnalyzerAdmissionTest {
    @Test fun cadenceAndStaleFramesAreRejectedBeforePixelExtraction() {
        val analyzer = CaptureQualityStreamAnalyzer(CaptureAnalysisSessionId("admission"))
        val frame = LumaFrame(64, 64, ByteArray(4096) { (it % 127).toByte() }, 1_000_000_000L, 0)
        assertTrue(analyzer.shouldRead(frame.timestampNanos))
        assertNotNull(analyzer.analyze(frame))
        assertFalse(analyzer.shouldRead(1_050_000_000L))
        assertFalse(analyzer.shouldRead(999_000_000L))
        assertEquals(1L, analyzer.stateSnapshot().cadenceDroppedFrameCount)
        assertEquals(1L, analyzer.stateSnapshot().outOfOrderDroppedFrameCount)
        assertEquals(1L, analyzer.stateSnapshot().analyzedFrameCount)
        assertTrue(analyzer.shouldRead(1_200_000_000L))
        analyzer.close()
        assertFalse(analyzer.shouldRead(1_300_000_000L))
    }
}
