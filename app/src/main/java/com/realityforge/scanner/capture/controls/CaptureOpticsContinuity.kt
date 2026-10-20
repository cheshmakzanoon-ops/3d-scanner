package com.realityforge.scanner.capture.controls

/** Retained for the capture session, including CameraX pause/rebinds. Unknown stays unknown. */
class CaptureOpticsContinuity {
    private var logicalCameraId: String? = null
    private var physicalCameraId: String? = null

    @Synchronized
    fun observe(still: AppliedOpticsMetadata?) {
        if (still == null) return
        check(logicalCameraId == null || logicalCameraId == still.camera.logicalCameraId) {
            "The camera changed. Start a new capture session before continuing."
        }
        still.activePhysicalCameraId?.let { physical ->
            check(physicalCameraId == null || physicalCameraId == physical) {
                "The camera changed physical lenses. Start a new capture session before continuing."
            }
            physicalCameraId = physical
        }
        logicalCameraId = still.camera.logicalCameraId
    }
}
