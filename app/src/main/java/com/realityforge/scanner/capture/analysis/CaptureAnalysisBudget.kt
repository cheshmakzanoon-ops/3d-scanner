package com.realityforge.scanner.capture.analysis

/**
 * Runtime admission limits for live analysis. Agent 030 may supply a dynamic provider without
 * changing analyzer ownership or retaining full-resolution camera frames.
 */
data class CaptureAnalysisBudget(
    val maxAnalysisEdge: Int = 320,
    val minFrameIntervalNanos: Long = 150_000_000L,
    val maxRetainedFrameBytes: Int = 320 * 320,
    val actionConfirmationFrames: Int = 2,
    val readyConfirmationFrames: Int = 3,
) {
    init {
        require(maxAnalysisEdge in 64..1024)
        require(minFrameIntervalNanos in 33_000_000L..2_000_000_000L)
        require(maxRetainedFrameBytes in 4_096..1_048_576)
        require(actionConfirmationFrames in 1..8)
        require(readyConfirmationFrames in 1..12)
    }
}

fun interface CaptureAnalysisBudgetProvider {
    fun currentBudget(): CaptureAnalysisBudget
}

class StaticCaptureAnalysisBudgetProvider(
    private val budget: CaptureAnalysisBudget = CaptureAnalysisBudget(),
) : CaptureAnalysisBudgetProvider {
    override fun currentBudget(): CaptureAnalysisBudget = budget
}
