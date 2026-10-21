package com.realityforge.scanner.hostintegration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.realityforge.scanner.imaging.*
import java.io.File
import java.security.MessageDigest
import java.io.DataInputStream
import java.io.BufferedInputStream
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HOST decoder integration: unchanged production decode with Robolectric software graphics.
 * External JDK ImageIO references are an independent oracle, never production decoder input.
 * No OpenCV/scanner JNI, shadows added by this test, pipeline, admission overrides or device claim.
 * Platform/linkage failures are failures, never assumptions/skips or synthetic successful results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeGraphicsDecoderHostProbeTest {
    @Test(timeout = 120_000) fun committedJpegPixelsRemainOpaqueSpatiallyCorrectAndBoundedAtAllPipelineEdges() {
        for ((name, digest) in FIXTURES) {
            val file = fixture(name)
            assertEquals(digest, sha(file))
            var primary: Throwable? = null
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                assertEquals("image/jpeg", options.outMimeType)
                assertEquals(800, options.outWidth)
                assertEquals(600, options.outHeight)
                    for ((edge, size) in listOf(1600 to (800 to 600), 256 to (256 to 192), 512 to (512 to 384))) {
                        val request = DecodeRequest(file, maxEdge = edge)
                        val bounds = AndroidImageDecoder.inspect(request)
                        assertEquals(ImageFormat.JPEG, bounds.format)
                        assertEquals(800, bounds.width)
                        assertEquals(600, bounds.height)
                        val owner = AndroidImageDecoder.decodeBlocking(request)
                        var borrowed: Bitmap? = null
                        try {
                            borrowed = owner.borrowBitmap()
                            assertEquals(size.first, owner.width)
                            assertEquals(size.second, owner.height)
                            assertEquals(if (edge == 256) 2 else 1, owner.info.sampleSize)
                            assertEquals(ExifOrientation.NORMAL, owner.info.orientation)
                            assertTrue(owner.info.measuredPeakBytes in 1..request.maxPeakBytes)
                            owner.withBitmap { bitmap ->
                                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                                assertTrue(bitmap.colorSpace?.isSrgb == true)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertMeaningfulPixels(pixels)
                                assertSpatialAgreement(name, digest, pixels, bitmap.width, bitmap.height)
                            }
                        } finally { owner.close() }
                        assertTrue(owner.isClosed)
                        assertTrue(requireNotNull(borrowed).isRecycled)
                    }
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { assertEquals("Production decode changed original bytes", digest, sha(file)) }
                catch (integrity: Throwable) {
                    if (primary == null) throw integrity
                    if (primary !== integrity) primary.addSuppressed(integrity)
                }
            }
        }
    }

    @Test(timeout = 120_000) fun exifRotationOfPrivateCopyMovesActualPixelsWithoutRewritingSource() {
        val source = fixture("view-01.jpg")
        val expectedHash = FIXTURES.getValue(source.name)
        assertEquals(expectedHash, sha(source))
        val app: Application = RuntimeEnvironment.getApplication()
        val copy = File.createTempFile("decoder-exif-probe-", ".jpg", app.cacheDir)
        var primary: Throwable? = null
        try {
            source.copyTo(copy, overwrite = true)
            ExifInterface(copy).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val copyHash = sha(copy)
            AndroidImageDecoder.decodeBlocking(DecodeRequest(copy, maxEdge = 1600, applyExifOrientation = false)).use { raw ->
                AndroidImageDecoder.decodeBlocking(DecodeRequest(copy, maxEdge = 1600)).use { upright ->
                    assertEquals(800, raw.width); assertEquals(600, raw.height)
                    assertEquals(600, upright.width); assertEquals(800, upright.height)
                    assertEquals(ExifOrientation.ROTATE_90_CW, upright.info.orientation)
                    assertEquals(OrientationEvidence.PRESENT, upright.info.orientationEvidence)
                    raw.withBitmap { before -> upright.withBitmap { after ->
                        // Independent 90-degree coordinate equation, not the production transform helper.
                        for (y in 20 until after.height step 71) for (x in 20 until after.width step 53) {
                            assertEquals("Rotated pixel ($x,$y)", before.getPixel(y, before.height - 1 - x), after.getPixel(x, y))
                        }
                    } }
                }
            }
            assertEquals("Decoder changed EXIF copy", copyHash, sha(copy))
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            for (action in listOf<() -> Unit>(
                { assertEquals("Probe changed committed source", expectedHash, sha(source)) },
                { check(!copy.exists() || copy.delete()) },
            )) try { action() } catch (cleanup: Throwable) {
                val first = primary
                if (first == null) primary = cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
            }
            primary?.let { throw it }
        }
    }

    private fun fixture(name: String): File {
        val directory = requireNotNull(System.getProperty("rf.decoder.fixtureDir")) {
            "Explicit verified fixture directory is required for this HOST-only test"
        }
        return File(directory, name).also { require(it.isFile) }
    }

    private fun assertMeaningfulPixels(pixels: IntArray) {
        assertTrue("JPEG must decode opaque pixels", pixels.all { it ushr 24 == 255 })
        val unique = HashSet<Int>()
        var sum = 0.0
        var squares = 0.0
        for (pixel in pixels) {
            if (unique.size <= 128) unique.add(pixel)
            val luma = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) / 256.0
            sum += luma; squares += luma * luma
        }
        assertTrue("Raster is blank or collapsed", unique.size > 128)
        val mean = sum / pixels.size
        assertTrue("Raster has no meaningful contrast", squares / pixels.size - mean * mean > 25.0)
    }

    private fun assertSpatialAgreement(name: String, sourceHash: String, actual: IntArray, width: Int, height: Int) {
        val directory = File(requireNotNull(System.getProperty("rf.decoder.oracleDir")))
        val manifest = File(directory, "SHA256SUMS")
        val manifestHash = requireNotNull(System.getProperty("rf.decoder.oracleManifestSha256"))
        require(manifestHash.matches(Regex("[0-9a-f]{64}")))
        require(manifest.isFile && manifest.length() in 1..4096)
        assertEquals("Independent oracle manifest changed", manifestHash, sha(manifest))
        val referenceName = "$name.${width}x${height}.rgb"
        val lines = manifest.readLines()
        assertEquals("generatorSha256=" + requireNotNull(System.getProperty("rf.decoder.oracleGeneratorSha256")), lines[0])
        require(lines[1].startsWith("javaRuntime=") && lines[1].length > 12)
        assertEquals("layout=RGB8-row-major", lines[2])
        val entries = lines.drop(3).map { it.split("  ") }
        require(entries.size == 9 && entries.all { it.size == 2 })
        require(entries.map { it[1] }.toSet().size == 9)
        require(directory.listFiles()?.map { it.name }?.toSet() == entries.map { it[1] }.toSet() + "SHA256SUMS")
        val expectedHash = entries.single { it[1] == referenceName }[0]
        val reference = File(directory, referenceName)
        require(reference.length() == 78L + width.toLong() * height * 3)
        assertEquals("Independent oracle bytes changed", expectedHash, sha(reference))
        var error = 0L
        DataInputStream(BufferedInputStream(reference.inputStream())).use { data ->
            assertEquals(0x52464f31, data.readInt())
            assertEquals(sourceHash, data.readUTF())
            assertEquals(width, data.readInt()); assertEquals(height, data.readInt())
            for (pixel in actual) {
                error += abs((pixel ushr 16 and 255) - data.readUnsignedByte())
                error += abs((pixel ushr 8 and 255) - data.readUnsignedByte())
                error += abs((pixel and 255) - data.readUnsignedByte())
            }
            assertEquals(-1, data.read())
        }
        // Unchanged fixed pre-execution threshold; oracle never supplies production pixels.
        val meanAbsoluteChannelError = error.toDouble() / (width.toLong() * height * 3)
        assertTrue("Spatial RGB error $meanAbsoluteChannelError exceeds fixed HOST oracle bound", meanAbsoluteChannelError <= 12.0)
    }

    companion object {
        private val FIXTURES = linkedMapOf(
            "view-01.jpg" to "66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c",
            "view-02.jpg" to "c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b",
            "view-03.jpg" to "a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f",
        )
        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
