package com.realityforge.scanner.agents.a014

import com.realityforge.scanner.capture.session.CaptureAdapter
import com.realityforge.scanner.capture.session.CaptureCloseReason
import com.realityforge.scanner.capture.session.CaptureCommitOutcome
import com.realityforge.scanner.capture.session.CaptureMetadataSnapshot
import com.realityforge.scanner.capture.session.CaptureSessionCheckpoint
import com.realityforge.scanner.capture.session.CaptureSessionController
import com.realityforge.scanner.capture.session.CaptureSessionIdentity
import com.realityforge.scanner.capture.session.CaptureSessionPhase
import com.realityforge.scanner.capture.session.CaptureStagingStore
import com.realityforge.scanner.capture.session.SavedImageDecision
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureSessionControllerTest {
    private lateinit var root: File
    private lateinit var store: CaptureStagingStore
    private var token = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rf-agent-014").toFile()
        store = CaptureStagingStore(root)
        token = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun delayedCallbackAfterBackIsRejectedAndPrivateFileIsRemoved() {
        val controller = controller(projectId = "project-a", revision = 4)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        attempt.stagedFile.writeBytes(byteArrayOf(1, 2, 3))

        controller.close(CaptureCloseReason.USER_BACK)
        val decision = controller.onImageSaved(attempt, metadata())

        assertTrue(decision is SavedImageDecision.Discarded)
        assertFalse(attempt.stagedFile.exists())
        assertEquals(CaptureSessionPhase.CLOSED, controller.state.value.phase)
        assertEquals(CaptureCloseReason.USER_BACK, controller.state.value.closeReason)
    }

    @Test
    fun staleSessionCannotPublishIntoReplacementProject() {
        val old = controller(projectId = "project-old", revision = 2)
        val replacement = controller(projectId = "project-new", revision = 11)
        old.markReady()
        replacement.markReady()
        val delayed = requireNotNull(old.beginCapture())
        delayed.stagedFile.writeText("old-session")

        old.close(CaptureCloseReason.NAVIGATION_CHANGED)
        val decision = old.onImageSaved(delayed, metadata())

        assertTrue(decision is SavedImageDecision.Discarded)
        assertEquals("project-new", replacement.state.value.identity.projectId)
        assertEquals(11L, replacement.state.value.expectedRevision)
        assertEquals(0, replacement.state.value.savedCount)
        assertFalse(delayed.stagedFile.exists())
    }

    @Test
    fun repeatedCommittedShotsAdvanceExpectedRevisionWithoutChangingSessionIdentity() {
        val controller = controller(projectId = "project-a", revision = 7)
        controller.markReady()

        val first = requireNotNull(controller.beginCapture())
        first.stagedFile.writeText("first")
        val firstDecision = controller.onImageSaved(first, metadata()) as SavedImageDecision.Deliver
        assertEquals("project-a", firstDecision.photo.projectId)
        assertEquals(7L, firstDecision.photo.revision)
        assertEquals("session-project-a", firstDecision.photo.sessionToken)
        assertTrue(controller.onCommit(first.captureToken, CaptureCommitOutcome.Accepted(8)))
        // A committed file belongs to the publication owner, not the session controller.
        assertTrue(first.stagedFile.exists())
        first.stagedFile.delete()

        val second = requireNotNull(controller.beginCapture())
        assertEquals(8L, second.expectedRevision)
        second.stagedFile.writeText("second")
        controller.onImageSaved(second, metadata())
        assertTrue(controller.onCommit(second.captureToken, CaptureCommitOutcome.Accepted(9)))
        second.stagedFile.delete()

        val state = controller.state.value
        assertEquals(CaptureSessionPhase.READY, state.phase)
        assertEquals(2, state.savedCount)
        assertEquals(9L, state.expectedRevision)
        assertEquals("session-project-a", state.identity.sessionToken)
    }

    @Test
    fun checkpointRestoresSavedCountAndNextRevisionAfterRecreation() {
        val original = controller(projectId = "project-a", revision = 9)
        original.markReady()
        val attempt = requireNotNull(original.beginCapture())
        attempt.stagedFile.writeText("committed")
        original.onImageSaved(attempt, metadata())
        assertTrue(original.onCommit(attempt.captureToken, CaptureCommitOutcome.Accepted(10)))
        attempt.stagedFile.delete()

        val restored = CaptureSessionController(
            identity = original.identity,
            stagingStore = store,
            restoredCheckpoint = original.state.value.checkpoint(),
            captureTokenFactory = { "restored-shot" },
        )
        restored.markReady()
        val next = requireNotNull(restored.beginCapture())

        assertEquals(10L, next.expectedRevision)
        assertEquals(1, restored.state.value.savedCount)
        assertEquals("session-project-a", restored.state.value.identity.sessionToken)
    }

    @Test
    fun rejectedPublicationDeletesStagingAndKeepsRevision() {
        val controller = controller(projectId = "project-a", revision = 3)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        attempt.stagedFile.writeText("reject-me")
        controller.onImageSaved(attempt, metadata())

        assertTrue(
            controller.onCommit(
                attempt.captureToken,
                CaptureCommitOutcome.Rejected("Project revision changed before publication."),
            ),
        )

        assertFalse(attempt.stagedFile.exists())
        assertEquals(3L, controller.state.value.expectedRevision)
        assertEquals(0, controller.state.value.savedCount)
        assertEquals(CaptureSessionPhase.READY, controller.state.value.phase)
    }

    @Test
    fun duplicateSavedCallbackCannotDeleteFileWhilePublisherReadsIt() {
        val controller = controller(projectId = "project-a", revision = 1)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        attempt.stagedFile.writeText("publisher-is-reading")

        assertTrue(controller.onImageSaved(attempt, metadata()) is SavedImageDecision.Deliver)
        assertTrue(controller.onImageSaved(attempt, metadata()) is SavedImageDecision.Discarded)
        assertTrue(attempt.stagedFile.exists())

        assertTrue(controller.onCommit(attempt.captureToken, CaptureCommitOutcome.Accepted(2)))
        assertTrue(attempt.stagedFile.exists())
        attempt.stagedFile.delete()
    }

    @Test
    fun nonAdvancingCommittedRevisionIsRejectedAndCleaned() {
        val controller = controller(projectId = "project-a", revision = 6)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        attempt.stagedFile.writeText("invalid-commit")
        controller.onImageSaved(attempt, metadata())

        assertFalse(controller.onCommit(attempt.captureToken, CaptureCommitOutcome.Accepted(6)))
        assertFalse(attempt.stagedFile.exists())
        assertEquals(6L, controller.state.value.expectedRevision)
        assertEquals(0, controller.state.value.savedCount)
    }

    @Test
    fun controllableAdapterReproducesDelayedCameraCompletion() {
        val controller = controller(projectId = "project-a", revision = 0)
        val adapter = DelayedAdapter()
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        adapter.takePicture(attempt, object : CaptureAdapter.Callback {
            override fun onSaved(metadata: CaptureMetadataSnapshot) {
                controller.onImageSaved(attempt, metadata)
            }

            override fun onError(message: String) {
                controller.onCaptureError(attempt, message)
            }
        })

        controller.close(CaptureCloseReason.DISPOSED)
        attempt.stagedFile.writeText("late-camera-bytes")
        adapter.complete(metadata())

        assertFalse(attempt.stagedFile.exists())
        assertEquals(CaptureSessionPhase.CLOSED, controller.state.value.phase)
    }

    @Test
    fun relaunchPrunesOnlyAgeQualifiedAbandonedSessionDirectories() {
        val activeController = controller(projectId = "active", revision = 0)
        activeController.markReady()
        val active = requireNotNull(activeController.beginCapture())
        active.stagedFile.writeText("active")

        val old = store.create("abandoned-old", "old-shot")
        old.writeText("old")
        val recent = store.create("abandoned-recent", "recent-shot")
        recent.writeText("recent")
        val now = 200_000L
        assertTrue(old.parentFile.setLastModified(1_000L))
        assertTrue(recent.parentFile.setLastModified(199_500L))

        assertEquals(
            1,
            store.discardSessionsExcept(
                activeSessionToken = "session-active",
                minimumAgeMillis = 1_000L,
                nowEpochMillis = now,
            ),
        )
        assertTrue(active.stagedFile.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun replacementSessionDoesNotDeleteRecentFileStillOwnedByDelayedCallback() {
        val oldController = controller(projectId = "old", revision = 0)
        oldController.markReady()
        val delayed = requireNotNull(oldController.beginCapture())
        delayed.stagedFile.writeText("camera-still-completing")
        oldController.close(CaptureCloseReason.USER_BACK)

        val replacement = controller(projectId = "new", revision = 0)
        replacement.markReady()
        val removed = store.discardSessionsExcept(
            activeSessionToken = replacement.identity.sessionToken,
            minimumAgeMillis = 60_000L,
            nowEpochMillis = delayed.stagedFile.parentFile.lastModified() + 1_000L,
        )

        assertEquals(0, removed)
        assertTrue(delayed.stagedFile.exists())
        assertTrue(oldController.onImageSaved(delayed, metadata()) is SavedImageDecision.Discarded)
        assertFalse(delayed.stagedFile.exists())
    }

    @Test
    fun captureErrorCleansOnlyCurrentUndeliveredAttempt() {
        val controller = controller(projectId = "project-a", revision = 5)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture())
        attempt.stagedFile.writeText("partial")

        assertTrue(controller.onCaptureError(attempt, "Camera disconnected."))
        assertFalse(attempt.stagedFile.exists())
        assertEquals(CaptureSessionPhase.READY, controller.state.value.phase)
        assertEquals("Camera disconnected.", controller.state.value.message)
        assertNotNull(controller.beginCapture())
    }

    private fun controller(projectId: String, revision: Long): CaptureSessionController =
        CaptureSessionController(
            identity = CaptureSessionIdentity(
                projectId = projectId,
                initialRevision = revision,
                sessionToken = "session-$projectId",
            ),
            stagingStore = store,
            captureTokenFactory = { "capture-${++token}" },
        )

    private fun metadata() = CaptureMetadataSnapshot(
        targetRotation = 0,
        capturedAtEpochMillis = 1234L,
    )

    private class DelayedAdapter : CaptureAdapter {
        private var callback: CaptureAdapter.Callback? = null

        override fun takePicture(attempt: com.realityforge.scanner.capture.session.CaptureAttempt, callback: CaptureAdapter.Callback) {
            check(this.callback == null)
            this.callback = callback
        }

        fun complete(metadata: CaptureMetadataSnapshot) {
            requireNotNull(callback).onSaved(metadata)
        }
    }
}
