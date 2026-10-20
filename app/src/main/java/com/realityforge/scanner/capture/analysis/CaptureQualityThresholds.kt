package com.realityforge.scanner.capture.analysis

import kotlin.math.ceil
import kotlin.math.floor

/** Threshold values retain their measurement units; none is an overall quality percentage. */
data class CaptureQualityThresholds(
    val minMeanLuma: Double,
    val maxMeanLuma: Double,
    val maxDarkClippedFraction: Double,
    val maxBrightClippedFraction: Double,
    val minContrast: Double,
    val minSharpness: Double,
    val maxInterFrameResidual: Double,
) {
    init {
        require(minMeanLuma in 0.0..<maxMeanLuma)
        require(maxMeanLuma <= 1.0)
        require(maxDarkClippedFraction in 0.0..1.0)
        require(maxBrightClippedFraction in 0.0..1.0)
        require(minContrast in 0.0..1.0)
        require(minSharpness in 0.0..1.0)
        require(maxInterFrameResidual in 0.0..1.0)
    }

    companion object {
        /** Conservative uncalibrated fallback, explicitly identified in every observation. */
        val CONSERVATIVE_DEFAULTS = CaptureQualityThresholds(
            minMeanLuma = 0.15,
            maxMeanLuma = 0.86,
            maxDarkClippedFraction = 0.35,
            maxBrightClippedFraction = 0.24,
            minContrast = 0.035,
            minSharpness = 0.025,
            maxInterFrameResidual = 0.10,
        )
    }
}

data class CaptureQualityThresholdProfile(
    val thresholds: CaptureQualityThresholds,
    val source: CaptureQualityThresholdSource,
    val evidenceIds: List<String>,
) {
    init {
        require(evidenceIds.size <= CaptureQualityObservation.MAX_THRESHOLD_EVIDENCE_IDS)
        require(evidenceIds.all {
            it.isNotBlank() && it.length <= CaptureQualityObservation.MAX_EVIDENCE_ID_LENGTH
        })
        require(evidenceIds.distinct().size == evidenceIds.size)
        if (source == CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS) {
            require(evidenceIds.isEmpty())
        } else {
            require(evidenceIds.isNotEmpty())
        }
    }

    companion object {
        val DEFAULT = CaptureQualityThresholdProfile(
            thresholds = CaptureQualityThresholds.CONSERVATIVE_DEFAULTS,
            source = CaptureQualityThresholdSource.CONSERVATIVE_DEFAULTS,
            evidenceIds = emptyList(),
        )
    }
}

fun interface CaptureQualityThresholdProvider {
    fun currentProfile(): CaptureQualityThresholdProfile
}

class StaticCaptureQualityThresholdProvider(
    private val profile: CaptureQualityThresholdProfile = CaptureQualityThresholdProfile.DEFAULT,
) : CaptureQualityThresholdProvider {
    override fun currentProfile(): CaptureQualityThresholdProfile = profile
}

data class RetainedPhotoQualitySample(
    val evidenceId: String,
    val acceptedForReconstruction: Boolean,
    val metrics: CaptureQualityMetrics,
) {
    init {
        require(evidenceId.isNotBlank())
        require(evidenceId.length <= CaptureQualityObservation.MAX_EVIDENCE_ID_LENGTH)
    }
}

/**
 * Derives device/session thresholds only from explicitly accepted retained photographs.
 * Too little evidence retains the named conservative fallback instead of claiming calibration.
 */
object CaptureQualityThresholdDeriver {
    const val MIN_ACCEPTED_SAMPLES = 5

    fun derive(
        samples: List<RetainedPhotoQualitySample>,
        minimumAcceptedSamples: Int = MIN_ACCEPTED_SAMPLES,
    ): CaptureQualityThresholdProfile {
        require(minimumAcceptedSamples in 3..CaptureQualityObservation.MAX_THRESHOLD_EVIDENCE_IDS)
        val accepted = samples
            .asSequence()
            .filter { it.acceptedForReconstruction }
            .distinctBy { it.evidenceId }
            .take(CaptureQualityObservation.MAX_THRESHOLD_EVIDENCE_IDS)
            .toList()
        if (accepted.size < minimumAcceptedSamples) return CaptureQualityThresholdProfile.DEFAULT

        val means = accepted.map { it.metrics.meanLuma }
        val dark = accepted.map { it.metrics.darkClippedFraction }
        val bright = accepted.map { it.metrics.brightClippedFraction }
        val contrast = accepted.map { it.metrics.contrast }
        val sharpness = accepted.map { it.metrics.sharpness }
        val motion = accepted.mapNotNull { it.metrics.interFrameResidual }

        val minMean = (quantile(means, 0.10) - 0.08).coerceIn(0.05, 0.40)
        val maxMean = (quantile(means, 0.90) + 0.08).coerceIn(0.60, 0.95)
        val thresholds = CaptureQualityThresholds(
            minMeanLuma = minMean.coerceAtMost(maxMean - 0.10),
            maxMeanLuma = maxMean,
            maxDarkClippedFraction = (quantile(dark, 0.90) + 0.08).coerceIn(0.08, 0.65),
            maxBrightClippedFraction = (quantile(bright, 0.90) + 0.08).coerceIn(0.05, 0.60),
            minContrast = (quantile(contrast, 0.15) * 0.65).coerceIn(0.01, 0.25),
            minSharpness = (quantile(sharpness, 0.15) * 0.65).coerceIn(0.005, 0.30),
            maxInterFrameResidual = if (motion.size >= 3) {
                (quantile(motion, 0.90) * 1.5 + 0.01).coerceIn(0.03, 0.35)
            } else {
                CaptureQualityThresholds.CONSERVATIVE_DEFAULTS.maxInterFrameResidual
            },
        )
        return CaptureQualityThresholdProfile(
            thresholds = thresholds,
            source = CaptureQualityThresholdSource.RETAINED_PHOTOGRAPHS,
            evidenceIds = accepted.map { it.evidenceId },
        )
    }

    private fun quantile(values: List<Double>, probability: Double): Double {
        require(values.isNotEmpty())
        require(probability in 0.0..1.0)
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = probability * (sorted.lastIndex)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }
}
