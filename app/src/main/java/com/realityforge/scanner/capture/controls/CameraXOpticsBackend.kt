package com.realityforge.scanner.capture.controls

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.google.common.util.concurrent.ListenableFuture
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface CameraMeteringPointProvider {
    /** Converts the normalized UI point into the bound preview's metering coordinate system. */
    fun create(point: NormalizedPoint, relativeSize: Float): MeteringPoint
}

/** Concrete bounded adapter for one already-bound CameraX Camera. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXOpticsBackend(
    private val camera: Camera,
    private val capabilities: CameraCapabilities,
    private val resultObserver: Camera2CaptureResultObserver,
    private val meteringPointProvider: CameraMeteringPointProvider? = null,
) : OpticalControlBackend {
    private val pendingFutures = Collections.synchronizedSet(mutableSetOf<ListenableFuture<*>>())

    override suspend fun setZoomRatio(ratio: Float): BackendControlResult<Float> {
        if (!capabilities.zoom.supports(ratio)) {
            return BackendControlResult.Unsupported("Zoom ratio is outside the advertised range.")
        }
        return runControl("OPTICS_ZOOM_FAILED") {
            camera.cameraControl.setZoomRatio(ratio).awaitTracked()
            val observed = camera.cameraInfo.zoomState.value?.zoomRatio
            if (observed != null && observed.nearlyEquals(ratio)) {
                BackendControlResult.Confirmed(
                    value = observed,
                    metadata = latestMetadata(),
                    detail = "CameraX zoom state confirmed the requested ratio.",
                )
            } else {
                BackendControlResult.Acknowledged(
                    value = ratio,
                    metadata = latestMetadata(),
                    detail = "CameraX accepted zoom; a matching state snapshot was not yet available.",
                )
            }
        }
    }

    override suspend fun setExposureCompensation(index: Int): BackendControlResult<Int> {
        if (
            !capabilities.exposureCompensation.supported ||
            !capabilities.exposureCompensation.supports(index)
        ) {
            return BackendControlResult.Unsupported(
                "Exposure compensation is unavailable or outside the advertised range.",
            )
        }
        return runControl("OPTICS_EXPOSURE_COMPENSATION_FAILED") {
            camera.cameraControl.setExposureCompensationIndex(index).awaitTracked()
            val observed = camera.cameraInfo.exposureState.exposureCompensationIndex
            if (observed == index) {
                BackendControlResult.Confirmed(
                    value = observed,
                    metadata = latestMetadata(),
                    detail = "CameraX exposure state confirmed the requested index.",
                )
            } else {
                BackendControlResult.Acknowledged(
                    value = index,
                    metadata = latestMetadata(),
                    detail = "CameraX accepted exposure compensation; state confirmation is pending.",
                )
            }
        }
    }

    override suspend fun applyFocus(request: FocusRequest): BackendControlResult<FocusRequest> =
        when (request) {
            FocusRequest.KeepCurrent -> BackendControlResult.Confirmed(
                value = request,
                metadata = latestMetadata(),
            )
            FocusRequest.Cancel -> runControl("OPTICS_FOCUS_CANCEL_FAILED") {
                camera.cameraControl.cancelFocusAndMetering().awaitTracked()
                BackendControlResult.Confirmed(
                    value = request,
                    metadata = latestMetadata(),
                    detail = "CameraX acknowledged focus/metering cancellation.",
                )
            }
            is FocusRequest.MeterAt -> applyMeteringFocus(request)
        }

    override suspend fun setLocks(
        aeLocked: Boolean?,
        awbLocked: Boolean?,
        confirmationTimeoutMillis: Long,
    ): LockBackendResult {
        val aeUnsupported = aeLocked != null && !capabilities.aeLockAvailable
        val awbUnsupported = awbLocked != null && !capabilities.awbLockAvailable
        if (aeUnsupported || awbUnsupported) {
            return LockBackendResult(
                ae = if (aeUnsupported) {
                    BackendControlResult.Unsupported("AE lock is unavailable on this camera.")
                } else {
                    null
                },
                awb = if (awbUnsupported) {
                    BackendControlResult.Unsupported("AWB lock is unavailable on this camera.")
                } else {
                    null
                },
                metadata = latestMetadata(),
            )
        }
        if (aeLocked == null && awbLocked == null) {
            return LockBackendResult(null, null, latestMetadata())
        }

        val beforeFrame = resultObserver.metadata.value?.frameNumber
        val acknowledged = try {
            val builder = CaptureRequestOptions.Builder()
            if (aeLocked != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            }
            if (awbLocked != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, awbLocked)
            }
            Camera2CameraControl.from(camera.cameraControl)
                .addCaptureRequestOptions(builder.build())
                .awaitTracked()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val detail = failure.message ?: failure::class.java.simpleName
            return LockBackendResult(
                ae = aeLocked?.let {
                    BackendControlResult.Failed("OPTICS_AE_LOCK_FAILED", detail, failure)
                },
                awb = awbLocked?.let {
                    BackendControlResult.Failed("OPTICS_AWB_LOCK_FAILED", detail, failure)
                },
                metadata = latestMetadata(),
            )
        }

        val confirmed = if (acknowledged) {
            resultObserver.awaitLocks(
                aeLocked = aeLocked,
                awbLocked = awbLocked,
                afterFrameExclusive = beforeFrame,
                timeoutMillis = confirmationTimeoutMillis.coerceIn(100L, 5_000L),
            )
        } else {
            null
        }
        val metadata = confirmed ?: latestMetadata()
        return LockBackendResult(
            ae = aeLocked?.let { desired ->
                if (confirmed?.aeLocked == desired) {
                    BackendControlResult.Confirmed(
                        value = desired,
                        metadata = confirmed,
                        detail = "Camera2 capture result confirmed AE lock state.",
                    )
                } else {
                    BackendControlResult.Acknowledged(
                        value = desired,
                        metadata = metadata,
                        detail = "AE lock request reached CameraX but no matching capture result arrived.",
                    )
                }
            },
            awb = awbLocked?.let { desired ->
                if (confirmed?.awbLocked == desired) {
                    BackendControlResult.Confirmed(
                        value = desired,
                        metadata = confirmed,
                        detail = "Camera2 capture result confirmed AWB lock state.",
                    )
                } else {
                    BackendControlResult.Acknowledged(
                        value = desired,
                        metadata = metadata,
                        detail = "AWB lock request reached CameraX but no matching capture result arrived.",
                    )
                }
            },
            metadata = metadata,
        )
    }

    override fun latestMetadata(): AppliedOpticsMetadata? = resultObserver.metadata.value

    override fun cancelPending(reason: String) {
        val snapshot = synchronized(pendingFutures) { pendingFutures.toList() }
        snapshot.forEach { future -> future.cancel(true) }
    }

    private suspend fun applyMeteringFocus(
        request: FocusRequest.MeterAt,
    ): BackendControlResult<FocusRequest> {
        if (!capabilities.focusMeteringSupported) {
            return BackendControlResult.Unsupported("AF metering is unavailable on this camera.")
        }
        val provider = meteringPointProvider
            ?: return BackendControlResult.Unsupported(
                "The preview has not supplied a metering-point mapper.",
            )
        val point = try {
            provider.create(request.point, request.relativeSize)
        } catch (failure: Throwable) {
            return BackendControlResult.Failed(
                diagnosticCode = "OPTICS_METERING_POINT_FAILED",
                detail = failure.message ?: "Could not map the metering point.",
                cause = failure,
            )
        }
        var flags = FocusMeteringAction.FLAG_AF
        if (capabilities.aeMeteringSupported) flags = flags or FocusMeteringAction.FLAG_AE
        if (capabilities.awbMeteringSupported) flags = flags or FocusMeteringAction.FLAG_AWB
        val action = FocusMeteringAction.Builder(point, flags)
            .setAutoCancelDuration(request.autoCancelMillis, TimeUnit.MILLISECONDS)
            .build()
        if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
            return BackendControlResult.Unsupported(
                "CameraX rejected the requested focus/metering action for this binding.",
            )
        }
        return runControl("OPTICS_FOCUS_FAILED") {
            val result = camera.cameraControl.startFocusAndMetering(action).awaitTracked()
            if (result.isFocusSuccessful) {
                BackendControlResult.Confirmed(
                    value = request,
                    metadata = latestMetadata(),
                    detail = "CameraX reported a successful focus action.",
                )
            } else {
                BackendControlResult.Failed(
                    diagnosticCode = "OPTICS_FOCUS_NOT_ACQUIRED",
                    detail = "CameraX completed metering but did not acquire focus.",
                )
            }
        }
    }

    private suspend fun <T> runControl(
        diagnosticCode: String,
        block: suspend () -> BackendControlResult<T>,
    ): BackendControlResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        BackendControlResult.Failed(
            diagnosticCode = diagnosticCode,
            detail = failure.message ?: failure::class.java.simpleName,
            cause = failure,
        )
    }

    private suspend fun <T> ListenableFuture<T>.awaitTracked(): T {
        pendingFutures += this
        return try {
            awaitListenableFuture()
        } finally {
            pendingFutures -= this
        }
    }
}

private val directExecutor = Executor { runnable -> runnable.run() }

private suspend fun <T> ListenableFuture<T>.awaitListenableFuture(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                if (!continuation.isActive) return@addListener
                try {
                    continuation.resume(get())
                } catch (execution: ExecutionException) {
                    continuation.resumeWithException(execution.cause ?: execution)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            },
            directExecutor,
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

private fun Float.nearlyEquals(other: Float): Boolean {
    val scale = maxOf(1f, kotlin.math.abs(this), kotlin.math.abs(other))
    return kotlin.math.abs(this - other) <= 0.002f * scale
}
