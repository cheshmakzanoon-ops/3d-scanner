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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                    assertEquals(OrientationEvidence.PRESENT, owner.info.orientationEvidence)
                    assertEquals(orientation.exifValue, rawJpegOrientation(file))
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

            // Independently inspect JPEG/TIFF bytes: this fixture has no stored
            // orientation, although AndroidX synthesizes getAttribute() == "0".
            assertEquals(null, rawJpegOrientation(jpeg))
            val absentExif = ExifInterface(jpeg)
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED.toString(), absentExif.getAttribute(ExifInterface.TAG_ORIENTATION))
            assertTrue(checkNotNull(absentExif.getAttributeRange(ExifInterface.TAG_ORIENTATION))[0] < 0)
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
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED, rawJpegOrientation(malformedExif))
            assertTrue(checkNotNull(ExifInterface(malformedExif).getAttributeRange(ExifInterface.TAG_ORIENTATION))[0] >= 0)
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
    fun physicallyPresentWrongTypeOrientationIsMalformedNotAbsentAndKeepsPixels() {
        val folder = File(context.cacheDir, "agent-012-wrong-type").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val original = File(folder, "original.jpg")
            writeSixBlockImage(original, Bitmap.CompressFormat.JPEG)
            val originalHash = sha256(original)
            assertEquals(null, rawJpegOrientation(original))
            for (little in listOf(true, false)) {
                // Independently construct APP1/TIFF bytes, never use AndroidX's
                // writer: it would normalize the deliberately incompatible type.
                val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val tiff = ByteBuffer.allocate(26).order(order).apply {
                    put(if (little) byteArrayOf(73, 73) else byteArrayOf(77, 77))
                    putShort(42); putInt(8); putShort(1)
                    putShort(0x0112); putShort(2) // orientation stored as ASCII, not SHORT
                    putInt(2); put(byteArrayOf(57, 0, 0, 0)); putInt(0)
                }.array()
                // Independent field oracle proves the physical tag is present.
                val oracle = ByteBuffer.wrap(tiff).order(order)
                assertEquals(0x0112, oracle.getShort(10).toInt())
                assertEquals(2, oracle.getShort(12).toInt())
                assertEquals(2, oracle.getInt(14))
                val payload = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff
                val length = payload.size + 2
                val app1 = byteArrayOf(-1, -31, (length ushr 8).toByte(), length.toByte()) + payload
                check(original.length() in 4..1_048_576)
                val encoded = original.readBytes()
                check(encoded[0] == (-1).toByte() && encoded[1] == (-40).toByte())
                val malformed = File(folder, "ascii-$little.jpg")
                malformed.writeBytes(encoded.copyOfRange(0, 2) + app1 + encoded.copyOfRange(2, encoded.size))
                val hashBefore = sha256(malformed)
                // ExifInterface discards the incompatible field and inserts0;
                // the decoder must not mistake this for physical absence.
                val exif = ExifInterface(malformed)
                assertEquals("0", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
                val range = exif.getAttributeRange(ExifInterface.TAG_ORIENTATION)
                assertTrue(range == null || range[0] < 0)
                val owner = AndroidImageDecoder.decodeBlocking(DecodeRequest(malformed, maxEdge = 512))
                val bitmap = owner.borrowBitmap()
                try {
                    assertEquals(OrientationEvidence.MALFORMED, owner.info.orientationEvidence)
                    assertEquals(ExifOrientation.NORMAL, owner.info.orientation)
                    assertTrue(ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED in owner.info.warnings)
                    assertFalse(ImageDecodeWarning.EXIF_ORIENTATION_ABSENT in owner.info.warnings)
                    assertEquals(240, bitmap.width)
                    assertEquals(160, bitmap.height)
                    for (row in 0..1) for (column in 0..2) {
                        assertColorNear(blockColors[row * 3 + column], bitmap.getPixel(column * 80 + 40, row * 80 + 40), 45)
                    }
                } finally {
                    owner.close()
                }
                assertTrue(bitmap.isRecycled)
                assertEquals(hashBefore, sha256(malformed))
                assertEquals(originalHash, sha256(original))
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

    /** Independent bounded JPEG APP1/TIFF inspection; never consults AndroidX attributes. */
    private fun rawJpegOrientation(file: File): Int? {
        check(file.length() in 4..1_048_576) { "Controlled JPEG fixture is unexpectedly large" }
        val bytes = file.readBytes()
        fun byte(offset: Int): Int = bytes[offset].toInt() and 0xff
        fun ushort(offset: Int, little: Boolean): Int {
            check(offset >= 0 && offset + 2 <= bytes.size)
            return if (little) byte(offset) or (byte(offset + 1) shl 8)
            else (byte(offset) shl 8) or byte(offset + 1)
        }
        fun uint(offset: Int, little: Boolean): Long {
            check(offset >= 0 && offset + 4 <= bytes.size)
            var value = 0L
            for (index in 0..3) {
                val shift = if (little) index * 8 else (3 - index) * 8
                value = value or (byte(offset + index).toLong() shl shift)
            }
            return value
        }
        check(byte(0) == 0xff && byte(1) == 0xd8)
        var offset = 2
        while (offset + 4 <= bytes.size) {
            check(byte(offset++) == 0xff)
            while (byte(offset) == 0xff) offset++
            val marker = byte(offset++)
            if (marker == 0xda || marker == 0xd9) return null
            val length = ushort(offset, false)
            check(length >= 2 && offset + length <= bytes.size)
            val start = offset + 2
            if (marker == 0xe1 && length >= 16 &&
                bytes.copyOfRange(start, start + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                val tiff = start + 6
                val little = byte(tiff) == 0x49
                check((little && byte(tiff + 1) == 0x49) ||
                    (!little && byte(tiff) == 0x4d && byte(tiff + 1) == 0x4d))
                check(ushort(tiff + 2, little) == 42)
                val ifdOffset = uint(tiff + 4, little)
                check(ifdOffset <= length - 8L)
                val directory = tiff + ifdOffset.toInt()
                val count = ushort(directory, little)
                check(directory + 2L + count * 12L <= offset + length)
                for (index in 0 until count) {
                    val entry = directory + 2 + index * 12
                    if (ushort(entry, little) == 0x0112) {
                        check(uint(entry + 4, little) == 1L)
                        return when (ushort(entry + 2, little)) {
                            3 -> ushort(entry + 8, little)
                            4 -> uint(entry + 8, little).also { check(it <= Int.MAX_VALUE) }.toInt()
                            else -> error("Unexpected raw orientation storage type")
                        }
                    }
                }
            }
            offset += length
        }
        return null
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
