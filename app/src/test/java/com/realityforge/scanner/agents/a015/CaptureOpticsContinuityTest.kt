package com.realityforge.scanner.agents.a015

import com.realityforge.scanner.capture.controls.*
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureOpticsContinuityTest {
    @Test fun unknownResultsDoNotEraseConfirmedLensAcrossCameraRebinds() {
        val continuity = CaptureOpticsContinuity()
        continuity.observe(null)
        continuity.observe(metadata("0", null))
        continuity.observe(metadata("0", "wide"))
        continuity.observe(null)
        continuity.observe(metadata("0", null))
        continuity.observe(metadata("0", "wide"))
        assertThrows(IllegalStateException::class.java) { continuity.observe(metadata("0", "tele")) }
        assertThrows(IllegalStateException::class.java) { continuity.observe(metadata("1", "wide")) }
    }

    private fun metadata(logical: String, physical: String?) = AppliedOpticsMetadata(CameraIdentity(logical),
        physical, 4f, 1f, null, 1_000_000, 100, 2f, 0f, false, false, 1, 1,
        OpticsConfirmationSource.CAMERA2_CAPTURE_RESULT)
}
