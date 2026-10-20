package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCurationSelectionTest {
    @Test fun reasonOnlyMetadataDoesNotInvalidateInputs() = runBlocking {
        val store = MemoryStore(fixture()); val svc = service(store)
        val initial = requireNotNull(svc.selectedPhotos(PROJECT_ID))
        val lease = (initial.admitForReconstruction() as ReconstructionAdmission.Ready).lease
        val excluded = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, initial.revision, "exclude-1", "photo-2", PhotoCurationReason(PhotoExclusionCode.BLUR))).applied()
        assertEquals(CurationRevision(8, 1), excluded.snapshot.revision); assertTrue(excluded.change.projectRevisionChanged)
        assertTrue(ReconstructionSelectionGuard.verifyPublication(lease, excluded.selectedPhotoSet) is PublicationSelectionCheck.Stale)
        val duplicate = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, excluded.snapshot.revision, "exclude-2", "photo-2", PhotoCurationReason(PhotoExclusionCode.BLUR)))
        assertTrue(duplicate is PhotoCurationOutcome.Unchanged); assertEquals(1, store.commits)
        val reason = svc.execute(PhotoCurationCommand.Exclude(PROJECT_ID, excluded.snapshot.revision, "reason", "photo-2", PhotoCurationReason(PhotoExclusionCode.USER_CHOICE, "Sharper angle"))).applied()
        assertEquals(CurationRevision(8, 2), reason.snapshot.revision); assertEquals(CurationChangeKind.METADATA_ONLY, reason.change.kind)
        assertFalse(reason.change.projectRevisionChanged); assertEquals(excluded.selectedPhotoSet.inputFingerprint, reason.selectedPhotoSet.inputFingerprint)
    }
}
