package com.realityforge.scanner.agents.a012

import com.realityforge.scanner.imaging.AnalysisColorContract
import com.realityforge.scanner.imaging.DecodePlanner
import com.realityforge.scanner.imaging.DecodeRequest
import com.realityforge.scanner.imaging.ExifOrientation
import com.realityforge.scanner.imaging.ImageBounds
import com.realityforge.scanner.imaging.ImageDecodeException
import com.realityforge.scanner.imaging.ImageDecodeFailure
import com.realityforge.scanner.imaging.ImageFormat
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContractsTest {
    private val source = arrayOf(
        intArrayOf(0, 1, 2),
        intArrayOf(3, 4, 5),
    )

    @Test
    fun allEightExifOrientationsHaveGoldenAsymmetricCoordinates() {
        val expected = mapOf(
            ExifOrientation.NORMAL to arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5)),
            ExifOrientation.FLIP_HORIZONTAL to arrayOf(intArrayOf(2, 1, 0), intArrayOf(5, 4, 3)),
            ExifOrientation.ROTATE_180 to arrayOf(intArrayOf(5, 4, 3), intArrayOf(2, 1, 0)),
            ExifOrientation.FLIP_VERTICAL to arrayOf(intArrayOf(3, 4, 5), intArrayOf(0, 1, 2)),
            ExifOrientation.TRANSPOSE to arrayOf(intArrayOf(0, 3), intArrayOf(1, 4), intArrayOf(2, 5)),
            ExifOrientation.ROTATE_90_CW to arrayOf(intArrayOf(3, 0), intArrayOf(4, 1), intArrayOf(5, 2)),
            ExifOrientation.TRANSVERSE to arrayOf(intArrayOf(5, 2), intArrayOf(4, 1), intArrayOf(3, 0)),
            ExifOrientation.ROTATE_270_CW to arrayOf(intArrayOf(2, 5), intArrayOf(1, 4), intArrayOf(0, 3)),
        )

        for ((orientation, golden) in expected) {
            val (width, height) = orientation.outputDimensions(3, 2)
            assertEquals("$orientation width", golden.first().size, width)
            assertEquals("$orientation height", golden.size, height)
            val actual = Array(height) { y ->
                IntArray(width) { x ->
                    val coordinate = orientation.sourceCoordinate(x, y, 3, 2)
                    source[coordinate.y][coordinate.x]
                }
            }
            for (row in golden.indices) {
                assertArrayEquals("$orientation row $row", golden[row], actual[row])
            }
        }
    }

    @Test
    fun canvasRenderMatricesMatchGoldenPixelCentersForAllOrientations() {
        for (orientation in ExifOrientation.entries) {
            val (outputWidth, outputHeight) = orientation.outputDimensions(3, 2)
            val matrix = orientation.renderMatrixValues(3, 2, outputWidth, outputHeight)
            val determinant = matrix[0] * matrix[4] - matrix[1] * matrix[3]
            assertTrue("$orientation matrix must be invertible", kotlin.math.abs(determinant) > 0.5f)
            for (outputY in 0 until outputHeight) {
                for (outputX in 0 until outputWidth) {
                    val targetX = outputX + 0.5f - matrix[2]
                    val targetY = outputY + 0.5f - matrix[5]
                    val sourceX = (matrix[4] * targetX - matrix[1] * targetY) / determinant
                    val sourceY = (-matrix[3] * targetX + matrix[0] * targetY) / determinant
                    val expected = orientation.sourceCoordinate(outputX, outputY, 3, 2)
                    assertEquals("$orientation source x at $outputX,$outputY", expected.x + 0.5f, sourceX, 0.0001f)
                    assertEquals("$orientation source y at $outputX,$outputY", expected.y + 0.5f, sourceY, 0.0001f)
                }
            }
        }
    }

    @Test
    fun plannerBoundsDecodeAndReducesResolutionToHonorMemoryBudget() {
        val bounds = ImageBounds(12_000, 8_000, ImageFormat.JPEG, 12_000_000)
        val ordinary = DecodePlanner.plan(
            bounds,
            ExifOrientation.ROTATE_90_CW,
            DecodeRequest(File("fixture.jpg"), maxEdge = 1_600, maxPeakBytes = 64L * 1024 * 1024),
        )
        assertEquals(4, ordinary.sampleSize)
        assertEquals(1_067, ordinary.outputWidth)
        assertEquals(1_600, ordinary.outputHeight)
        assertTrue(ordinary.estimatedPeakBytes <= 64L * 1024 * 1024)

        val constrained = DecodePlanner.plan(
            bounds,
            ExifOrientation.ROTATE_90_CW,
            DecodeRequest(File("fixture.jpg"), maxEdge = 1_600, maxPeakBytes = 8L * 1024 * 1024),
        )
        assertTrue(constrained.sampleSize > ordinary.sampleSize)
        assertTrue(constrained.estimatedPeakBytes <= 8L * 1024 * 1024)
        assertTrue(maxOf(constrained.outputWidth, constrained.outputHeight) <= 1_600)
    }

    @Test
    fun plannerPreservesOddAspectRatioWithinOneRoundedPixel() {
        val plan = DecodePlanner.plan(
            ImageBounds(9_997, 333, ImageFormat.PNG, 500_000),
            ExifOrientation.NORMAL,
            DecodeRequest(File("odd.png"), maxEdge = 1_601),
        )
        assertEquals(1_601, plan.outputWidth)
        val expectedHeight = 333.0 / 9_997.0 * 1_601.0
        assertTrue(kotlin.math.abs(plan.outputHeight - expectedHeight) <= 1.0)
    }

    @Test
    fun plannerPreservesThreePixelMinimumForExtremeAspectRatios() {
        assertEquals(32 to 3, DecodePlanner.fitWithin(32_768, 3, 32))
        assertEquals(3 to 32, DecodePlanner.fitWithin(3, 32_768, 32))
        assertEquals(3 to 3, DecodePlanner.fitWithin(1, 2, 32))
    }

    @Test
    fun unsupportedFormatAndOversizedSourceUseTypedFailures() {
        val unsupported = runCatching {
            DecodePlanner.validateBounds(
                ImageBounds(640, 480, ImageFormat.WEBP, 10_000),
                DecodeRequest(File("fixture.webp"), allowedFormats = setOf(ImageFormat.JPEG)),
            )
        }.exceptionOrNull() as ImageDecodeException
        assertEquals(ImageDecodeFailure.UNSUPPORTED_FORMAT, unsupported.failure)

        val oversized = runCatching {
            DecodePlanner.validateBounds(
                ImageBounds(20_000, 20_000, ImageFormat.JPEG, 10_000),
                DecodeRequest(File("huge.jpg")),
            )
        }.exceptionOrNull() as ImageDecodeException
        assertEquals(ImageDecodeFailure.SOURCE_PIXEL_LIMIT, oversized.failure)
    }

    @Test
    fun analysisLumaDefinesAlphaAndColorBehavior() {
        assertEquals(255, AnalysisColorContract.lumaFromArgb(0xffffffff.toInt()))
        assertEquals(0, AnalysisColorContract.lumaFromArgb(0x00ffffff))
        assertEquals(128, AnalysisColorContract.lumaFromArgb(0x80ffffff.toInt()))
        // These values intentionally match the pre-Agent-012 floor-based 77/150/29 pipeline.
        assertEquals(76, AnalysisColorContract.lumaFromArgb(0xffff0000.toInt()))
        assertEquals(149, AnalysisColorContract.lumaFromArgb(0xff00ff00.toInt()))
        assertEquals(28, AnalysisColorContract.lumaFromArgb(0xff0000ff.toInt()))
    }

    @Test
    fun mimeMappingIsExplicitAndDoesNotTrustExtensions() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromMimeType("image/jpeg"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromMimeType("IMAGE/PNG"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromMimeType("image/webp"))
        assertEquals(ImageFormat.HEIF_HEIC, ImageFormat.fromMimeType("image/heic"))
        assertEquals(null, ImageFormat.fromMimeType("image/gif"))
    }
}
