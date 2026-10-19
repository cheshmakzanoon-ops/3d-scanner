package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCurationRevisionSemanticsTest {
    @Test
    fun removalLifecycleIsDistinctAndPendingOnlyOrderDoesNotInvalidateSelectedInputs() = runBlocking {
        val removalIds = ArrayDeque(listOf("removal-1", "removal-2"))
        val store = MemoryStore(fixture())
        val svc = service(store, removalId = { removalIds.removeFirst() })
        val initial = requireNotNull(svc.selectedPhotos(PROJECT_ID))
        val lease = (initial.admitForReconstruction() as ReconstructionAdmission.Ready).lease

        val stageTwo = svc.execute(
            PhotoCurationCommand.RequestRemoval(
                PROJECT_ID,
                initial.revision,
                "stage-2",
                "photo-2",
                PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            ),
        ).applied()
        assertEquals(CurationChangeKind.REMOVAL_LIFECYCLE, stageTwo.change.kind)
        assertTrue(stageTwo.change.projectRevisionChanged)
        assertTrue(stageTwo.change.selectedInputChanged)
        assertEquals(4, stageTwo.selectedPhotoSet.retainedOriginalCount)
        assertTrue(ReconstructionSelectionGuard.verifyPublication(lease, stageTwo.selectedPhotoSet) is PublicationSelectionCheck.Stale)

        val stageFour = svc.execute(
            PhotoCurationCommand.RequestRemoval(
                PROJECT_ID,
                stageTwo.snapshot.revision,
                "stage-4",
                "photo-4",
                PhotoRemovalReason(PhotoRemovalCode.DUPLICATE),
            ),
        ).applied()
        assertEquals(CurationChangeKind.REMOVAL_LIFECYCLE, stageFour.change.kind)
        assertEquals(listOf("photo-1", "photo-3"), stageFour.selectedPhotoSet.photos.map { it.id })

        val projectRevisionBefore = stageFour.snapshot.revision.projectRevision
        val selectionFingerprintBefore = stageFour.selectedPhotoSet.inputFingerprint
        val reordered = svc.execute(
            PhotoCurationCommand.Reorder(
                PROJECT_ID,
                stageFour.snapshot.revision,
                "pending-only-order",
                listOf("photo-4", "photo-1", "photo-2", "photo-3"),
            ),
        ).applied()
        assertEquals(CurationChangeKind.METADATA_ONLY, reordered.change.kind)
        assertFalse(reordered.change.projectRevisionChanged)
        assertFalse(reordered.change.selectedInputChanged)
        assertEquals(projectRevisionBefore, reordered.snapshot.revision.projectRevision)
        assertEquals(selectionFingerprintBefore, reordered.selectedPhotoSet.inputFingerprint)

        val restored = svc.execute(
            PhotoCurationCommand.RestorePendingRemoval(
                PROJECT_ID,
                reordered.snapshot.revision,
                "restore-2",
                "photo-2",
            ),
        ).applied()
        assertEquals(CurationChangeKind.REMOVAL_LIFECYCLE, restored.change.kind)
        assertTrue(restored.change.projectRevisionChanged)
        assertTrue(restored.change.selectedInputChanged)
        assertTrue(restored.removalDirectives.single() is PhotoRemovalDirective.Cancel)

        val pendingFour = restored.snapshot.photos.single { it.id == "photo-4" }
        val completed = svc.execute(
            PhotoCurationCommand.CompleteRemoval(
                PROJECT_ID,
                restored.snapshot.revision,
                "complete-4",
                "photo-4",
                requireNotNull(pendingFour.pendingRemoval).requestId,
                pendingFour.sha256,
            ),
        ).applied()
        assertEquals(CurationChangeKind.RETAINED_ORIGINALS, completed.change.kind)
        assertTrue(completed.change.projectRevisionChanged)
        assertFalse(completed.change.selectedInputChanged)
        assertEquals(3, completed.selectedPhotoSet.retainedOriginalCount)
        assertNull(completed.undoToken)
    }

    @Test
    fun duplicateActiveRemovalIdentityIsRejectedWithoutASecondCommit() = runBlocking {
        val store = MemoryStore(fixture())
        val svc = service(store, removalId = { "shared-removal" })
        val first = svc.execute(
            PhotoCurationCommand.RequestRemoval(
                PROJECT_ID,
                CurationRevision(7, 0),
                "stage-1",
                "photo-1",
                PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            ),
        ).applied()

        val rejected = svc.execute(
            PhotoCurationCommand.RequestRemoval(
                PROJECT_ID,
                first.snapshot.revision,
                "stage-2",
                "photo-2",
                PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            ),
        ).rejected()
        val failure = rejected.failure as PhotoCurationFailure.InvalidCommand
        assertEquals("REMOVAL_REQUEST_DUPLICATE", failure.code)
        assertEquals(1, store.commits)
    }
}
