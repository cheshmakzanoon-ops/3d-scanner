package com.realityforge.scanner.agents.a014

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.capture.permissions.CameraPermissionObservation
import com.realityforge.scanner.capture.permissions.CameraPermissionPolicy
import com.realityforge.scanner.capture.permissions.CameraPermissionStatus
import com.realityforge.scanner.capture.session.CaptureCloseReason
import com.realityforge.scanner.capture.session.CaptureCommitOutcome
import com.realityforge.scanner.capture.session.CaptureMetadataSnapshot
import com.realityforge.scanner.capture.session.CaptureSessionController
import com.realityforge.scanner.capture.session.CaptureSessionIdentity
import com.realityforge.scanner.capture.session.CaptureSessionPhase
import com.realityforge.scanner.capture.session.CaptureStagingStore
import com.realityforge.scanner.capture.session.SavedImageDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureSessionLifecycleDeviceTest {
    @Test
    fun delayedSaveAfterDisposalCannotSurviveOrChangeDestination() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "agent-014-${System.nanoTime()}")
        try {
            val controller = CaptureSessionController(
                CaptureSessionIdentity("project-a", 12, "session-a"),
                CaptureStagingStore(root),
                captureTokenFactory = { "shot-a" },
            )
            controller.markReady()
            val attempt = requireNotNull(controller.beginCapture())
            attempt.stagedFile.writeText("camera-output")

            controller.close(CaptureCloseReason.DISPOSED)
            val result = controller.onImageSaved(
                attempt,
                CaptureMetadataSnapshot(targetRotation = 1, capturedAtEpochMillis = 1),
            )

            assertTrue(result is SavedImageDecision.Discarded)
            assertFalse(attempt.stagedFile.exists())
            assertEquals("project-a", controller.state.value.identity.projectId)
            assertEquals(CaptureSessionPhase.CLOSED, controller.state.value.phase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedShotsRemainInOneSessionAndAdvanceOnlyAfterCommit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "agent-014-${System.nanoTime()}")
        var token = 0
        try {
            val controller = CaptureSessionController(
                CaptureSessionIdentity("project-a", 20, "session-a"),
                CaptureStagingStore(root),
                captureTokenFactory = { "shot-${++token}" },
            )
            controller.markReady()
            repeat(3) { index ->
                val attempt = requireNotNull(controller.beginCapture())
                assertEquals(20L + index, attempt.expectedRevision)
                attempt.stagedFile.writeText("shot-$index")
                assertTrue(
                    controller.onImageSaved(
                        attempt,
                        CaptureMetadataSnapshot(targetRotation = index % 4, capturedAtEpochMillis = index.toLong()),
                    ) is SavedImageDecision.Deliver,
                )
                assertTrue(
                    controller.onCommit(
                        attempt.captureToken,
                        CaptureCommitOutcome.Accepted(21L + index),
                    ),
                )
                attempt.stagedFile.delete()
            }
            assertEquals(3, controller.state.value.savedCount)
            assertEquals(23L, controller.state.value.expectedRevision)
            assertEquals("session-a", controller.state.value.identity.sessionToken)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pauseResumeAndRecreationPreserveIdentityRevisionCountAndRotationEvidence() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "agent-014-${System.nanoTime()}")
        try {
            val identity = CaptureSessionIdentity("project-a", 30, "session-a")
            val first = CaptureSessionController(identity, CaptureStagingStore(root))
            first.markReady()
            first.markPaused()
            assertEquals(CaptureSessionPhase.PAUSED, first.state.value.phase)
            first.markReady()

            val attempt = requireNotNull(first.beginCapture())
            attempt.stagedFile.writeText("rotated-camera-output")
            val decision = first.onImageSaved(
                attempt,
                CaptureMetadataSnapshot(targetRotation = 3, capturedAtEpochMillis = 1),
            )
            assertTrue(decision is SavedImageDecision.Deliver)
            assertEquals(3, (decision as SavedImageDecision.Deliver).photo.metadata.targetRotation)
            assertTrue(first.onCommit(attempt.captureToken, CaptureCommitOutcome.Accepted(31)))
            attempt.stagedFile.delete()

            val checkpoint = first.state.value.checkpoint()
            first.close(CaptureCloseReason.PROCESS_RECREATED)
            val restored = CaptureSessionController(
                identity = identity,
                stagingStore = CaptureStagingStore(root),
                restoredCheckpoint = checkpoint,
            )
            assertEquals("project-a", restored.state.value.identity.projectId)
            assertEquals("session-a", restored.state.value.identity.sessionToken)
            assertEquals(31L, restored.state.value.expectedRevision)
            assertEquals(1, restored.state.value.savedCount)
            restored.markReady()
            assertEquals(31L, requireNotNull(restored.beginCapture()).expectedRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun permissionObservationsCoverDenyRationaleSettingsRegrantAndRevocation() {
        fun status(granted: Boolean, rationale: Boolean, requested: Boolean) =
            CameraPermissionPolicy.evaluate(
                CameraPermissionObservation(granted, rationale, requested, cameraAvailable = true),
            )

        assertEquals(CameraPermissionStatus.REQUESTABLE, status(false, false, false))
        assertEquals(CameraPermissionStatus.RATIONALE_REQUIRED, status(false, true, true))
        assertEquals(CameraPermissionStatus.PERMANENTLY_DENIED, status(false, false, true))
        assertEquals(CameraPermissionStatus.GRANTED, status(true, false, true))
        assertEquals(CameraPermissionStatus.PERMANENTLY_DENIED, status(false, false, true))
    }
}
