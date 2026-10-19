package com.realityforge.scanner.agents.a016

import com.realityforge.scanner.capture.analysis.CaptureQualityEstimator
import com.realityforge.scanner.capture.analysis.LumaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityEstimatorTest {
    @Test
    fun blurExposureAndMotionSignalsFollowDefinedImageChanges() {
        val sharp = checkerboard(64, 48, low = 28, high = 226)
        val blurred = boxBlur(sharp, 64, 48, radius = 3)
        val dark = ByteArray(64 * 48) { 5 }
        val bright = ByteArray(64 * 48) { 250.toByte() }

        val sharpMetrics = CaptureQualityEstimator.measure(frame(sharp, 1_000_000_000L))
        val blurMetrics = CaptureQualityEstimator.measure(frame(blurred, 1_200_000_000L))
        val darkMetrics = CaptureQualityEstimator.measure(frame(dark, 1_400_000_000L))
        val brightMetrics = CaptureQualityEstimator.measure(frame(bright, 1_600_000_000L))

        assertTrue(sharpMetrics.sharpness > blurMetrics.sharpness * 2.0)
        assertTrue(darkMetrics.meanLuma < 0.03)
        assertTrue(darkMetrics.darkClippedFraction > 0.99)
        assertTrue(brightMetrics.meanLuma > 0.97)
        assertTrue(brightMetrics.brightClippedFraction > 0.99)

        val identical = CaptureQualityEstimator.measure(
            frame(sharp, 1_800_000_000L),
            frame(sharp, 1_600_000_000L),
        )
        val shifted = CaptureQualityEstimator.measure(
            frame(shiftRight(sharp, 64, 5), 2_000_000_000L),
            frame(sharp, 1_800_000_000L),
        )
        assertEquals(0.0, identical.interFrameResidual!!, 1e-12)
        assertTrue(shifted.interFrameResidual!! > 0.08)
    }

    @Test
    fun globalBrightnessOffsetDoesNotMasqueradeAsCameraMotion() {
        val source = checkerboard(48, 48, low = 40, high = 160)
        val offset = ByteArray(source.size) { index ->
            ((source[index].toInt() and 0xff) + 35).coerceAtMost(255).toByte()
        }
        val metrics = CaptureQualityEstimator.measure(
            frame(offset, 2_000_000_000L, width = 48, height = 48),
            frame(source, 1_800_000_000L, width = 48, height = 48),
        )
        assertTrue(metrics.interFrameResidual!! < 1e-9)
    }

    private fun frame(
        pixels: ByteArray,
        timestamp: Long,
        width: Int = 64,
        height: Int = 48,
    ) = LumaFrame(width, height, pixels, timestamp, 0)

    private fun checkerboard(width: Int, height: Int, low: Int, high: Int): ByteArray =
        ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (((x / 4) + (y / 4)) % 2 == 0) low.toByte() else high.toByte()
        }

    private fun boxBlur(
        input: ByteArray,
        width: Int,
        height: Int,
        radius: Int,
    ): ByteArray = ByteArray(input.size) { index ->
        val x = index % width
        val y = index / width
        var sum = 0
        var count = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val xx = (x + dx).coerceIn(0, width - 1)
                val yy = (y + dy).coerceIn(0, height - 1)
                sum += input[yy * width + xx].toInt() and 0xff
                count++
            }
        }
        (sum / count).toByte()
    }

    private fun shiftRight(input: ByteArray, width: Int, amount: Int): ByteArray =
        ByteArray(input.size) { index ->
            val x = index % width
            val y = index / width
            input[y * width + (x - amount).mod(width)]
        }
}
