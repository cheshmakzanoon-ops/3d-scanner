package com.realityforge.scanner.capture.controls

import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview

/**
 * Pre-build CameraX configuration for Agent 014. The same selected physical id must be applied
 * to every simultaneously bound use case; CameraX rejects mixed physical ids in one binding.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object CameraXOpticsInterop {
    fun configurePreview(
        builder: Preview.Builder,
        capabilities: CameraCapabilities,
        selectedPhysicalCameraId: String?,
        resultObserver: Camera2CaptureResultObserver,
    ): Preview.Builder {
        validatePhysicalSelection(capabilities, selectedPhysicalCameraId)
        val extender = Camera2Interop.Extender(builder)
        selectedPhysicalCameraId?.let(extender::setPhysicalCameraId)
        extender.setSessionCaptureCallback(resultObserver)
        return builder
    }

    fun configureImageCapture(
        builder: ImageCapture.Builder,
        capabilities: CameraCapabilities,
        selectedPhysicalCameraId: String?,
    ): ImageCapture.Builder {
        validatePhysicalSelection(capabilities, selectedPhysicalCameraId)
        val extender = Camera2Interop.Extender(builder)
        selectedPhysicalCameraId?.let(extender::setPhysicalCameraId)
        return builder
    }

    private fun validatePhysicalSelection(
        capabilities: CameraCapabilities,
        selectedPhysicalCameraId: String?,
    ) {
        require(
            selectedPhysicalCameraId == null ||
                capabilities.supportsPhysicalCamera(selectedPhysicalCameraId),
        ) {
            "Physical camera $selectedPhysicalCameraId is not advertised by " +
                capabilities.camera.logicalCameraId
        }
    }
}
