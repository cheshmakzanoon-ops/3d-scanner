package com.realityforge.scanner.processing.resources

import android.content.Context
import android.os.SystemClock
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudget
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider

/** Observes at most once per second; reduces optional analysis load without blocking CameraX frame release. */
class ResourceAwareCaptureBudgetProvider(context: Context) : CaptureAnalysisBudgetProvider {
    private val appContext = context.applicationContext
    private var lastObserved = -1L
    private var recoverySince: Long? = null
    private var constrained = false
    private var current = COMPACT

    @Synchronized
    override fun currentBudget(): CaptureAnalysisBudget {
        val now = SystemClock.elapsedRealtime()
        if (lastObserved >= 0 && now - lastObserved < 1000) return current
        lastObserved = now
        val snapshot = AndroidResourceObserver.sample(appContext)
        val severe = snapshot.lowMemory || (snapshot.thermalStatus ?: 0) >= 3
        val moderate = snapshot.thermalStatus == null || (snapshot.thermalStatus ?: 0) >= 2 ||
            snapshot.powerSave == true || (snapshot.batteryPercent ?: 100) <= 10
        if (severe || moderate) {
            constrained = true; recoverySince = null
            current = if (severe) MINIMAL else COMPACT
        } else if (constrained) {
            if (recoverySince == null) recoverySince = now
            if (now - requireNotNull(recoverySince) >= 15_000) { constrained = false; current = BASELINE }
        } else current = BASELINE
        return current
    }

    companion object {
        private val BASELINE = CaptureAnalysisBudget()
        private val COMPACT = CaptureAnalysisBudget(maxAnalysisEdge = 160, minFrameIntervalNanos = 500_000_000L,
            maxRetainedFrameBytes = 160 * 160)
        private val MINIMAL = CaptureAnalysisBudget(maxAnalysisEdge = 64, minFrameIntervalNanos = 2_000_000_000L,
            maxRetainedFrameBytes = 64 * 64)
    }
}
