package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.data.deletion.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeletionPayloadTest {
    @Test fun originalAndCurationConfirmationRemainBoundAcrossPersistenceAndStateChanges() {
        val pending = PendingDeletion(OPERATION, DeletionScope.Photo(PROJECT, PHOTO), 9, 100,
            DeletionPhase.TOMBSTONED, 0, listOf(OwnedDeletionTarget.ProjectRelativeNode(PROJECT, "originals/$PHOTO.image")),
            updatedAtEpochMillis = 100, expectedOriginalSha256 = "a".repeat(64), expectedRemovalRequestId = "remove-request")
        assertEquals(pending, DeletionPayload.decode(DeletionPayload.entity(pending)))
        val cleaning = DeletionStateReducer.apply(pending, DeletionTransition.CleaningFiles(101))
        assertEquals(pending.expectedOriginalSha256, DeletionPayload.decode(DeletionPayload.entity(cleaning)).expectedOriginalSha256)
        assertEquals(pending.expectedRemovalRequestId, DeletionPayload.decode(DeletionPayload.entity(cleaning)).expectedRemovalRequestId)
    }

    @Test fun storedCountersCannotCoerceNumericStringsAndConfirmationCannotLoseItsHash() {
        val pending = PendingDeletion(OPERATION, DeletionScope.Project(PROJECT), 9, 100,
            DeletionPhase.TOMBSTONED, 0, listOf(OwnedDeletionTarget.ProjectTree(PROJECT)), updatedAtEpochMillis = 100)
        val row = DeletionPayload.entity(pending)
        val corrupt = row.copy(payload = JSONObject(row.payload).put("expectedRevision", "9").toString())
        assertThrows(IllegalArgumentException::class.java) { DeletionPayload.decode(corrupt) }
        assertThrows(IllegalArgumentException::class.java) {
            DeletionIntent(OPERATION, DeletionScope.Photo(PROJECT, PHOTO), 9, 100,
                expectedRemovalRequestId = "remove-request")
        }
    }

    companion object {
        const val OPERATION = "00000000-0000-0000-0000-000000000221"
        const val PROJECT = "00000000-0000-0000-0000-000000000222"
        const val PHOTO = "00000000-0000-0000-0000-000000000223"
    }
}
