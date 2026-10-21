package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCurationUndoIdentityTest {
    @Test fun invalidUndoIdentityCannotCommit() = runBlocking {
        val store = MemoryStore(fixture()); val svc = service(store, undoId = { "invalid undo id" })
        val result = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, CurationRevision(7, 0), "request", "photo-1", PhotoCurationReason(PhotoExclusionCode.BLUR))).rejected()
        assertEquals("INVALID_UNDO_IDENTITY", (result.failure as PhotoCurationFailure.InvalidCommand).code)
        assertEquals(0, store.commits); assertEquals(CurationRevision(7, 0), store.snapshot().revision)
    }
}
