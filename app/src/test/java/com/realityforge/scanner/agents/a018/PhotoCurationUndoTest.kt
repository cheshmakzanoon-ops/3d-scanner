package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCurationUndoTest {
    @Test fun undoRestoresSelectionAndCannotOverwriteInterveningChange() = runBlocking {
        val store = MemoryStore(fixture()); val svc = service(store); val initial = requireNotNull(svc.selectedPhotos(PROJECT_ID))
        val excluded = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, initial.revision, "exclude", "photo-3", PhotoCurationReason(PhotoExclusionCode.LOW_OVERLAP))).applied()
        val undone = svc.execute(PhotoCurationCommand.Undo(PROJECT_ID, excluded.snapshot.revision, "undo", requireNotNull(excluded.undoToken))).applied()
        assertEquals(4, undone.selectedPhotoSet.photos.size); assertEquals(CurationRevision(9, 2), undone.snapshot.revision)
        val again = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, undone.snapshot.revision, "again", "photo-2", PhotoCurationReason(PhotoExclusionCode.BLUR))).applied()
        val reordered = svc.execute(PhotoCurationCommand.Reorder(PROJECT_ID, again.snapshot.revision, "order", listOf("photo-4", "photo-1", "photo-2", "photo-3"))).applied()
        val stale = svc.execute(PhotoCurationCommand.Undo(PROJECT_ID, reordered.snapshot.revision, "stale", requireNotNull(again.undoToken))).rejected()
        assertEquals("UNDO_REVISION_CHANGED", (stale.failure as PhotoCurationFailure.UndoNotApplicable).code)
    }
}
