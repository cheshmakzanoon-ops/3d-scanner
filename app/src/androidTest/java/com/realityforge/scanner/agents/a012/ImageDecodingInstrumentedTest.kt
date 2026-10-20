package com.realityforge.scanner.agents.a012

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.imaging.AnalysisPixels
import com.realityforge.scanner.imaging.AndroidImageDecoder
import com.realityforge.scanner.imaging.DecodeRequest
import com.realityforge.scanner.imaging.ExifOrientation
import com.realityforge.scanner.imaging.ImageDecodeException
import com.realityforge.scanner.imaging.ImageDecodeFailure
import com.realityforge.scanner.imaging.ImageFormat
import com.realityforge.scanner.imaging.ImageDecodeWarning
import com.realityforge.scanner.imaging.OrientationEvidence
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageDecodingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val blockColors = intArrayOf(
        Color.rgb(240, 20, 20),
        Color.rgb(20, 220, 20),
        Color.rgb(20, 20, 240),
        Color.rgb(240, 220, 20),
        Color.rgb(230, 20, 220),
        Color.rgb(20, 220, 220),
    )

    @Test
    fun allEightExifOrientationsProduceGoldenPixelsAndPreserveOriginalBytes() {
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
        val folder = File(context.cacheDir, "agent-012-orientation").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for (orientation in ExifOrientation.entries) {
                val file = File(folder, "orientation-${orientation.exifValue}.jpg")
                writeSixBlockImage(file, Bitmap.CompressFormat.JPEG)
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.exifValue.toString())
                    saveAttributes()
                }
                val hashBefore = sha256(file)
                val owner = AndroidImageDecoder.decodeBlocking(DecodeRequest(file, maxEdge = 512))
                val borrowed = owner.borrowBitmap()
                try {
                    val golden = checkNotNull(expected[orientation])
                    assertEquals(golden.first().size * 80, borrowed.width)
                    assertEquals(golden.size * 80, borrowed.height)
                    for (row in golden.indices) {
                        for (column in golden[row].indices) {
                            val actual = borrowed.getPixel(column * 80 + 40, row * 80 + 40)
                            assertColorNear(blockColors[golden[row][column]], actual, 45)
                        }
                    }
                    assertEquals(orientation, owner.info.orientation)
                } finally {
                    owner.close()
                }
                assertTrue("Owned bitmap was not recycled for $orientation", borrowed.isRecycled)
                assertEquals("Original bytes changed for $orientation", hashBefore, sha256(file))
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun jpegPngAndWebpDecodeToSrgbArgbWithExplicitAlphaAnalysis() {
        val folder = File(context.cacheDir, "agent-012-formats").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            @Suppress("DEPRECATION")
            val cases = listOf(
                Triple("fixture.jpg", Bitmap.CompressFormat.JPEG, ImageFormat.JPEG),
                Triple("fixture.png", Bitmap.CompressFormat.PNG, ImageFormat.PNG),
                Triple("fixture.webp", Bitmap.CompressFormat.WEBP, ImageFormat.WEBP),
            )
            for ((name, compression, expectedFormat) in cases) {
                val file = File(folder, name)
                writeSixBlockImage(file, compression)
                AndroidImageDecoder.decodeBlocking(DecodeRequest(file, maxEdge = 512)).use { owner ->
                    assertEquals(expectedFormat, owner.info.source.format)
                    owner.withBitmap { bitmap ->
                        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                        assertTrue(bitmap.colorSpace?.isSrgb == true)
                    }
                }
            }

            val alpha = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
            alpha.setPixel(0, 0, Color.argb(0, 255, 255, 255))
            alpha.setPixel(1, 0, Color.WHITE)
            try {
                val gray = AnalysisPixels.grayscale(alpha)
                assertEquals(0, gray[0].toInt() and 0xff)
                assertEquals(255, gray[1].toInt() and 0xff)
            } finally {
                alpha.recycle()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun corruptUnsupportedAndEncodedLimitFailuresAreTyped() {
        val folder = File(context.cacheDir, "agent-012-failures").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val gif = File(folder, "unsupported.gif")
            gif.writeBytes(Base64.decode("R0lGODlhAwADAIABAP///wAAACwAAAAAAwADAAACAkQBADs=", Base64.DEFAULT))
            assertFailure(ImageDecodeFailure.UNSUPPORTED_FORMAT) {
                AndroidImageDecoder.decodeBlocking(DecodeRequest(gif))
            }

            val corrupt = File(folder, "truncated.jpg")
            corrupt.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 32, 1, 2))
            val corruptFailure = runCatching {
                AndroidImageDecoder.decodeBlocking(DecodeRequest(corrupt))
            }.exceptionOrNull() as ImageDecodeException
            assertTrue(
                corruptFailure.failure == ImageDecodeFailure.CORRUPT_IMAGE ||
                    corruptFailure.failure == ImageDecodeFailure.DECODE_FAILED,
            )

            val jpeg = File(folder, "limited.jpg")
            writeSixBlockImage(jpeg, Bitmap.CompressFormat.JPEG)
            assertFailure(ImageDecodeFailure.ENCODED_SIZE_LIMIT) {
                AndroidImageDecoder.decodeBlocking(DecodeRequest(jpeg, maxEncodedBytes = jpeg.length() - 1))
            }

            AndroidImageDecoder.decodeBlocking(DecodeRequest(jpeg)).use { owner ->
                assertEquals(OrientationEvidence.ABSENT, owner.info.orientationEvidence)
                assertTrue(ImageDecodeWarning.EXIF_ORIENTATION_ABSENT in owner.info.warnings)
            }
            val malformedExif = File(folder, "malformed-orientation.jpg")
            jpeg.copyTo(malformedExif)
            ExifInterface(malformedExif).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED.toString())
                saveAttributes()
            }
            AndroidImageDecoder.decodeBlocking(DecodeRequest(malformedExif)).use { owner ->
                assertEquals(ExifOrientation.NORMAL, owner.info.orientation)
                assertEquals(OrientationEvidence.MALFORMED, owner.info.orientationEvidence)
                assertTrue(ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED in owner.info.warnings)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun largeDecodeAndRepeatedOwnershipStayInsideDeclaredBudget() {
        val folder = File(context.cacheDir, "agent-012-budget").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val large = File(folder, "large.jpg")
            val source = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.rgb(90, 130, 170))
            try {
                FileOutputStream(large).use { output ->
                    assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 92, output))
                    output.fd.sync()
                }
            } finally {
                source.recycle()
            }
            val budget = 24L * 1024 * 1024
            AndroidImageDecoder.decodeBlocking(
                DecodeRequest(large, maxEdge = 1_000, maxPeakBytes = budget),
            ).use { owner ->
                assertTrue(maxOf(owner.width, owner.height) <= 1_000)
                assertTrue(owner.info.estimatedPeakBytes <= budget)
                assertTrue(owner.info.measuredPeakBytes <= budget)
            }

            val small = File(folder, "repeat.jpg")
            writeSixBlockImage(small, Bitmap.CompressFormat.JPEG)
            repeat(50) {
                val owner = AndroidImageDecoder.decodeBlocking(DecodeRequest(small, maxEdge = 512))
                val borrowed = owner.borrowBitmap()
                assertFalse(borrowed.isRecycled)
                owner.close()
                owner.close()
                assertTrue("Iteration $it retained a live bitmap", borrowed.isRecycled)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun writeSixBlockImage(file: File, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL }
        try {
            for (row in 0 until 2) {
                for (column in 0 until 3) {
                    paint.color = blockColors[row * 3 + column]
                    canvas.drawRect(
                        (column * 80).toFloat(),
                        (row * 80).toFloat(),
                        ((column + 1) * 80).toFloat(),
                        ((row + 1) * 80).toFloat(),
                        paint,
                    )
                }
            }
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(format, 100, output))
                output.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    private fun assertFailure(expected: ImageDecodeFailure, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull() as ImageDecodeException
        assertEquals(expected, failure.failure)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
