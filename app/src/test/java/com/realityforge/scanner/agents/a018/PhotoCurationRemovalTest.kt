package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoCurationRemovalTest {
    @Test fun stagedRemovalPreservesIdentityRestoresAndCompletesOnlyWithExactProof() = runBlocking {
        val hashes = fixture().photos.associate { it.id to it.sha256 }; val ids = ArrayDeque(listOf("remove-1", "remove-2"))
        val store = MemoryStore(fixture()); val svc = service(store, { ids.removeFirst() })
        val before = requireNotNull(svc.selectedPhotos(PROJECT_ID))
        val staged = svc.execute(PhotoCurationCommand.RequestRemoval(PROJECT_ID, before.revision, "stage", "photo-2", PhotoRemovalReason(PhotoRemovalCode.DUPLICATE))).applied()
        assertEquals(4, staged.selectedPhotoSet.retainedOriginalCount); assertEquals(1, staged.selectedPhotoSet.pendingRemovalCount)
        val directive = staged.removalDirectives.single() as PhotoRemovalDirective.Stage
        assertEquals("remove-1", directive.request.requestId); assertEquals(hashes["photo-2"], directive.photoSha256)
        val wrong = svc.execute(PhotoCurationCommand.CompleteRemoval(PROJECT_ID, staged.snapshot.revision, "wrong", "photo-2", "other", requireNotNull(hashes["photo-2"]))).rejected()
        assertEquals("REMOVAL_REQUEST_MISMATCH", (wrong.failure as PhotoCurationFailure.InvalidCommand).code); assertEquals(1, store.commits)
        val restored = svc.execute(PhotoCurationCommand.RestorePendingRemoval(PROJECT_ID, staged.snapshot.revision, "restore", "photo-2")).applied()
        assertEquals("remove-1", (restored.removalDirectives.single() as PhotoRemovalDirective.Cancel).removalRequestId)
        assertEquals(hashes, restored.snapshot.photos.associate { it.id to it.sha256 })
        val restaged = svc.execute(PhotoCurationCommand.RequestRemoval(PROJECT_ID, restored.snapshot.revision, "stage-2", "photo-2", PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST))).applied()
        val completed = svc.execute(PhotoCurationCommand.CompleteRemoval(PROJECT_ID, restaged.snapshot.revision, "complete", "photo-2", "remove-2", requireNotNull(hashes["photo-2"]))).applied()
        assertNull(completed.undoToken); assertEquals(listOf("photo-1", "photo-3", "photo-4"), completed.snapshot.photos.map { it.id })
        assertEquals(3, completed.selectedPhotoSet.retainedOriginalCount); assertInvariant(completed.snapshot, hashes)
    }
}
