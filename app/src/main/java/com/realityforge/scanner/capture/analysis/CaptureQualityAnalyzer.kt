package com.realityforge.scanner.capture.analysis

import kotlin.math.sqrt

/**
 * Session-bound, single-frame-at-a-time live quality state. At most one bounded previous luma
 * frame is retained for inter-frame evidence; closing clears all state and makes later frames
 * no-ops.
 */
class CaptureQualityStreamAnalyzer(
    val sessionId: CaptureAnalysisSessionId,
    private val budgetProvider: CaptureAnalysisBudgetProvider = StaticCaptureAnalysisBudgetProvider(),
    private val thresholdProvider: CaptureQualityThresholdProvider = StaticCaptureQualityThresholdProvider(),
) : AutoCloseable {
    private var closed = false
    private var lastAnalyzedTimestampNanos: Long? = null
    private var previousFrame: LumaFrame? = null
    private var sequence = 0L
    private var cadenceDroppedFrameCount = 0L
    private var outOfOrderDroppedFrameCount = 0L
    private var budgetRejectedFrameCount = 0L
    private val smoother = ActionSmoother()

    @Synchronized
    fun analyze(frame: LumaFrame): CaptureQualityObservation? {
        if (closed) return null
        val budget = budgetProvider.currentBudget()
        discardRetainedFrameOutside(budget)

        val lastTimestamp = lastAnalyzedTimestampNanos
        if (lastTimestamp != null) {
            if (frame.timestampNanos <= lastTimestamp) {
                outOfOrderDroppedFrameCount++
                return null
            }
            if (frame.timestampNanos - lastTimestamp < budget.minFrameIntervalNanos) {
                cadenceDroppedFrameCount++
                return null
            }
        }
        lastAnalyzedTimestampNanos = frame.timestampNanos
        sequence++

        if (!frame.fitsWithin(budget)) {
            budgetRejectedFrameCount++
            previousFrame = null
            smoother.reset()
            return CaptureQualityObservation(
                observationId = observationId(sequence, frame.timestampNanos),
                sessionId = sessionId,
                sequence = sequence,
                frameTimestampNanos = frame.timestampNanos,
                sourceRotationDegrees = frame.sourceRotationDegrees,
                status = CaptureQualityStatus.UNAVAILABLE,
                action = CaptureQualityActionCode.WAIT_FOR_EVIDENCE,
                confidence = 0.0,
                metrics = null,
                evidenceCodes = setOf(CaptureQualityEvidenceCode.FRAME_OUTSIDE_BUDGET),
                thresholdSource = CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS,
                thresholdEvidenceIds = emptyList(),
            )
        }

        val metrics = CaptureQualityEstimator.measure(frame, previousFrame)
        previousFrame = frame.retainedCopy()
        val profile = thresholdProvider.currentProfile()
        val raw = classify(metrics, profile.thresholds)
        val smoothed = smoother.update(
            raw.action,
            budget.actionConfirmationFrames,
            budget.readyConfirmationFrames,
        )
        val evidence = linkedSetOf<CaptureQualityEvidenceCode>()
        evidence += if (profile.source == CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS) {
            CaptureQualityEvidenceCode.CALIBRATED_THRESHOLDS
        } else {
            CaptureQualityEvidenceCode.DEFAULT_THRESHOLDS
        }
        evidence += raw.evidence
        if (smoothed.pending) evidence += CaptureQualityEvidenceCode.SMOOTHING_PENDING
        val status = when (smoothed.action) {
            CaptureQualityActionCode.WAIT_FOR_EVIDENCE -> CaptureQualityStatus.WARMING_UP
            CaptureQualityActionCode.READY_TO_CAPTURE -> CaptureQualityStatus.ACCEPTABLE
            else -> CaptureQualityStatus.ACTION_REQUIRED
        }
        return CaptureQualityObservation(
            observationId = observationId(sequence, frame.timestampNanos),
            sessionId = sessionId,
            sequence = sequence,
            frameTimestampNanos = frame.timestampNanos,
            sourceRotationDegrees = frame.sourceRotationDegrees,
            status = status,
            action = smoothed.action,
            confidence = measurementConfidence(metrics, profile.source),
            metrics = metrics,
            evidenceCodes = evidence,
            thresholdSource = profile.source,
            thresholdEvidenceIds = profile.evidenceIds,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        previousFrame = null
        lastAnalyzedTimestampNanos = null
        smoother.reset()
    }

    @Synchronized
    fun isClosed(): Boolean = closed

    @Synchronized
    fun stateSnapshot(): CaptureAnalysisStateSnapshot = CaptureAnalysisStateSnapshot(
        analyzedFrameCount = sequence,
        cadenceDroppedFrameCount = cadenceDroppedFrameCount,
        outOfOrderDroppedFrameCount = outOfOrderDroppedFrameCount,
        budgetRejectedFrameCount = budgetRejectedFrameCount,
        retainedFrameCount = if (previousFrame == null) 0 else 1,
        retainedFrameBytes = previousFrame?.byteCount ?: 0,
        lastAnalyzedTimestampNanos = lastAnalyzedTimestampNanos,
        closed = closed,
    )

    private fun discardRetainedFrameOutside(budget: CaptureAnalysisBudget) {
        val retained = previousFrame ?: return
        if (!retained.fitsWithin(budget)) {
            previousFrame = null
            smoother.reset()
        }
    }

    private fun LumaFrame.fitsWithin(budget: CaptureAnalysisBudget): Boolean =
        width <= budget.maxAnalysisEdge &&
            height <= budget.maxAnalysisEdge &&
            byteCount <= budget.maxRetainedFrameBytes

    private fun observationId(sequence: Long, timestampNanos: Long): String =
        "${sessionId.value}:$sequence:$timestampNanos"

    private fun classify(
        metrics: CaptureQualityMetrics,
        thresholds: CaptureQualityThresholds,
    ): RawAssessment {
        val evidence = linkedSetOf<CaptureQualityEvidenceCode>()
        val underexposed = metrics.meanLuma < thresholds.minMeanLuma ||
            metrics.darkClippedFraction > thresholds.maxDarkClippedFraction
        val overexposed = metrics.meanLuma > thresholds.maxMeanLuma ||
            metrics.brightClippedFraction > thresholds.maxBrightClippedFraction
        if (underexposed && overexposed) {
            evidence += CaptureQualityEvidenceCode.UNDEREXPOSED
            evidence += CaptureQualityEvidenceCode.OVEREXPOSED
            return RawAssessment(CaptureQualityActionCode.EVEN_OUT_LIGHTING, evidence)
        }
        if (underexposed) {
            evidence += CaptureQualityEvidenceCode.UNDEREXPOSED
            return RawAssessment(CaptureQualityActionCode.ADD_LIGHT, evidence)
        }
        if (overexposed) {
            evidence += CaptureQualityEvidenceCode.OVEREXPOSED
            return RawAssessment(CaptureQualityActionCode.REDUCE_LIGHT, evidence)
        }
        val residual = metrics.interFrameResidual
        if (residual != null && residual > thresholds.maxInterFrameResidual) {
            evidence += CaptureQualityEvidenceCode.EXCESSIVE_FRAME_CHANGE
            return RawAssessment(CaptureQualityActionCode.HOLD_STEADY, evidence)
        }
        if (metrics.contrast < thresholds.minContrast) {
            evidence += CaptureQualityEvidenceCode.LOW_CONTRAST
            return RawAssessment(CaptureQualityActionCode.REFOCUS_OR_REFRAME, evidence)
        }
        if (metrics.sharpness < thresholds.minSharpness) {
            evidence += CaptureQualityEvidenceCode.LOW_SHARPNESS
            return RawAssessment(CaptureQualityActionCode.REFOCUS_OR_REFRAME, evidence)
        }
        if (residual == null) {
            evidence += CaptureQualityEvidenceCode.MOTION_UNAVAILABLE
            return RawAssessment(CaptureQualityActionCode.WAIT_FOR_EVIDENCE, evidence)
        }
        return RawAssessment(CaptureQualityActionCode.READY_TO_CAPTURE, evidence)
    }

    private fun measurementConfidence(
        metrics: CaptureQualityMetrics,
        source: CaptureQualityThresholdSource,
    ): Double {
        val sampleConfidence = (sqrt(metrics.sampledPixelCount.toDouble()) / 180.0)
            .coerceIn(0.0, 1.0)
        val textureConfidence = (metrics.contrast / 0.12).coerceIn(0.15, 1.0)
        val motionConfidence = if (metrics.interFrameResidual == null) 0.55 else 1.0
        val thresholdConfidence = if (source == CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS) {
            1.0
        } else {
            0.70
        }
        return (
            0.35 * sampleConfidence +
                0.30 * textureConfidence +
                0.15 * motionConfidence +
                0.20 * thresholdConfidence
            ).coerceIn(0.0, 1.0)
    }

    private data class RawAssessment(
        val action: CaptureQualityActionCode,
        val evidence: Set<CaptureQualityEvidenceCode>,
    )

    private class ActionSmoother {
        private var published = CaptureQualityActionCode.WAIT_FOR_EVIDENCE
        private var candidate: CaptureQualityActionCode? = null
        private var candidateCount = 0

        fun update(
            raw: CaptureQualityActionCode,
            actionConfirmationFrames: Int,
            readyConfirmationFrames: Int,
        ): SmoothedAction {
            if (raw == published) {
                candidate = null
                candidateCount = 0
                return SmoothedAction(published, pending = false)
            }
            if (candidate == raw) {
                candidateCount++
            } else {
                candidate = raw
                candidateCount = 1
            }
            val required = when (raw) {
                CaptureQualityActionCode.WAIT_FOR_EVIDENCE -> 1
                CaptureQualityActionCode.READY_TO_CAPTURE -> readyConfirmationFrames
                else -> actionConfirmationFrames
            }
            if (candidateCount >= required) {
                published = raw
                candidate = null
                candidateCount = 0
                return SmoothedAction(published, pending = false)
            }
            return SmoothedAction(published, pending = true)
        }

        fun reset() {
            published = CaptureQualityActionCode.WAIT_FOR_EVIDENCE
            candidate = null
            candidateCount = 0
        }
    }

    private data class SmoothedAction(
        val action: CaptureQualityActionCode,
        val pending: Boolean,
    )
}
