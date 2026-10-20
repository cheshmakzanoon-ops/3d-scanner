package com.realityforge.scanner.capture.session

/** Injectable camera boundary used by production CameraX and deterministic delayed-callback tests. */
interface CaptureAdapter {
    fun takePicture(attempt: CaptureAttempt, callback: Callback)

    interface Callback {
        fun onSaved(metadata: CaptureMetadataSnapshot)
        fun onError(message: String)
    }
}
