package com.realityforge.scanner.agents.a016

import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudget
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.analysis.CaptureAnalysisSessionId
import com.realityforge.scanner.capture.analysis.CaptureQualityActionCode
import com.realityforge.scanner.capture.analysis.CaptureQualityEvidenceCode
import com.realityforge.scanner.capture.analysis.CaptureQualityStatus
import com.realityforge.scanner.capture.analysis.CaptureQualityStreamAnalyzer
import com.realityforge.scanner.capture.analysis.LumaFrame
import com.realityforge.scanner.capture.analysis.StaticCaptureAnalysisBudgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityStreamAnalyzerTest {
    @Test
    fun cadenceIsBoundedAndDroppedFramesDoNotAdvanceEvidence() {
        val analyzer = analyzer(
            CaptureAnalysisBudget(
                maxAnalysisEdge = 128,
                minFrameIntervalNanos = 100_000_000L,
                maxRetainedFrameBytes = 128 * 128,
                actionConfirmationFrames = 1,
                readyConfirmationFrames = 1,
            ),
        )
        val first = analyzer.analyze(pattern(1_000_000_000L))
        val dropped = analyzer.analyze(pattern(1_050_000_000L, shift = 8))
        val second = analyzer.analyze(pattern(1_120_000_000L))

        assertEquals(1L, first!!.sequence)
        assertNull(dropped)
        assertEquals(2L, second!!.sequence)
        assertEquals(0.0, second.metrics!!.interFrameResidual!!, 1e-12)
        val snapshot = analyzer.stateSnapshot()
        assertEquals(2L, snapshot.analyzedFrameCount)
        assertEquals(1L, snapshot.cadenceDroppedFrameCount)
        assertEquals(1, snapshot.retainedFrameCount)
        assertEquals(64 * 48, snapshot.retainedFrameBytes)
    }

    @Test
    fun smoothingRequiresRepeatedActionAndRepeatedRecoveryEvidence() {
        val analyzer = analyzer(
            CaptureAnalysisBudget(
                maxAnalysisEdge = 128,
                minFrameIntervalNanos = 50_000_000L,
                maxRetainedFrameBytes = 128 * 128,
                actionConfirmationFrames = 2,
                readyConfirmationFrames = 2,
            ),
        )
        analyzer.analyze(pattern(1_000_000_000L))
        val readyPending = analyzer.analyze(pattern(1_100_000_000L))!!
        val ready = analyzer.analyze(pattern(1_200_000_000L))!!
        val oneDark = analyzer.analyze(darkPattern(1_300_000_000L))!!
        val repeatedDark = analyzer.analyze(darkPattern(1_400_000_000L))!!

        assertEquals(CaptureQualityActionCode.WAIT_FOR_EVIDENCE, readyPending.action)
        assertTrue(CaptureQualityEvidenceCode.SMOOTHING_PENDING in readyPending.evidenceCodes)
        assertEquals(CaptureQualityActionCode.READY_TO_CAPTURE, ready.action)
        assertEquals(CaptureQualityStatus.ACCEPTABLE, ready.status)
        assertEquals(CaptureQualityActionCode.READY_TO_CAPTURE, oneDark.action)
        assertTrue(CaptureQualityEvidenceCode.UNDEREXPOSED in oneDark.evidenceCodes)
        assertEquals(CaptureQualityActionCode.ADD_LIGHT, repeatedDark.action)
        assertEquals(CaptureQualityStatus.ACTION_REQUIRED, repeatedDark.status)

        // The first brighter frame may carry inter-frame evidence; two subsequent stable frames
        // are required before the positive state is published again.
        analyzer.analyze(pattern(1_500_000_000L))
        analyzer.analyze(pattern(1_600_000_000L))
        val recovered = analyzer.analyze(pattern(1_700_000_000L))!!
        assertEquals(CaptureQualityActionCode.READY_TO_CAPTURE, recovered.action)
    }

    @Test
    fun outOfOrderFramesDoNotReplaceRetainedEvidence() {
        val analyzer = analyzer(
            CaptureAnalysisBudget(
                maxAnalysisEdge = 128,
                minFrameIntervalNanos = 50_000_000L,
                maxRetainedFrameBytes = 128 * 128,
                actionConfirmationFrames = 1,
                readyConfirmationFrames = 1,
            ),
        )
        analyzer.analyze(pattern(1_000_000_000L))
        analyzer.analyze(pattern(1_100_000_000L))
        assertNull(analyzer.analyze(pattern(1_050_000_000L, shift = 8)))
        val afterStaleFrame = analyzer.analyze(pattern(1_200_000_000L))!!

        assertEquals(0.0, afterStaleFrame.metrics!!.interFrameResidual!!, 1e-12)
        assertEquals(1L, analyzer.stateSnapshot().outOfOrderDroppedFrameCount)
        assertEquals(3L, analyzer.stateSnapshot().analyzedFrameCount)
    }

    @Test
    fun missingComparableMotionEvidenceRevokesReadyImmediately() {
        val analyzer = analyzer(
            CaptureAnalysisBudget(
                maxAnalysisEdge = 128,
                minFrameIntervalNanos = 50_000_000L,
                maxRetainedFrameBytes = 128 * 128,
                actionConfirmationFrames = 3,
                readyConfirmationFrames = 1,
            ),
        )
        analyzer.analyze(pattern(1_000_000_000L))
        assertEquals(
            CaptureQualityActionCode.READY_TO_CAPTURE,
            analyzer.analyze(pattern(1_100_000_000L))!!.action,
        )

        val resized = analyzer.analyze(
            pattern(1_200_000_000L, width = 48, height = 64),
        )!!
        assertEquals(CaptureQualityActionCode.WAIT_FOR_EVIDENCE, resized.action)
        assertEquals(CaptureQualityStatus.WARMING_UP, resized.status)
        assertTrue(CaptureQualityEvidenceCode.MOTION_UNAVAILABLE in resized.evidenceCodes)
    }

    @Test
    fun dynamicBudgetShrinkDropsOversizedRetainedEvidence() {
        var budget = CaptureAnalysisBudget(
            maxAnalysisEdge = 128,
            minFrameIntervalNanos = 50_000_000L,
            maxRetainedFrameBytes = 128 * 128,
            actionConfirmationFrames = 1,
            readyConfirmationFrames = 1,
        )
        val analyzer = CaptureQualityStreamAnalyzer(
            sessionId = CaptureAnalysisSessionId("session-a016-dynamic"),
            budgetProvider = CaptureAnalysisBudgetProvider { budget },
        )
        analyzer.analyze(pattern(1_000_000_000L, width = 80, height = 80))
        assertEquals(6_400, analyzer.stateSnapshot().retainedFrameBytes)

        budget = budget.copy(maxAnalysisEdge = 64, maxRetainedFrameBytes = 4_096)
        val afterShrink = analyzer.analyze(
            pattern(1_100_000_000L, width = 64, height = 64),
        )!!
        assertNull(afterShrink.metrics!!.interFrameResidual)
        assertEquals(CaptureQualityActionCode.WAIT_FOR_EVIDENCE, afterShrink.action)
        assertEquals(4_096, analyzer.stateSnapshot().retainedFrameBytes)
    }

    @Test
    fun outOfBudgetAndClosedSessionsCannotPublishStaleEvidence() {
        val analyzer = analyzer(
            CaptureAnalysisBudget(
                maxAnalysisEdge = 64,
                minFrameIntervalNanos = 50_000_000L,
                maxRetainedFrameBytes = 4_096,
                actionConfirmationFrames = 1,
                readyConfirmationFrames = 1,
            ),
        )
        val unavailable = analyzer.analyze(
            LumaFrame(80, 80, ByteArray(6_400) { 90 }, 1_000_000_000L, 0),
        )!!
        assertEquals(CaptureQualityStatus.UNAVAILABLE, unavailable.status)
        assertNull(unavailable.metrics)
        assertEquals(
            setOf(CaptureQualityEvidenceCode.FRAME_OUTSIDE_BUDGET),
            unavailable.evidenceCodes,
        )

        analyzer.close()
        assertTrue(analyzer.isClosed())
        assertEquals(0, analyzer.stateSnapshot().retainedFrameCount)
        assertNull(analyzer.analyze(pattern(1_200_000_000L)))
        analyzer.close() // idempotent
    }

    private fun analyzer(budget: CaptureAnalysisBudget) = CaptureQualityStreamAnalyzer(
        sessionId = CaptureAnalysisSessionId("session-a016"),
        budgetProvider = StaticCaptureAnalysisBudgetProvider(budget),
    )

    private fun pattern(
        timestamp: Long,
        shift: Int = 0,
        width: Int = 64,
        height: Int = 48,
    ): LumaFrame = LumaFrame(
        width,
        height,
        ByteArray(width * height) { index ->
            val x = (index % width - shift).mod(width)
            val y = index / width
            if (((x / 4) + (y / 4)) % 2 == 0) 35 else 220.toByte()
        },
        timestamp,
        0,
    )

    private fun darkPattern(timestamp: Long): LumaFrame {
        val width = 64
        val height = 48
        return LumaFrame(
            width,
            height,
            ByteArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (((x / 4) + (y / 4)) % 2 == 0) 2 else 28
            },
            timestamp,
            0,
        )
    }
}
