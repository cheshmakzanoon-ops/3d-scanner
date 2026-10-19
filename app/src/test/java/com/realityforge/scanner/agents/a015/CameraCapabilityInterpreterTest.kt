package com.realityforge.scanner.agents.a015

import com.realityforge.scanner.capture.controls.CameraCapabilityInterpreter
import com.realityforge.scanner.capture.controls.FocusMode
import com.realityforge.scanner.capture.controls.LensFacing
import com.realityforge.scanner.capture.controls.PixelSize
import com.realityforge.scanner.capture.controls.RawCameraCapabilities
import com.realityforge.scanner.capture.controls.StabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityInterpreterTest {
    @Test
    fun absentCharacteristicsRemainUnavailableInsteadOfInvented() {
        val capabilities = CameraCapabilityInterpreter.interpret(
            RawCameraCapabilities(logicalCameraId = "rear-logical"),
        )

        assertEquals(1f, capabilities.zoom.minimumRatio)
        assertEquals(1f, capabilities.zoom.maximumRatio)
        assertFalse(capabilities.exposureCompensation.supported)
        assertFalse(capabilities.focusMeteringSupported)
        assertFalse(capabilities.aeLockAvailable)
        assertFalse(capabilities.awbLockAvailable)
        assertTrue(capabilities.physicalCameraIds.isEmpty())
        assertTrue(capabilities.jpegOutputSizes.isEmpty())
        assertNull(capabilities.camera.physicalCameraId)
    }

    @Test
    fun malformedAndDuplicateValuesAreBoundedAndCanonicalized() {
        val capabilities = CameraCapabilityInterpreter.interpret(
            RawCameraCapabilities(
                logicalCameraId = "logical-0",
                selectedPhysicalCameraId = "tele",
                lensFacing = LensFacing.BACK,
                physicalCameraIds = listOf(" tele ", "wide", "wide", "", "logical-0"),
                focalLengthsMm = listOf(Float.NaN, -1f, 4.2f, 4.2f, 8.4f),
                jpegOutputSizes = listOf(
                    PixelSize(1920, 1080),
                    PixelSize(4000, 3000),
                    PixelSize(1920, 1080),
                ),
                minimumZoomRatio = 0.5f,
                maximumZoomRatio = 8f,
                currentZoomRatio = 99f,
                exposureMinimumIndex = -4,
                exposureMaximumIndex = 4,
                exposureCurrentIndex = 99,
                exposureStepEv = 1f / 3f,
                focusModes = listOf(FocusMode.UNKNOWN, FocusMode.AUTO),
                maximumAfRegions = 1,
                maximumAeRegions = 0,
                maximumAwbRegions = 1,
                aeLockAvailable = true,
                awbLockAvailable = false,
                stabilizationModes = listOf(
                    StabilizationMode.OPTICAL,
                    StabilizationMode.OPTICAL,
                ),
            ),
        )

        assertEquals(listOf("tele", "wide"), capabilities.physicalCameraIds)
        assertEquals("tele", capabilities.camera.physicalCameraId)
        assertEquals(listOf(4.2f, 8.4f), capabilities.focalLengthsMm)
        assertEquals(PixelSize(4000, 3000), capabilities.largestJpegOutput)
        assertEquals(8f, capabilities.zoom.currentRatio)
        assertEquals(4, capabilities.exposureCompensation.currentIndex)
        assertTrue(capabilities.focusMeteringSupported)
        assertFalse(capabilities.aeMeteringSupported)
        assertTrue(capabilities.awbMeteringSupported)
        assertTrue(capabilities.aeLockAvailable)
        assertFalse(capabilities.awbLockAvailable)
        assertEquals(setOf(StabilizationMode.OPTICAL), capabilities.stabilizationModes)
    }

    @Test
    fun invalidSelectedPhysicalIdIsNotReportedAsSelected() {
        val capabilities = CameraCapabilityInterpreter.interpret(
            RawCameraCapabilities(
                logicalCameraId = "0",
                selectedPhysicalCameraId = "not-advertised",
                physicalCameraIds = listOf("1", "2"),
            ),
        )

        assertNull(capabilities.camera.physicalCameraId)
        assertFalse(capabilities.supportsPhysicalCamera("not-advertised"))
    }
}
