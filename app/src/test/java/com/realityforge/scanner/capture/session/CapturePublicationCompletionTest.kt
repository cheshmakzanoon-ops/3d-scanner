package com.realityforge.scanner.capture.session

import com.realityforge.scanner.data.importing.ImportPublicationResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CapturePublicationCompletionTest {
    private suspend fun fixture(test: suspend (CaptureStagingStore, CaptureSessionController, CapturedPhoto, File) -> Unit) {
        val root = Files.createTempDirectory("capture-owned-").toFile()
        val original = Files.createTempFile("durable-original-", ".image").toFile().apply { writeText("durable-original") }
        try {
            val staging = CaptureStagingStore(root)
            val controller = CaptureSessionController(CaptureSessionIdentity("project", 7, "session"), staging)
            controller.markReady()
            val attempt = requireNotNull(controller.beginCapture())
            attempt.stagedFile.writeText("camera-source")
            val photo = (controller.onImageSaved(attempt, CaptureMetadataSnapshot(capturedAtEpochMillis = 1)) as SavedImageDecision.Deliver).photo
            test(staging, controller, photo, original)
            assertEquals("durable-original", original.readText())
        } finally { root.deleteRecursively(); original.delete() }
    }

    @Test fun importedMappingGrantsExplicitCopiedOwnershipOnlyForAdvancingRevision() {
        val accepted = ImportPublicationResult.Imported("photo", 8).toCaptureCommitOutcome(7) as CaptureCommitOutcome.Accepted
        assertEquals(8L, accepted.committedRevision)
        assertEquals(CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL, accepted.stagingDisposition)
        for (revision in listOf(6L, 7L, 9L)) assertTrue(ImportPublicationResult.Imported("photo", revision).toCaptureCommitOutcome(7) is CaptureCommitOutcome.Uncertain)
        assertTrue(ImportPublicationResult.Imported("photo", Long.MAX_VALUE).toCaptureCommitOutcome(Long.MAX_VALUE) is CaptureCommitOutcome.Uncertain)
    }
    @Test fun allOtherImportResultsHaveDistinctHonestMappings() {
        assertEquals(CaptureCommitOutcome.Duplicate(7), ImportPublicationResult.Duplicate("photo", 7).toCaptureCommitOutcome(7))
        assertTrue(ImportPublicationResult.Duplicate("photo", 8).toCaptureCommitOutcome(7) is CaptureCommitOutcome.Uncertain)
        assertEquals(CaptureCommitOutcome.Rejected("not committed"), ImportPublicationResult.Rejected("BAD", "not committed", 7).toCaptureCommitOutcome(7))
        assertEquals(CaptureCommitOutcome.Uncertain("reconcile"), ImportPublicationResult.PendingRecovery("photo", "reconcile", 7).toCaptureCommitOutcome(7))
    }
    @Test fun copiedSourceExistsDuringGuidanceAndIsReleasedOnlyAfterIt() = runBlocking {
        fixture { staging, controller, photo, _ ->
            var guidance = false
            finishCapturePublication(photo, ImportPublicationResult.Imported("photo", 8).toCaptureCommitOutcome(7), controller, staging,
                acceptedGuidance = { assertTrue(photo.stagedFile.exists()); guidance = true }, onGuidanceFailure = { fail() })
            assertTrue(guidance); assertFalse(photo.stagedFile.exists())
            assertEquals(8L, controller.state.value.expectedRevision); assertEquals(1, controller.state.value.savedCount)
        }
    }
    @Test fun legacyPublisherOwnedAcceptanceRetainsExistingOwnershipContract() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, CaptureCommitOutcome.Accepted(8), controller, staging, {}, { fail() })
            assertTrue(photo.stagedFile.exists()); assertEquals(1, controller.state.value.savedCount)
        }
    }
    @Test fun guidanceCancellationDoesNotUndoAcceptanceOrPreventConfirmedCleanup() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, ImportPublicationResult.Imported("photo", 8).toCaptureCommitOutcome(7), controller, staging,
                { throw CancellationException("optional guidance stopped") }, { fail() })
            assertFalse(photo.stagedFile.exists()); assertEquals(1, controller.state.value.savedCount)
        }
    }
    @Test fun duplicateSkipsGuidancePreservesRevisionCountAndUnverifiedSource() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, ImportPublicationResult.Duplicate("photo", 7).toCaptureCommitOutcome(7), controller, staging,
                { fail("Duplicate is not a newly accepted photograph") }, { fail() })
            assertTrue(photo.stagedFile.exists()); assertEquals(7L, controller.state.value.expectedRevision)
            assertEquals(0, controller.state.value.savedCount); assertEquals(CaptureSessionPhase.READY, controller.state.value.phase)
            assertTrue(controller.state.value.message.orEmpty().contains("no new photo"))
            assertNotNull(controller.beginCapture())
        }
    }
    @Test fun uncertainPublicationRetainsSourceAndCannotBeReopenedByLifecycleOrCheckpoint() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, ImportPublicationResult.PendingRecovery("photo", "reconcile", 7).toCaptureCommitOutcome(7),
                controller, staging, { fail() }, { fail() })
            assertTrue(photo.stagedFile.exists()); assertEquals(0, controller.state.value.savedCount)
            controller.markBinding(); controller.markReady(); controller.markWaitingForPermission(); controller.markReady()
            assertEquals(CaptureSessionPhase.UNAVAILABLE, controller.state.value.phase)
            assertNull(controller.beginCapture())
            val recreated = CaptureSessionController(controller.identity, staging, controller.state.value.checkpoint())
            recreated.markReady(); assertNull(recreated.beginCapture()); assertTrue(recreated.state.value.requiresRecovery)
        }
    }
    @Test fun cleanupFailurePreservesAcceptedResultAndReportsNonfatalMessage() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, ImportPublicationResult.Imported("photo", 8).toCaptureCommitOutcome(7), controller, staging,
                {}, { fail() }, discard = { throw java.io.IOException("cleanup failed") })
            assertTrue(photo.stagedFile.exists()); assertEquals(8L, controller.state.value.expectedRevision)
            assertEquals(1, controller.state.value.savedCount); assertTrue(controller.state.value.message.orEmpty().contains("Photograph saved"))
        }
    }
    @Test fun malformedAcceptanceRetainsSourceAndDoesNotRunGuidance() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, CaptureCommitOutcome.Accepted(7, CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL),
                controller, staging, { fail() }, { fail() })
            assertTrue(photo.stagedFile.exists()); assertTrue(controller.state.value.requiresRecovery)
            assertEquals(0, controller.state.value.savedCount)
        }
    }
    @Test fun staleCompletionCannotDeleteNewPendingCapture() = runBlocking {
        fixture { staging, controller, photo, _ ->
            assertTrue(controller.onCommit(photo.captureToken, CaptureCommitOutcome.Accepted(8)))
            val next = requireNotNull(controller.beginCapture()).also { it.stagedFile.writeText("new-in-flight") }
            finishCapturePublication(photo, CaptureCommitOutcome.Accepted(8, CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL),
                controller, staging, {}, { fail() })
            assertTrue(photo.stagedFile.exists()); assertEquals("new-in-flight", next.stagedFile.readText())
        }
    }
    @Test fun unownedFileIsNeverReleasedEvenWithCopiedDisposition() = runBlocking {
        fixture { staging, controller, photo, original ->
            finishCapturePublication(photo.copy(stagedFile = original), CaptureCommitOutcome.Accepted(8, CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL),
                controller, staging, {}, { fail() }, discard = { fail("Unowned path must never reach cleanup"); false })
            assertTrue(original.exists())
        }
    }
    @Test fun realGuidanceJobCancellationStillFinalizesProvenAcceptance() = runBlocking {
        fixture { staging, controller, photo, _ ->
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                finishCapturePublication(photo, ImportPublicationResult.Imported("photo", 8).toCaptureCommitOutcome(7),
                    controller, staging, { entered.complete(Unit); awaitCancellation() }, { fail() })
            }
            entered.await(); job.cancelAndJoin()
            assertFalse(photo.stagedFile.exists()); assertEquals(1, controller.state.value.savedCount)
        }
    }
    @Test fun wrongOwnedPathCannotFinalizeOrDeleteEitherSource() = runBlocking {
        fixture { staging, controller, photo, _ ->
            val other = File(photo.stagedFile.parentFile, "another-owned.jpg").apply { writeText("other-source") }
            finishCapturePublication(photo.copy(stagedFile = other), CaptureCommitOutcome.Accepted(8, CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL),
                controller, staging, {}, { fail() }, discard = { fail("Identity mismatch must not reach cleanup"); false })
            assertTrue(photo.stagedFile.exists()); assertEquals("other-source", other.readText())
            assertEquals(0, controller.state.value.savedCount)
            assertEquals(CaptureSessionPhase.COMMITTING, controller.state.value.phase)
        }
    }
    @Test fun invalidDuplicateRevisionRetainsAndBlocksSession() = runBlocking {
        fixture { staging, controller, photo, _ ->
            finishCapturePublication(photo, CaptureCommitOutcome.Duplicate(8), controller, staging, { fail() }, { fail() })
            assertTrue(photo.stagedFile.exists()); assertEquals(7L, controller.state.value.expectedRevision)
            assertEquals(0, controller.state.value.savedCount); controller.markReady(); assertNull(controller.beginCapture())
        }
    }

}
