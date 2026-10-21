package com.realityforge.scanner.capture.session

import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import com.realityforge.scanner.capture.analysis.CameraXCaptureQualityAnalyzer
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.analysis.CaptureAnalysisSessionId
import com.realityforge.scanner.capture.analysis.CaptureQualityImageAnalysis
import com.realityforge.scanner.capture.analysis.CaptureQualityObservation
import com.realityforge.scanner.capture.analysis.CaptureQualityThresholdProvider
import com.realityforge.scanner.capture.analysis.StaticCaptureQualityThresholdProvider
import com.realityforge.scanner.capture.analysis.StaticCaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.controls.Camera2CaptureResultObserver
import com.realityforge.scanner.capture.controls.CameraMeteringPointProvider
import com.realityforge.scanner.capture.controls.CameraOpticsController
import com.realityforge.scanner.capture.controls.CameraXCapabilityReader
import com.realityforge.scanner.capture.controls.CameraXOpticsBackend
import com.realityforge.scanner.capture.controls.CameraXOpticsInterop
import com.realityforge.scanner.capture.controls.CaptureSessionToken
import com.realityforge.scanner.capture.controls.CaptureOpticsContinuity
import com.realityforge.scanner.capture.metadata.CaptureOpticsMetadataAdapter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One CameraX binding owns exactly one analyzer executor, observer and optical controller. */
class BoundCaptureFeatures(
    private val identity: CaptureSessionIdentity,
    cameraInfo: CameraInfo,
    private val previewView: PreviewView,
    targetRotation: Int,
    private val mainExecutor: Executor,
    budgetProvider: CaptureAnalysisBudgetProvider = StaticCaptureAnalysisBudgetProvider(),
    onQuality: (CaptureQualityObservation?) -> Unit,
    onFailure: (String) -> Unit,
    private val continuity: CaptureOpticsContinuity = CaptureOpticsContinuity(),
    thresholdProvider: CaptureQualityThresholdProvider = StaticCaptureQualityThresholdProvider(),
) : AutoCloseable {
    val capabilities = CameraXCapabilityReader.read(cameraInfo)
    val optics = CameraOpticsController()
    private val closed = AtomicBoolean(false)
    private val observer = Camera2CaptureResultObserver(capabilities.camera, capabilities.activeArray)
    private val executor = Executors.newSingleThreadExecutor()
    private val clearQuality = { onQuality(null) }
    private val analyzer = CameraXCaptureQualityAnalyzer(
        sessionId = CaptureAnalysisSessionId(identity.sessionToken),
        budgetProvider = budgetProvider,
        thresholdProvider = thresholdProvider,
        onObservation = { observation -> mainExecutor.execute {
            if (!closed.get() && observation.sessionId.value == identity.sessionToken) onQuality(observation)
        } },
        onFailure = { failure -> mainExecutor.execute {
            if (!closed.get()) { onQuality(null); onFailure(failure.detail) }
        } },
    )
    val analysis = CaptureQualityImageAnalysis.create(executor, targetRotation, analyzer)

    fun configure(preview: Preview.Builder, capture: ImageCapture.Builder) {
        // Logical-only mode is explicit until a physical selection has actual device evidence.
        // Never select an arbitrary physical camera from an unordered capability list.
        CameraXOpticsInterop.configurePreview(preview, capabilities, null, observer)
        CameraXOpticsInterop.configureImageCapture(capture, capabilities, null)
    }

    fun bind(camera: Camera) {
        check(!closed.get())
        optics.bind(CaptureSessionToken(identity.sessionToken), capabilities.camera, capabilities,
            CameraXOpticsBackend(camera, capabilities, observer, CameraMeteringPointProvider { point, size ->
                previewView.meteringPointFactory.createPoint(point.x * previewView.width,
                    point.y * previewView.height, size)
            }))
    }

    fun currentFrameNumber(): Long = observer.latestFrameNumber

    fun savedMetadata(rotation: Int, afterFrame: Long, deviceNamespace: String?): CaptureMetadataSnapshot {
        val still = if (closed.get()) null else observer.stillAfter(afterFrame)
        continuity.observe(still)
        return CaptureMetadataSnapshot(targetRotation = rotation, capturedAtEpochMillis = System.currentTimeMillis(),
            fields = buildMap {
                still?.let { value ->
                    put("confirmationSource", value.source.name)
                    value.sensorTimestampNanos?.let { put("sensorTimestampNanos", it.toString()) }
                    value.frameNumber?.let { put("frameNumber", it.toString()) }
                    value.exposureTimeNanos?.let { put("exposureTimeNanos", it.toString()) }
                    value.sensitivityIso?.let { put("sensitivityIso", it.toString()) }
                    value.aperture?.let { put("aperture", it.toString()) }
                    value.focusDistanceDiopters?.let { put("focusDistanceDiopters", it.toString()) }
                    value.focalLengthMm?.let { put("focalLengthMm", it.toString()) }
                    value.zoomRatio?.let { put("zoomRatio", it.toString()) }
                    value.aeLocked?.let { put("aeLocked", it.toString()) }
                    value.awbLocked?.let { put("awbLocked", it.toString()) }
                    value.cropRegion?.let {
                        put("cropLeft", it.left.toString()); put("cropTop", it.top.toString())
                        put("cropRight", it.right.toString()); put("cropBottom", it.bottom.toString())
                    }
                }
            },
            captureMetadata = CaptureOpticsMetadataAdapter.fromStill(deviceNamespace, capabilities,
                still))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        optics.unbind("Camera session was disposed or paused.")
        analyzer.close()
        analysis.clearAnalyzer()
        executor.shutdown()
        clearQuality()
    }
}
