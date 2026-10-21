package com.realityforge.scanner.capture.analysis

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

fun interface CameraXFrameReader {
    @Throws(UnsupportedCaptureFrameException::class, InvalidCaptureFrameException::class)
    fun read(image: ImageProxy, budget: CaptureAnalysisBudget): LumaFrame
}

class UnsupportedCaptureFrameException(message: String) : Exception(message)
class InvalidCaptureFrameException(message: String) : Exception(message)

/**
 * Reads only CameraX's Y plane, respects crop/row/pixel stride, rotates to an upright bounded
 * luma frame and never retains an ImageProxy or plane buffer after returning.
 */
object CameraXLumaFrameReader : CameraXFrameReader {
    override fun read(image: ImageProxy, budget: CaptureAnalysisBudget): LumaFrame {
        if (image.format != ImageFormat.YUV_420_888) {
            throw UnsupportedCaptureFrameException("Live analysis requires YUV_420_888 frames.")
        }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation !in CaptureQualityObservation.VALID_ROTATIONS) {
            throw UnsupportedCaptureFrameException("Unsupported CameraX rotation: $rotation")
        }
        val crop = image.cropRect
        if (crop.left < 0 || crop.top < 0 || crop.right > image.width || crop.bottom > image.height ||
            crop.width() < 3 || crop.height() < 3
        ) {
            throw InvalidCaptureFrameException("Invalid YUV crop rectangle.")
        }
        val plane = image.planes.firstOrNull()
            ?: throw InvalidCaptureFrameException("YUV frame has no luma plane.")
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) {
            throw InvalidCaptureFrameException("Invalid luma plane strides.")
        }
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        val limit = buffer.limit()
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        val uprightWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val uprightHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val scale = min(1.0, budget.maxAnalysisEdge.toDouble() / maxOf(uprightWidth, uprightHeight))
        val outputWidth = floor(uprightWidth * scale).toInt().coerceAtLeast(3)
        val outputHeight = floor(uprightHeight * scale).toInt().coerceAtLeast(3)
        val requiredBytes = outputWidth.toLong() * outputHeight
        if (requiredBytes > budget.maxRetainedFrameBytes || requiredBytes > Int.MAX_VALUE) {
            throw UnsupportedCaptureFrameException("Configured analysis frame exceeds its memory budget.")
        }
        val output = ByteArray(requiredBytes.toInt())
        for (outputY in 0 until outputHeight) {
            val uprightY = sampleCoordinate(outputY, outputHeight, uprightHeight)
            for (outputX in 0 until outputWidth) {
                val uprightX = sampleCoordinate(outputX, outputWidth, uprightWidth)
                val (sourceX, sourceY) = when (rotation) {
                    0 -> uprightX to uprightY
                    90 -> uprightY to (sourceHeight - 1 - uprightX)
                    180 -> (sourceWidth - 1 - uprightX) to (sourceHeight - 1 - uprightY)
                    270 -> (sourceWidth - 1 - uprightY) to uprightX
                    else -> error("Rotation was validated above.")
                }
                val absoluteX = crop.left + sourceX
                val absoluteY = crop.top + sourceY
                val bufferIndex = base.toLong() + absoluteY.toLong() * rowStride +
                    absoluteX.toLong() * pixelStride
                if (bufferIndex !in base.toLong() until limit.toLong()) {
                    throw InvalidCaptureFrameException("Luma plane is shorter than its declared layout.")
                }
                output[outputY * outputWidth + outputX] = buffer.get(bufferIndex.toInt())
            }
        }
        return LumaFrame(
            width = outputWidth,
            height = outputHeight,
            pixels = output,
            timestampNanos = image.imageInfo.timestamp,
            sourceRotationDegrees = rotation,
        )
    }

    private fun sampleCoordinate(index: Int, outputSize: Int, sourceSize: Int): Int =
        (((index + 0.5) * sourceSize / outputSize) - 0.5)
            .roundToInt()
            .coerceIn(0, sourceSize - 1)
}

/**
 * CameraX adapter for [CaptureQualityStreamAnalyzer]. Every call closes its ImageProxy exactly
 * once in a finally block, including dropped, unsupported, cancelled, failed and disposed paths.
 * Exceptions are reported as typed failures and never escape to disable normal camera capture.
 */
class CameraXCaptureQualityAnalyzer(
    sessionId: CaptureAnalysisSessionId,
    private val budgetProvider: CaptureAnalysisBudgetProvider = StaticCaptureAnalysisBudgetProvider(),
    thresholdProvider: CaptureQualityThresholdProvider = StaticCaptureQualityThresholdProvider(),
    private val frameReader: CameraXFrameReader = CameraXLumaFrameReader,
    private val onObservation: (CaptureQualityObservation) -> Unit,
    private val onFailure: (CaptureAnalysisFailure) -> Unit = {},
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val streamAnalyzer = CaptureQualityStreamAnalyzer(
        sessionId = sessionId,
        budgetProvider = budgetProvider,
        thresholdProvider = thresholdProvider,
    )

    val sessionId: CaptureAnalysisSessionId get() = streamAnalyzer.sessionId

    override fun analyze(image: ImageProxy) {
        var timestamp: Long? = runCatching { image.imageInfo.timestamp }.getOrNull()
        try {
            if (closed.get()) return
            if (!streamAnalyzer.shouldRead(requireNotNull(timestamp))) return
            val frame = frameReader.read(image, budgetProvider.currentBudget())
            timestamp = frame.timestampNanos
            val observation = streamAnalyzer.analyze(frame) ?: return
            publishObservation(observation)
        } catch (exception: UnsupportedCaptureFrameException) {
            publishFailure(timestamp, CaptureAnalysisFailureCode.UNSUPPORTED_FRAME, exception)
        } catch (exception: InvalidCaptureFrameException) {
            publishFailure(timestamp, CaptureAnalysisFailureCode.INVALID_FRAME, exception)
        } catch (exception: CancellationException) {
            publishFailure(timestamp, CaptureAnalysisFailureCode.CANCELLED, exception)
        } catch (exception: Exception) {
            publishFailure(timestamp, CaptureAnalysisFailureCode.ANALYZER_FAILURE, exception)
        } finally {
            try {
                image.close()
            } catch (exception: Exception) {
                publishFailure(timestamp, CaptureAnalysisFailureCode.ANALYZER_FAILURE, exception)
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            streamAnalyzer.close()
        }
    }

    fun isClosed(): Boolean = closed.get()

    fun stateSnapshot(): CaptureAnalysisStateSnapshot = streamAnalyzer.stateSnapshot()

    private fun publishObservation(observation: CaptureQualityObservation) {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            try {
                onObservation(observation)
            } catch (exception: Exception) {
                publishFailureLocked(
                    observation.frameTimestampNanos,
                    CaptureAnalysisFailureCode.OBSERVER_FAILURE,
                    exception,
                )
            }
        }
    }

    private fun publishFailure(
        timestampNanos: Long?,
        code: CaptureAnalysisFailureCode,
        exception: Exception,
    ) {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            publishFailureLocked(timestampNanos, code, exception)
        }
    }

    private fun publishFailureLocked(
        timestampNanos: Long?,
        code: CaptureAnalysisFailureCode,
        exception: Exception,
    ) {
        val detail = (exception.message ?: exception::class.java.simpleName)
            .ifBlank { code.name }
            .take(240)
        try {
            onFailure(
                CaptureAnalysisFailure(
                    sessionId = sessionId,
                    frameTimestampNanos = timestampNanos,
                    code = code,
                    detail = detail,
                ),
            )
        } catch (_: Exception) {
            // A diagnostic observer cannot be allowed to break CameraX or camera capture.
        }
    }
}

/** Factory only; Agent 014 retains ownership of lifecycle binding and camera-screen rendering. */
object CaptureQualityImageAnalysis {
    fun create(
        executor: Executor,
        targetRotation: Int,
        analyzer: CameraXCaptureQualityAnalyzer,
    ): ImageAnalysis = ImageAnalysis.Builder()
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetRotation(targetRotation)
        .build()
        .also { it.setAnalyzer(executor, analyzer) }
}
