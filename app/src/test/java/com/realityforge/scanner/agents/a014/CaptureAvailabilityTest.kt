package com.realityforge.scanner.agents.a014

import com.realityforge.scanner.capture.session.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CaptureAvailabilityTest {
    private val metadata = CaptureMetadataSnapshot(capturedAtEpochMillis = 1234L)
    private fun fixture(block: (CaptureSessionController) -> Unit) {
        val root = Files.createTempDirectory("capture-availability").toFile()
        var primary: Throwable? = null
        try {
            var number = 0
            block(CaptureSessionController(CaptureSessionIdentity("project", 0, "session"),
                CaptureStagingStore(root), captureTokenFactory = { "capture-${++number}" }).also { it.markReady() })
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { check(root.deleteRecursively()) }
            catch (cleanup: Throwable) { if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup) }
        }
    }
    private val restrictions = listOf(CaptureSessionPhase.PAUSED, CaptureSessionPhase.WAITING_FOR_PERMISSION, CaptureSessionPhase.UNAVAILABLE)
    private fun restrict(c: CaptureSessionController, phase: CaptureSessionPhase) {
        when (phase) {
            CaptureSessionPhase.PAUSED -> c.markPaused()
            CaptureSessionPhase.WAITING_FOR_PERMISSION -> c.markWaitingForPermission("Permission required")
            CaptureSessionPhase.UNAVAILABLE -> c.markUnavailable("Camera disconnected")
            else -> error("Unexpected fixture")
        }
    }
    private fun assertRestricted(c: CaptureSessionController, phase: CaptureSessionPhase) {
        assertEquals(phase, c.state.value.phase); assertNull(c.beginCapture())
        if (phase == CaptureSessionPhase.UNAVAILABLE) assertEquals("Camera disconnected", c.state.value.message)
        if (phase == CaptureSessionPhase.WAITING_FOR_PERMISSION) assertEquals("Permission required", c.state.value.message)
    }
    @Test fun capturingErrorRestoresLatestAvailability() {
        for (phase in restrictions) fixture { c ->
            val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("partial")
            restrict(c, phase); assertEquals(CaptureSessionPhase.CAPTURING, c.state.value.phase)
            assertTrue(a.stagedFile.exists())
            assertTrue(c.onCaptureError(a, "Capture failed")); assertFalse(a.stagedFile.exists())
            assertRestricted(c, phase)
        }
    }
    @Test fun availabilityDuringCaptureDoesNotDropAcceptedPublication() {
        for (phase in restrictions) fixture { c ->
            val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("publisher source")
            restrict(c, phase)
            assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
            assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Discarded)
            assertTrue(a.stagedFile.exists())
            assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
            assertFalse(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
            assertEquals(1, c.state.value.savedCount); assertEquals(1L, c.state.value.expectedRevision)
            assertTrue(a.stagedFile.exists()); assertRestricted(c, phase)
        }
    }
    @Test fun committingSuccessRestoresLatestAvailability() {
        for (phase in restrictions) fixture { c ->
            val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("publisher source")
            assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
            restrict(c, phase); assertEquals(CaptureSessionPhase.COMMITTING, c.state.value.phase)
            assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
            assertTrue(a.stagedFile.exists()); assertRestricted(c, phase)
        }
    }
    @Test fun committingRejectionRestoresLatestAvailability() {
        for (phase in restrictions) fixture { c ->
            val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("uncommitted source")
            assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
            restrict(c, phase)
            assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Rejected("Not committed")))
            assertFalse(a.stagedFile.exists()); assertEquals(0, c.state.value.savedCount)
            assertRestricted(c, phase)
        }
    }
    @Test fun laterReadyWinsWithoutReopeningPendingCapture() {
        for (committing in listOf(false, true)) fixture { c ->
            val a = requireNotNull(c.beginCapture())
            if (committing) assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
            c.markPaused(); c.markReady(); assertNull(c.beginCapture())
            if (committing) assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
            else assertTrue(c.onCaptureError(a, "Failed"))
            assertEquals(CaptureSessionPhase.READY, c.state.value.phase); assertNotNull(c.beginCapture())
        }
    }
    @Test fun laterDenialWinsOverEarlierReady() = fixture { c ->
        val a = requireNotNull(c.beginCapture())
        c.markPaused(); c.markReady(); c.markWaitingForPermission("Permission required")
        c.onCaptureError(a, "Failed"); assertRestricted(c, CaptureSessionPhase.WAITING_FOR_PERMISSION)
        c.markReady(); assertNotNull(c.beginCapture())
    }
    @Test fun closedRemainsTerminalWhilePendingPublicationSettlesOnce() = fixture { c ->
        val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("publisher source")
        assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
        c.markPaused(); c.close(CaptureCloseReason.USER_BACK); c.markReady()
        assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
        assertFalse(c.onCommit(a.captureToken, CaptureCommitOutcome.Accepted(1)))
        assertEquals(CaptureSessionPhase.CLOSED, c.state.value.phase); assertNull(c.beginCapture())
        assertTrue(a.stagedFile.exists()); assertEquals(1, c.state.value.savedCount)
    }
    @Test fun duplicatePublicationRetainsSourceAndLatestAvailability() = fixture { c ->
        val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("unverified duplicate source")
        assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
        c.markUnavailable("Camera disconnected")
        assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Duplicate(0)))
        assertTrue(a.stagedFile.exists()); assertEquals(0, c.state.value.savedCount)
        assertRestricted(c, CaptureSessionPhase.UNAVAILABLE)
    }
    @Test fun safeAbandonRestoresLatestAvailability() = fixture { c ->
        val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("abandoned partial")
        c.markPaused()
        assertTrue(c.abandonPending("Adapter confirmed writer stopped"))
        assertFalse(a.stagedFile.exists()); assertRestricted(c, CaptureSessionPhase.PAUSED)
    }
    @Test fun uncertaintyCannotBeClearedByLaterReady() = fixture { c ->
        val a = requireNotNull(c.beginCapture()); a.stagedFile.writeText("uncertain source")
        assertTrue(c.onImageSaved(a, metadata) is SavedImageDecision.Deliver)
        c.markPaused()
        assertTrue(c.onCommit(a.captureToken, CaptureCommitOutcome.Uncertain("Needs recovery")))
        c.markReady()
        assertEquals(CaptureSessionPhase.UNAVAILABLE, c.state.value.phase)
        assertTrue(c.state.value.requiresRecovery); assertNull(c.beginCapture()); assertTrue(a.stagedFile.exists())
    }
}
