package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCurationConcurrencyTest {
    @Test fun concurrentStaleCommandAndPublicationLeaseAreRejected() = runBlocking {
        val store = MemoryStore(fixture()); val a = service(store); val b = service(store)
        val initial = requireNotNull(a.selectedPhotos(PROJECT_ID)); val lease = (initial.admitForReconstruction() as ReconstructionAdmission.Ready).lease
        val winner = a.execute(PhotoCurationCommand.Exclude(PROJECT_ID, initial.revision, "winner", "photo-4", PhotoCurationReason(PhotoExclusionCode.MOVING_SUBJECT))).applied()
        val loser = b.execute(PhotoCurationCommand.Reorder(PROJECT_ID, initial.revision, "loser", listOf("photo-4", "photo-3", "photo-2", "photo-1"))).rejected()
        assertTrue(loser.failure is PhotoCurationFailure.RevisionConflict); assertEquals(1, store.commits)
        val check = ReconstructionSelectionGuard.verifyPublication(lease, winner.selectedPhotoSet) as PublicationSelectionCheck.Stale
        assertEquals("PROJECT_REVISION_CHANGED", check.code)
    }
}
