package com.realityforge.scanner.capture.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** Pure, allocation-bounded luma measurements used by live capture analysis. */
object CaptureQualityEstimator {
    private const val DARK_CLIP = 12
    private const val BRIGHT_CLIP = 243
    private const val MAX_LAPLACIAN = 4.0 * 255.0

    fun measure(current: LumaFrame, previous: LumaFrame? = null): CaptureQualityMetrics {
        val count = current.byteCount
        var sum = 0.0
        var sumSquares = 0.0
        var dark = 0
        var bright = 0
        for (index in 0 until count) {
            val value = current.unsigned(index)
            sum += value
            sumSquares += value.toDouble() * value
            if (value <= DARK_CLIP) dark++
            if (value >= BRIGHT_CLIP) bright++
        }
        val meanRaw = sum / count
        val variance = (sumSquares / count - meanRaw * meanRaw).coerceAtLeast(0.0)

        var laplacianSquares = 0.0
        var laplacianCount = 0
        val width = current.width
        val height = current.height
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val laplacian = 4 * current.unsigned(index) -
                    current.unsigned(index - 1) - current.unsigned(index + 1) -
                    current.unsigned(index - width) - current.unsigned(index + width)
                laplacianSquares += laplacian.toDouble() * laplacian
                laplacianCount++
            }
        }
        val sharpness = if (laplacianCount == 0) 0.0 else {
            (sqrt(laplacianSquares / laplacianCount) / MAX_LAPLACIAN).coerceIn(0.0, 1.0)
        }
        val residual = previous?.takeIf {
            it.width == current.width && it.height == current.height
        }?.let { prior ->
            val priorMean = prior.pixels.indices.sumOf { prior.unsigned(it).toDouble() } / count
            val meanShift = meanRaw - priorMean
            var residualSum = 0.0
            for (index in 0 until count) {
                residualSum += abs((current.unsigned(index) - prior.unsigned(index)) - meanShift)
            }
            (residualSum / count / 255.0).coerceIn(0.0, 1.0)
        }

        return CaptureQualityMetrics(
            sampledPixelCount = count,
            meanLuma = (meanRaw / 255.0).coerceIn(0.0, 1.0),
            contrast = (sqrt(variance) / 255.0).coerceIn(0.0, 1.0),
            sharpness = sharpness,
            darkClippedFraction = dark.toDouble() / count,
            brightClippedFraction = bright.toDouble() / count,
            interFrameResidual = residual,
        )
    }
}
