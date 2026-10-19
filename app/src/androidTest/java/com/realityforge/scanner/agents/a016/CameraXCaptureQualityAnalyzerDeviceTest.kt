package com.realityforge.scanner.agents.a016

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.capture.analysis.CameraXCaptureQualityAnalyzer
import com.realityforge.scanner.capture.analysis.CameraXFrameReader
import com.realityforge.scanner.capture.analysis.CameraXLumaFrameReader
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudget
import com.realityforge.scanner.capture.analysis.CaptureAnalysisFailureCode
import com.realityforge.scanner.capture.analysis.CaptureAnalysisSessionId
import com.realityforge.scanner.capture.analysis.CaptureQualityObservation
import com.realityforge.scanner.capture.analysis.LumaFrame
import com.realityforge.scanner.capture.analysis.StaticCaptureAnalysisBudgetProvider
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraXCaptureQualityAnalyzerDeviceTest {
    private val budget = CaptureAnalysisBudget(
        maxAnalysisEdge = 128,
        minFrameIntervalNanos = 100_000_000L,
        maxRetainedFrameBytes = 128 * 128,
        actionConfirmationFrames = 1,
        readyConfirmationFrames = 1,
    )

    @Test
    fun yuvReaderAppliesRotationAndDeclaredPlaneLayout() {
        val image = fakeImage(
            width = 4,
            height = 3,
            timestampNanos = 1_000_000_000L,
            rotationDegrees = 90,
            luma = ByteArray(12) { (it + 1).toByte() },
        )
        val frame = CameraXLumaFrameReader.read(image.proxy, budget)

        assertEquals(3, frame.width)
        assertEquals(4, frame.height)
        assertEquals(90, frame.sourceRotationDegrees)
        assertEquals(
            listOf(9, 5, 1, 10, 6, 2, 11, 7, 3, 12, 8, 4),
            frame.pixels.map { it.toInt() and 0xff },
        )
        // The reader borrows the proxy. The Analyzer, not the reader, owns closure.
        assertEquals(0, image.closeCount.get())
    }

    @Test
    fun successCadenceRejectionAndDisposedPathsEachCloseExactlyOnce() {
        val observations = mutableListOf<CaptureQualityObservation>()
        val analyzer = analyzer(onObservation = observations::add)
        val first = patternedImage(1_000_000_000L)
        val dropped = patternedImage(1_050_000_000L)

        analyzer.analyze(first.proxy)
        analyzer.analyze(dropped.proxy)
        assertEquals(1, first.closeCount.get())
        assertEquals(1, dropped.closeCount.get())
        assertEquals(1, observations.size)

        analyzer.close()
        val disposed = patternedImage(1_200_000_000L)
        analyzer.analyze(disposed.proxy)
        assertEquals(1, disposed.closeCount.get())
        assertEquals(1, observations.size)
    }

    @Test
    fun unsupportedInvalidAndCancelledFramesCloseExactlyOnceWithoutThrowing() {
        val failures = mutableListOf<CaptureAnalysisFailureCode>()
        val unsupportedAnalyzer = analyzer(onFailure = { failures += it.code })
        val unsupported = fakeImage(
            width = 64,
            height = 48,
            timestampNanos = 1_000_000_000L,
            format = ImageFormat.JPEG,
            luma = ByteArray(64 * 48),
        )
        unsupportedAnalyzer.analyze(unsupported.proxy)
        assertEquals(1, unsupported.closeCount.get())
        assertEquals(CaptureAnalysisFailureCode.UNSUPPORTED_FRAME, failures.last())

        val invalid = fakeImage(
            width = 64,
            height = 48,
            timestampNanos = 1_200_000_000L,
            luma = ByteArray(7),
        )
        unsupportedAnalyzer.analyze(invalid.proxy)
        assertEquals(1, invalid.closeCount.get())
        assertEquals(CaptureAnalysisFailureCode.INVALID_FRAME, failures.last())

        val cancelledAnalyzer = analyzer(
            frameReader = CameraXFrameReader { _, _ -> throw CancellationException("cancelled") },
            onFailure = { failures += it.code },
        )
        val cancelled = patternedImage(1_400_000_000L)
        cancelledAnalyzer.analyze(cancelled.proxy)
        assertEquals(1, cancelled.closeCount.get())
        assertEquals(CaptureAnalysisFailureCode.CANCELLED, failures.last())
    }

    @Test
    fun observerExceptionsAreContainedAndStillCloseTheFrame() {
        val failures = mutableListOf<CaptureAnalysisFailureCode>()
        val analyzer = analyzer(
            onObservation = { throw IllegalStateException("UI disposed") },
            onFailure = { failures += it.code },
        )
        val image = patternedImage(1_000_000_000L)

        analyzer.analyze(image.proxy)

        assertEquals(1, image.closeCount.get())
        assertEquals(listOf(CaptureAnalysisFailureCode.OBSERVER_FAILURE), failures)
    }

    private fun analyzer(
        frameReader: CameraXFrameReader = CameraXLumaFrameReader,
        onObservation: (CaptureQualityObservation) -> Unit = {},
        onFailure: (com.realityforge.scanner.capture.analysis.CaptureAnalysisFailure) -> Unit = {},
    ) = CameraXCaptureQualityAnalyzer(
        sessionId = CaptureAnalysisSessionId("device-session-a016"),
        budgetProvider = StaticCaptureAnalysisBudgetProvider(budget),
        frameReader = frameReader,
        onObservation = onObservation,
        onFailure = onFailure,
    )

    private fun patternedImage(timestampNanos: Long): FakeImage {
        val width = 64
        val height = 48
        val pixels = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (((x / 4) + (y / 4)) % 2 == 0) 32 else 224.toByte()
        }
        return fakeImage(width, height, timestampNanos, luma = pixels)
    }

    private fun fakeImage(
        width: Int,
        height: Int,
        timestampNanos: Long,
        rotationDegrees: Int = 0,
        format: Int = ImageFormat.YUV_420_888,
        luma: ByteArray,
        rowStride: Int = width,
        pixelStride: Int = 1,
    ): FakeImage {
        val closeCount = AtomicInteger()
        val plane = proxy(ImageProxy.PlaneProxy::class.java) { method, _ ->
            when (method.name) {
                "getBuffer" -> ByteBuffer.wrap(luma).asReadOnlyBuffer()
                "getRowStride" -> rowStride
                "getPixelStride" -> pixelStride
                else -> defaultValue(method)
            }
        }
        val imageInfo = proxy(ImageInfo::class.java) { method, _ ->
            when (method.name) {
                "getTimestamp" -> timestampNanos
                "getRotationDegrees" -> rotationDegrees
                "getSensorToBufferTransformMatrix" -> Matrix()
                else -> defaultValue(method)
            }
        }
        var crop = Rect(0, 0, width, height)
        val imageProxy = proxy(ImageProxy::class.java) { method, args ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getFormat" -> format
                "getCropRect" -> Rect(crop)
                "setCropRect" -> {
                    crop = Rect(args!![0] as Rect)
                    null
                }
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> imageInfo
                "getImage" -> null
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                else -> defaultValue(method)
            }
        }
        return FakeImage(imageProxy, closeCount)
    }

    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<out Any?>?) -> Any?,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Agent016Fake${type.simpleName}"
                else -> handler(method, args)
            }
        } as T
    }

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private data class FakeImage(
        val proxy: ImageProxy,
        val closeCount: AtomicInteger,
    )
}
