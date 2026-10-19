package com.realityforge.scanner.capture.session

import androidx.camera.core.Camera
import androidx.camera.core.UseCase

/**
 * Integration seam for the optical-control, live-analysis, and guidance owners. Extensions may
 * contribute session-scoped use cases and observe the exact camera binding; they must not bind or
 * unbind the provider independently.
 */
interface CaptureSessionExtension {
    val useCases: List<UseCase>
        get() = emptyList()

    fun onCameraBound(identity: CaptureSessionIdentity, camera: Camera) = Unit

    fun onCameraUnbound(sessionToken: String) = Unit
}
