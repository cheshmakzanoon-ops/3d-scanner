package com.realityforge.scanner.capture.analysis

/**
 * Stable identity for one live camera session. Observations from one token must never be
 * rendered or consumed by a different session.
 */
data class CaptureAnalysisSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Capture analysis session ID must not be blank." }
        require(value.length <= MAX_LENGTH) { "Capture analysis session ID is too long." }
    }

    companion object {
        const val MAX_LENGTH = 128
    }
}

enum class CaptureQualityStatus {
    WARMING_UP,
    ACCEPTABLE,
    ACTION_REQUIRED,
    UNAVAILABLE,
}

enum class CaptureQualityActionCode {
    WAIT_FOR_EVIDENCE,
    READY_TO_CAPTURE,
    HOLD_STEADY,
    ADD_LIGHT,
    REDUCE_LIGHT,
    EVEN_OUT_LIGHTING,
    REFOCUS_OR_REFRAME,
}

enum class CaptureQualityEvidenceCode {
    CALIBRATED_THRESHOLDS,
    DEFAULT_THRESHOLDS,
    MOTION_UNAVAILABLE,
    EXCESSIVE_FRAME_CHANGE,
    UNDEREXPOSED,
    OVEREXPOSED,
    LOW_SHARPNESS,
    LOW_CONTRAST,
    SMOOTHING_PENDING,
    FRAME_OUTSIDE_BUDGET,
}

enum class CaptureQualityThresholdSource {
    CONSERVATIVE_DEFAULTS,
    RETAINED_PHOTOGRAPHS,
}

/**
 * Normalized measurements from a bounded luma image. These are evidence values, not a
 * fabricated scan-completeness or overall-quality percentage.
 */
data class CaptureQualityMetrics(
    val sampledPixelCount: Int,
    val meanLuma: Double,
    val contrast: Double,
    val sharpness: Double,
    val darkClippedFraction: Double,
    val brightClippedFraction: Double,
    val interFrameResidual: Double?,
) {
    init {
        require(sampledPixelCount >= 9)
        require(meanLuma in 0.0..1.0)
        require(contrast in 0.0..1.0)
        require(sharpness in 0.0..1.0)
        require(darkClippedFraction in 0.0..1.0)
        require(brightClippedFraction in 0.0..1.0)
        require(interFrameResidual == null || interFrameResidual in 0.0..1.0)
    }
}

/**
 * One timestamped, session-bound live observation. [confidence] describes confidence in the
 * bounded measurements only; it does not describe unseen object coverage or reconstruction
 * success.
 */
data class CaptureQualityObservation(
    val observationId: String,
    val sessionId: CaptureAnalysisSessionId,
    val sequence: Long,
    val frameTimestampNanos: Long,
    val sourceRotationDegrees: Int,
    val status: CaptureQualityStatus,
    val action: CaptureQualityActionCode,
    val confidence: Double,
    val metrics: CaptureQualityMetrics?,
    val evidenceCodes: Set<CaptureQualityEvidenceCode>,
    val thresholdSource: CaptureQualityThresholdSource,
    val thresholdEvidenceIds: List<String>,
) {
    init {
        require(observationId.isNotBlank())
        require(sequence > 0)
        require(frameTimestampNanos >= 0)
        require(sourceRotationDegrees in VALID_ROTATIONS)
        require(confidence in 0.0..1.0)
        require(evidenceCodes.isNotEmpty())
        require(thresholdEvidenceIds.size <= MAX_THRESHOLD_EVIDENCE_IDS)
        require(thresholdEvidenceIds.all { it.isNotBlank() && it.length <= MAX_EVIDENCE_ID_LENGTH })
        require(thresholdEvidenceIds.distinct().size == thresholdEvidenceIds.size)
        if (status == CaptureQualityStatus.UNAVAILABLE) {
            require(metrics == null)
            require(action == CaptureQualityActionCode.WAIT_FOR_EVIDENCE)
            require(confidence == 0.0)
        } else {
            require(metrics != null)
        }
        if (thresholdSource == CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS) {
            require(thresholdEvidenceIds.isEmpty())
        } else {
            require(thresholdEvidenceIds.isNotEmpty())
        }
    }

    companion object {
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
        const val MAX_THRESHOLD_EVIDENCE_IDS = 32
        const val MAX_EVIDENCE_ID_LENGTH = 128
    }
}

/** Bounded runtime counters for resource qualification and integration diagnostics. */
data class CaptureAnalysisStateSnapshot(
    val analyzedFrameCount: Long,
    val cadenceDroppedFrameCount: Long,
    val outOfOrderDroppedFrameCount: Long,
    val budgetRejectedFrameCount: Long,
    val retainedFrameCount: Int,
    val retainedFrameBytes: Int,
    val lastAnalyzedTimestampNanos: Long?,
    val closed: Boolean,
) {
    init {
        require(analyzedFrameCount >= 0)
        require(cadenceDroppedFrameCount >= 0)
        require(outOfOrderDroppedFrameCount >= 0)
        require(budgetRejectedFrameCount >= 0)
        require(retainedFrameCount in 0..1)
        require(retainedFrameBytes >= 0)
        require((retainedFrameCount == 0) == (retainedFrameBytes == 0))
        require(lastAnalyzedTimestampNanos == null || lastAnalyzedTimestampNanos >= 0)
    }
}

enum class CaptureAnalysisFailureCode {
    UNSUPPORTED_FRAME,
    INVALID_FRAME,
    CANCELLED,
    ANALYZER_FAILURE,
    OBSERVER_FAILURE,
}

/** A typed live-analysis failure. Camera capture remains usable after this event. */
data class CaptureAnalysisFailure(
    val sessionId: CaptureAnalysisSessionId,
    val frameTimestampNanos: Long?,
    val code: CaptureAnalysisFailureCode,
    val detail: String,
) {
    init {
        require(frameTimestampNanos == null || frameTimestampNanos >= 0)
        require(detail.isNotBlank())
        require(detail.length <= 240)
    }
}
