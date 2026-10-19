package com.realityforge.scanner.agents.a015

import com.realityforge.scanner.capture.controls.AppliedOpticsMetadata
import com.realityforge.scanner.capture.controls.AppliedOpticsPhase
import com.realityforge.scanner.capture.controls.BackendControlResult
import com.realityforge.scanner.capture.controls.CameraCapabilities
import com.realityforge.scanner.capture.controls.CameraIdentity
import com.realityforge.scanner.capture.controls.CameraOpticsController
import com.realityforge.scanner.capture.controls.CaptureSessionToken
import com.realityforge.scanner.capture.controls.ControlStatus
import com.realityforge.scanner.capture.controls.ExposureCompensationCapability
import com.realityforge.scanner.capture.controls.FocusMode
import com.realityforge.scanner.capture.controls.FocusRequest
import com.realityforge.scanner.capture.controls.IntCropRegion
import com.realityforge.scanner.capture.controls.LensFacing
import com.realityforge.scanner.capture.controls.LockBackendResult
import com.realityforge.scanner.capture.controls.LockDirective
import com.realityforge.scanner.capture.controls.OpticalCommandId
import com.realityforge.scanner.capture.controls.OpticalControlBackend
import com.realityforge.scanner.capture.controls.OpticalControlRequest
import com.realityforge.scanner.capture.controls.OpticsConfirmationSource
import com.realityforge.scanner.capture.controls.PixelSize
import com.realityforge.scanner.capture.controls.ZoomCapability
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpticsControllerTest {
    @Test
    fun lockRequestIsPendingUntilCaptureResultConfirmation() = runBlocking {
        val controller = CameraOpticsController()
        val backend = FakeBackend()
        val lockGate = CompletableDeferred<LockBackendResult>()
        backend.lockHandler = { _, _, _ -> lockGate.await() }
        controller.bind(SESSION_A, CAMERA, capabilities(), backend)

        val job = launch {
            controller.submit(request(command = "lock", aeLock = LockDirective.LOCK))
        }
        awaitCondition { controller.state.value.aeLock.status == ControlStatus.PENDING }
        assertEquals(AppliedOpticsPhase.PENDING, controller.state.value.phase)
        assertFalse(controller.state.value.aeLock.status == ControlStatus.APPLIED)

        val metadata = metadata(activePhysicalId = "wide", aeLocked = true)
        backend.latest = metadata
        lockGate.complete(
            LockBackendResult(
                ae = BackendControlResult.Confirmed(true, metadata),
                awb = null,
                metadata = metadata,
            ),
        )
        job.join()

        assertEquals(ControlStatus.APPLIED, controller.state.value.aeLock.status)
        assertEquals(true, controller.state.value.aeLock.applied)
        assertEquals(AppliedOpticsPhase.APPLIED, controller.state.value.phase)
    }

    @Test
    fun acknowledgementWithoutCaptureResultNeverBecomesApplied() = runBlocking {
        val controller = CameraOpticsController()
        val backend = FakeBackend().apply {
            lockHandler = { ae, _, _ ->
                LockBackendResult(
                    ae = BackendControlResult.Acknowledged(requireNotNull(ae)),
                    awb = null,
                )
            }
        }
        controller.bind(SESSION_A, CAMERA, capabilities(), backend)

        val result = controller.submit(request(command = "ack", aeLock = LockDirective.LOCK))

        assertEquals(ControlStatus.ACKNOWLEDGED_UNCONFIRMED, result.aeLock.status)
        assertEquals(AppliedOpticsPhase.ACKNOWLEDGED_UNCONFIRMED, result.phase)
        assertEquals(null, result.aeLock.applied)
    }

    @Test
    fun unsupportedControlsStayUnavailableAndDoNotReachBackend() = runBlocking {
        val controller = CameraOpticsController()
        val backend = FakeBackend()
        controller.bind(
            SESSION_A,
            CAMERA,
            capabilities(aeLock = false, awbLock = false, zoomMin = 1f, zoomMax = 1f),
            backend,
        )

        val result = controller.submit(
            request(
                command = "unsupported",
                zoom = 4f,
                aeLock = LockDirective.LOCK,
                awbLock = LockDirective.LOCK,
            ),
        )

        assertEquals(AppliedOpticsPhase.UNAVAILABLE, result.phase)
        assertEquals(ControlStatus.UNAVAILABLE, result.zoom.status)
        assertEquals(ControlStatus.UNAVAILABLE, result.aeLock.status)
        assertEquals(ControlStatus.UNAVAILABLE, result.awbLock.status)
        assertEquals(0, backend.zoomCalls.get())
        assertEquals(0, backend.lockCalls.get())
    }

    @Test
    fun lateCompletionCannotUpdateReboundSession() = runBlocking {
        val controller = CameraOpticsController()
        val oldBackend = FakeBackend()
        val zoomGate = CompletableDeferred<BackendControlResult<Float>>()
        oldBackend.zoomHandler = { zoomGate.await() }
        controller.bind(SESSION_A, CAMERA, capabilities(), oldBackend)

        val oldJob: Job = launch {
            controller.submit(request(command = "old", zoom = 2f))
        }
        awaitCondition { controller.state.value.zoom.status == ControlStatus.PENDING }

        val newBackend = FakeBackend()
        controller.bind(SESSION_B, CAMERA, capabilities(), newBackend)
        zoomGate.complete(BackendControlResult.Confirmed(2f))
        oldJob.join()

        assertEquals(SESSION_B, controller.state.value.sessionToken)
        assertEquals(AppliedOpticsPhase.IDLE, controller.state.value.phase)
        assertTrue(oldBackend.cancelCalls.get() >= 1)
    }

    @Test
    fun logicalOrPhysicalCameraMismatchIsRejectedBeforeCommand() = runBlocking {
        val controller = CameraOpticsController()
        val backend = FakeBackend()
        controller.bind(SESSION_A, CAMERA, capabilities(), backend)

        val result = controller.submit(
            request(command = "wrong-camera", zoom = 2f).copy(
                expectedCamera = CameraIdentity("other", "wide"),
            ),
        )

        assertEquals(AppliedOpticsPhase.FAILED, result.phase)
        assertEquals("OPTICS_CAMERA_IDENTITY_MISMATCH", result.diagnosticCode)
        assertEquals(0, backend.zoomCalls.get())
    }

    @Test
    fun physicalLensChangeInsideLogicalSessionFailsHonestly() = runBlocking {
        val controller = CameraOpticsController()
        val backend = FakeBackend()
        val unpinned = CameraIdentity("logical")
        controller.bind(SESSION_A, unpinned, capabilities(camera = unpinned), backend)

        backend.zoomHandler = { ratio ->
            val first = metadata(activePhysicalId = "wide", zoom = ratio, camera = unpinned)
            backend.latest = first
            BackendControlResult.Confirmed(ratio, first)
        }
        val first = controller.submit(
            request(command = "first", zoom = 2f).copy(expectedCamera = unpinned),
        )
        assertEquals(AppliedOpticsPhase.APPLIED, first.phase)

        backend.zoomHandler = { ratio ->
            val switched = metadata(activePhysicalId = "tele", zoom = ratio, camera = unpinned)
            backend.latest = switched
            BackendControlResult.Confirmed(ratio, switched)
        }
        val second = controller.submit(
            request(command = "second", zoom = 3f).copy(expectedCamera = unpinned),
        )

        assertEquals("OPTICS_PHYSICAL_CAMERA_CHANGED", second.diagnosticCode)
        assertTrue(second.phase == AppliedOpticsPhase.FAILED || second.phase == AppliedOpticsPhase.PARTIAL)
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        repeat(500) {
            if (predicate()) return
            delay(2)
        }
        throw AssertionError("Condition was not reached.")
    }

    private fun request(
        command: String,
        zoom: Float? = null,
        aeLock: LockDirective = LockDirective.KEEP,
        awbLock: LockDirective = LockDirective.KEEP,
    ) = OpticalControlRequest(
        sessionToken = SESSION_A,
        commandId = OpticalCommandId(command),
        expectedCamera = CAMERA,
        zoomRatio = zoom,
        aeLock = aeLock,
        awbLock = awbLock,
    )

    private class FakeBackend : OpticalControlBackend {
        val zoomCalls = AtomicInteger()
        val exposureCalls = AtomicInteger()
        val focusCalls = AtomicInteger()
        val lockCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()

        var latest: AppliedOpticsMetadata? = null
        var zoomHandler: suspend (Float) -> BackendControlResult<Float> = { value ->
            BackendControlResult.Confirmed(value, latest)
        }
        var lockHandler: suspend (Boolean?, Boolean?, Long) -> LockBackendResult = { ae, awb, _ ->
            LockBackendResult(
                ae = ae?.let { BackendControlResult.Confirmed(it, latest) },
                awb = awb?.let { BackendControlResult.Confirmed(it, latest) },
                metadata = latest,
            )
        }

        override suspend fun setZoomRatio(ratio: Float): BackendControlResult<Float> {
            zoomCalls.incrementAndGet()
            return zoomHandler(ratio)
        }

        override suspend fun setExposureCompensation(index: Int): BackendControlResult<Int> {
            exposureCalls.incrementAndGet()
            return BackendControlResult.Confirmed(index, latest)
        }

        override suspend fun applyFocus(request: FocusRequest): BackendControlResult<FocusRequest> {
            focusCalls.incrementAndGet()
            return BackendControlResult.Confirmed(request, latest)
        }

        override suspend fun setLocks(
            aeLocked: Boolean?,
            awbLocked: Boolean?,
            confirmationTimeoutMillis: Long,
        ): LockBackendResult {
            lockCalls.incrementAndGet()
            return lockHandler(aeLocked, awbLocked, confirmationTimeoutMillis)
        }

        override fun latestMetadata(): AppliedOpticsMetadata? = latest

        override fun cancelPending(reason: String) {
            cancelCalls.incrementAndGet()
        }
    }

    companion object {
        private val SESSION_A = CaptureSessionToken("session-a")
        private val SESSION_B = CaptureSessionToken("session-b")
        private val CAMERA = CameraIdentity("logical", "wide")

        private fun capabilities(
            camera: CameraIdentity = CAMERA,
            aeLock: Boolean = true,
            awbLock: Boolean = true,
            zoomMin: Float = 1f,
            zoomMax: Float = 8f,
        ) = CameraCapabilities(
            camera = camera,
            lensFacing = LensFacing.BACK,
            physicalCameraIds = listOf("wide", "tele"),
            focalLengthsMm = listOf(4.2f, 8.4f),
            activeArray = IntCropRegion(0, 0, 4000, 3000),
            jpegOutputSizes = listOf(PixelSize(4000, 3000)),
            zoom = ZoomCapability(zoomMin, zoomMax, 1f.coerceIn(zoomMin, zoomMax)),
            exposureCompensation = ExposureCompensationCapability(-4, 4, 0, 1f / 3f),
            focusModes = setOf(FocusMode.AUTO),
            focusMeteringSupported = true,
            aeMeteringSupported = true,
            awbMeteringSupported = true,
            aeLockAvailable = aeLock,
            awbLockAvailable = awbLock,
            stabilizationModes = emptySet(),
        )

        private fun metadata(
            activePhysicalId: String?,
            aeLocked: Boolean? = null,
            zoom: Float? = 1f,
            camera: CameraIdentity = CAMERA,
        ) = AppliedOpticsMetadata(
            camera = camera,
            activePhysicalCameraId = activePhysicalId,
            focalLengthMm = 4.2f,
            zoomRatio = zoom,
            cropRegion = IntCropRegion(0, 0, 4000, 3000),
            exposureTimeNanos = 10_000_000L,
            sensitivityIso = 100,
            aperture = 1.8f,
            focusDistanceDiopters = 1f,
            aeLocked = aeLocked,
            awbLocked = null,
            sensorTimestampNanos = 1L,
            frameNumber = 1L,
            source = OpticsConfirmationSource.CAMERA2_CAPTURE_RESULT,
        )
    }
}
