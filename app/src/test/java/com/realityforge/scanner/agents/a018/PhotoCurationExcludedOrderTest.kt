package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCurationExcludedOrderTest {
    @Test fun excludedOnlyOrderDoesNotInvalidateInputs() = runBlocking {
        val start = fixture(mapOf("photo-2" to PhotoCurationReason(PhotoExclusionCode.BLUR), "photo-4" to PhotoCurationReason(PhotoExclusionCode.DUPLICATE_VIEW)))
        val store = MemoryStore(start); val svc = service(store); val before = requireNotNull(svc.selectedPhotos(PROJECT_ID))
        val reordered = svc.execute(PhotoCurationCommand.Reorder(PROJECT_ID, before.revision, "order", listOf("photo-2", "photo-1", "photo-4", "photo-3"))).applied()
        assertEquals(CurationRevision(7, 1), reordered.snapshot.revision); assertEquals(before.inputFingerprint, reordered.selectedPhotoSet.inputFingerprint)
        assertEquals(listOf("photo-1", "photo-3"), reordered.selectedPhotoSet.photos.map { it.id })
    }
}
