package com.realityforge.scanner.agents.a015

import com.realityforge.scanner.capture.controls.AppliedOpticsPhase
import com.realityforge.scanner.capture.controls.AppliedOpticsState
import com.realityforge.scanner.capture.controls.CameraCapabilities
import com.realityforge.scanner.capture.controls.CameraIdentity
import com.realityforge.scanner.capture.controls.CameraOpticsUiModelFactory
import com.realityforge.scanner.capture.controls.ControlApplication
import com.realityforge.scanner.capture.controls.ExposureCompensationCapability
import com.realityforge.scanner.capture.controls.FocusMode
import com.realityforge.scanner.capture.controls.LensFacing
import com.realityforge.scanner.capture.controls.LockControlPresentation
import com.realityforge.scanner.capture.controls.ZoomCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpticsUiModelTest {
    @Test
    fun acknowledgedLockIsRenderedAsUnconfirmedRatherThanLocked() {
        val capabilities = capabilities()
        val state = AppliedOpticsState(
            sessionToken = null,
            commandId = null,
            camera = capabilities.camera,
            phase = AppliedOpticsPhase.ACKNOWLEDGED_UNCONFIRMED,
            aeLock = ControlApplication.acknowledged(true),
        )

        val model = CameraOpticsUiModelFactory.create(capabilities, state)

        assertEquals(LockControlPresentation.ACKNOWLEDGED_UNCONFIRMED, model.aeLock)
        assertFalse(model.commandInFlight)
        assertTrue(model.controlsEnabled)
    }

    @Test
    fun disposedSessionDisablesEveryInteractiveControl() {
        val capabilities = capabilities()
        val model = CameraOpticsUiModelFactory.create(
            capabilities,
            AppliedOpticsState(
                sessionToken = null,
                commandId = null,
                camera = capabilities.camera,
                phase = AppliedOpticsPhase.DISPOSED,
            ),
        )

        assertFalse(model.controlsEnabled)
        assertFalse(model.zoom.enabled)
        assertFalse(model.exposureCompensation.enabled)
        assertFalse(model.focusMeteringEnabled)
    }

    private fun capabilities() = CameraCapabilities(
        camera = CameraIdentity("0"),
        lensFacing = LensFacing.BACK,
        physicalCameraIds = emptyList(),
        focalLengthsMm = listOf(4f),
        activeArray = null,
        jpegOutputSizes = emptyList(),
        zoom = ZoomCapability(1f, 4f, 1f),
        exposureCompensation = ExposureCompensationCapability(-2, 2, 0, 0.5f),
        focusModes = setOf(FocusMode.AUTO),
        focusMeteringSupported = true,
        aeMeteringSupported = true,
        awbMeteringSupported = true,
        aeLockAvailable = true,
        awbLockAvailable = false,
        stabilizationModes = emptySet(),
    )
}
