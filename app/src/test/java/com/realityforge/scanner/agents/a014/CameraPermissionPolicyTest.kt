package com.realityforge.scanner.agents.a014

import com.realityforge.scanner.capture.permissions.CameraPermissionObservation
import com.realityforge.scanner.capture.permissions.CameraPermissionPolicy
import com.realityforge.scanner.capture.permissions.CameraPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionPolicyTest {
    @Test
    fun firstRequestIsRequestable() {
        assertStatus(
            CameraPermissionStatus.REQUESTABLE,
            granted = false,
            rationale = false,
            requested = false,
            camera = true,
        )
    }

    @Test
    fun denialWithRationaleCanBeRequestedAgain() {
        assertStatus(
            CameraPermissionStatus.RATIONALE_REQUIRED,
            granted = false,
            rationale = true,
            requested = true,
            camera = true,
        )
    }

    @Test
    fun denialWithoutRationaleAfterRequestRequiresSettings() {
        assertStatus(
            CameraPermissionStatus.PERMANENTLY_DENIED,
            granted = false,
            rationale = false,
            requested = true,
            camera = true,
        )
    }

    @Test
    fun settingsRegrantAndLaterRevocationAreReevaluated() {
        assertStatus(
            CameraPermissionStatus.GRANTED,
            granted = true,
            rationale = false,
            requested = true,
            camera = true,
        )
        assertStatus(
            CameraPermissionStatus.PERMANENTLY_DENIED,
            granted = false,
            rationale = false,
            requested = true,
            camera = true,
        )
    }

    @Test
    fun absentCameraWinsWithoutBlockingPhotoImport() {
        assertStatus(
            CameraPermissionStatus.CAMERA_UNAVAILABLE,
            granted = true,
            rationale = false,
            requested = false,
            camera = false,
        )
    }

    private fun assertStatus(
        expected: CameraPermissionStatus,
        granted: Boolean,
        rationale: Boolean,
        requested: Boolean,
        camera: Boolean,
    ) {
        assertEquals(
            expected,
            CameraPermissionPolicy.evaluate(
                CameraPermissionObservation(
                    granted = granted,
                    shouldShowRationale = rationale,
                    hasRequestedBefore = requested,
                    cameraAvailable = camera,
                ),
            ),
        )
    }
}
