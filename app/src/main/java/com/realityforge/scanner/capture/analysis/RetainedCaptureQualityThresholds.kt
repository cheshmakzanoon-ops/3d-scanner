package com.realityforge.scanner.capture.analysis

import java.util.Collections

/**
 * Session-local thresholds measured from originals that the repository has actually accepted.
 * This is photographic adaptation, not physical-device qualification. Only compact statistics
 * and original SHA-256 evidence are retained; changed optics or analysis scale resets the sample.
 */
class RetainedCaptureQualityThresholds(
    private val session: CaptureAnalysisSessionId,
    private val budgets: CaptureAnalysisBudgetProvider,
) : CaptureQualityThresholdProvider, AutoCloseable {
    private val samples = LinkedHashMap<String, RetainedPhotoQualitySample>()
    private var context: Pair<String, Int>? = null
    private var profile = CaptureQualityThresholdProfile.DEFAULT
    private var closed = false

    @Synchronized
    fun recordAccepted(sessionId: CaptureAnalysisSessionId, originalSha256: String,
        opticalIdentitySha256: String, measurementEdge: Int, metrics: CaptureQualityMetrics): Boolean {
        if (closed || sessionId != session) return false
        require(originalSha256.matches(HASH) && opticalIdentitySha256.matches(HASH))
        require(measurementEdge in 128..640 && metrics.interFrameResidual == null)
        if (measurementEdge != budgets.currentBudget().maxAnalysisEdge) return false
        val nextContext = opticalIdentitySha256 to measurementEdge
        if (context != nextContext) {
            samples.clear()
            profile = CaptureQualityThresholdProfile.DEFAULT
            context = nextContext
        }
        if (originalSha256 in samples) return false
        samples[originalSha256] = RetainedPhotoQualitySample("saved-$originalSha256", true, metrics)
        while (samples.size > CaptureQualityObservation.MAX_THRESHOLD_EVIDENCE_IDS) {
            samples.remove(samples.keys.first())
        }
        profile = CaptureQualityThresholdDeriver.derive(samples.values.toList()).let {
            it.copy(evidenceIds = Collections.unmodifiableList(it.evidenceIds.toList()))
        }
        return true
    }

    @Synchronized
    override fun currentProfile(): CaptureQualityThresholdProfile {
        if (closed) return CaptureQualityThresholdProfile.DEFAULT
        if (context?.second != budgets.currentBudget().maxAnalysisEdge) {
            samples.clear()
            context = null
            profile = CaptureQualityThresholdProfile.DEFAULT
        }
        return profile
    }

    @Synchronized
    fun retainedSampleCount(): Int = samples.size

    @Synchronized
    override fun close() {
        closed = true
        samples.clear()
        context = null
        profile = CaptureQualityThresholdProfile.DEFAULT
    }

    companion object { private val HASH = Regex("[0-9a-f]{64}") }
}
