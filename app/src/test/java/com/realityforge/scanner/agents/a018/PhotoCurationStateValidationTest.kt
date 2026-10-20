package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhotoCurationStateValidationTest {
    @Test
    fun admissionUsesTheRequestedMinimumAndSelectionCountsCannotDrift() = runBlocking {
        val selected = requireNotNull(service(MemoryStore(fixture())).selectedPhotos(PROJECT_ID))
        val blocked = selected.admitForReconstruction(requiredCount = 5) as ReconstructionAdmission.Blocked
        assertEquals(ReconstructionSelectionStatus.INSUFFICIENT_PHOTOS, blocked.status)
        assertEquals(4, blocked.selectedCount)
        assertEquals(5, blocked.requiredCount)

        expectIllegalArgument { selected.copy(retainedOriginalCount = selected.retainedOriginalCount + 1) }
        expectIllegalArgument { selected.copy(status = ReconstructionSelectionStatus.EMPTY) }
        expectIllegalArgument {
            selected.copy(
                photos = listOf(
                    selected.photos[0].copy(position = 0),
                    selected.photos[0].copy(position = 1),
                ),
                retainedOriginalCount = 2,
                excludedCount = 0,
                pendingRemovalCount = 0,
                status = ReconstructionSelectionStatus.READY,
            )
        }
    }

    @Test
    fun activeRemovalRequestIdentitiesMustBeUniqueWithinAProject() {
        expectIllegalArgument {
            CurationSnapshot(
                PROJECT_ID,
                CurationRevision(1, 0),
                listOf(
                    pendingPhoto("photo-a", "1".padStart(64, '0'), 0, "shared-removal"),
                    pendingPhoto("photo-b", "2".padStart(64, '0'), 1, "shared-removal"),
                ),
            )
        }
    }

    @Test
    fun committedStateFingerprintIncludesThePendingRestoreTarget() = runBlocking {
        val initial = fixture(
            states = mapOf("photo-1" to PhotoCurationReason(PhotoExclusionCode.BLUR)),
        )
        val tamperingStore = object : PhotoCurationStore {
            override suspend fun read(projectId: String): CurationSnapshot? = initial

            override suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult {
                val tamperedPhotos = request.nextSnapshot.photos.map { photo ->
                    if (photo.id != "photo-1") {
                        photo
                    } else {
                        val pending = requireNotNull(photo.pendingRemoval)
                        photo.copy(
                            pendingRemoval = pending.copy(
                                previousState = RestorablePhotoState.INCLUDED,
                                previousExclusion = null,
                            ),
                        )
                    }
                }
                return CurationStoreCommitResult.Committed(request.nextSnapshot.copy(photos = tamperedPhotos))
            }
        }

        val outcome = service(tamperingStore, removalId = { "tampered-removal" }).execute(
            PhotoCurationCommand.RequestRemoval(
                PROJECT_ID,
                initial.revision,
                "stage",
                "photo-1",
                PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            ),
        ).rejected()
        assertTrue(outcome.failure is PhotoCurationFailure.StoreProtocolViolation)
    }

    private fun pendingPhoto(
        id: String,
        sha256: String,
        position: Int,
        removalRequestId: String,
    ) = CuratedPhoto(
        id = id,
        sha256 = sha256,
        position = position,
        state = CuratedPhotoState.REMOVAL_PENDING,
        pendingRemoval = PendingPhotoRemoval(
            requestId = removalRequestId,
            requestedAtEpochMillis = 1,
            reason = PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            previousState = RestorablePhotoState.INCLUDED,
        ),
    )

    private inline fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
