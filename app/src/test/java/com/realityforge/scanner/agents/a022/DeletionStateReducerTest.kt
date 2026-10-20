package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.data.deletion.CleanupDelta
import com.realityforge.scanner.data.deletion.DeletionFailure
import com.realityforge.scanner.data.deletion.DeletionFailureCode
import com.realityforge.scanner.data.deletion.DeletionPhase
import com.realityforge.scanner.data.deletion.DeletionScope
import com.realityforge.scanner.data.deletion.DeletionStateReducer
import com.realityforge.scanner.data.deletion.DeletionTransition
import com.realityforge.scanner.data.deletion.OwnedDeletionTarget
import com.realityforge.scanner.data.deletion.PendingDeletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class DeletionStateReducerTest {
    @Test
    fun cleanupProofIsRequiredBeforeDatabaseFinalizationAndCompletion() {
        val staged = staged()
        assertThrows(IllegalArgumentException::class.java) {
            DeletionStateReducer.apply(staged, DeletionTransition.FinalizingDatabase(2))
        }

        val cleaning = DeletionStateReducer.apply(staged, DeletionTransition.CleaningFiles(2))
        val filesRemoved = DeletionStateReducer.apply(
            cleaning,
            DeletionTransition.CleanupProgress(
                CleanupDelta(entriesRemoved = 2, bytesReclaimed = 17, entriesVisited = 3),
                moreWork = false,
                atEpochMillis = 3,
            ),
        )
        assertEquals(DeletionPhase.FILES_REMOVED, filesRemoved.phase)
        assertEquals(2L, filesRemoved.entriesRemoved)
        assertEquals(17L, filesRemoved.bytesReclaimed)

        val finalizing = DeletionStateReducer.apply(
            filesRemoved,
            DeletionTransition.FinalizingDatabase(4),
        )
        val completed = DeletionStateReducer.complete(finalizing, 5)
        assertEquals(DeletionPhase.COMPLETED, completed.phase)
        assertEquals(5L, completed.completedAtEpochMillis)
        assertNull(completed.failure)
    }

    @Test
    fun retryableFailurePreservesMeasuredProgressAndNextAttemptCanContinue() {
        val cleaning = DeletionStateReducer.apply(staged(), DeletionTransition.CleaningFiles(2))
        val failed = DeletionStateReducer.apply(
            cleaning,
            DeletionTransition.RetryableFailure(
                DeletionFailure(DeletionFailureCode.IO_DELETE_FAILED, "Could not remove an owned entry."),
                CleanupDelta(1, 11, 2, "project/originals/photo.image"),
                3,
            ),
        )
        assertEquals(DeletionPhase.RETRYABLE_FAILURE, failed.phase)
        assertEquals(1L, failed.entriesRemoved)
        assertEquals(1, failed.attempts)

        val retry = DeletionStateReducer.apply(failed, DeletionTransition.CleaningFiles(4))
        val pending = DeletionStateReducer.apply(
            retry,
            DeletionTransition.CleanupProgress(CleanupDelta(2, 13, 4), true, 5),
        )
        assertEquals(DeletionPhase.CLEANUP_PENDING, pending.phase)
        assertEquals(3L, pending.entriesRemoved)
        assertEquals(24L, pending.bytesReclaimed)
        assertEquals(2, pending.attempts)
        assertNull(pending.failure)
    }

    private fun staged(): PendingDeletion {
        val projectId = UUID.randomUUID().toString()
        return PendingDeletion(
            operationId = UUID.randomUUID().toString(),
            scope = DeletionScope.Project(projectId),
            expectedRevision = 1,
            confirmedAtEpochMillis = 1,
            phase = DeletionPhase.TOMBSTONED,
            stateVersion = 0,
            targets = listOf(OwnedDeletionTarget.ProjectTree(projectId)),
            updatedAtEpochMillis = 1,
        )
    }
}
