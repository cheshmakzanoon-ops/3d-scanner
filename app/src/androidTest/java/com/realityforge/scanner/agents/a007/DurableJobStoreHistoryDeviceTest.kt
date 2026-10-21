package com.realityforge.scanner.agents.a007

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.data.jobs.IsolatedJobStoreDatabase
import com.realityforge.scanner.data.jobs.JobCommandDisposition
import com.realityforge.scanner.data.jobs.JobEventKind
import com.realityforge.scanner.data.jobs.JobProgress
import com.realityforge.scanner.data.jobs.JobProgressUnit
import com.realityforge.scanner.data.jobs.ReconstructionStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableJobStoreHistoryDeviceTest : DurableJobStoreDeviceSupport() {
    @Test
    fun progressHistoryIsBoundedAndUserCommandsAreIdempotent() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, IsolatedJobStoreDatabase::class.java).build()
        try {
            val store = store(database, "history", historyLimit = 4)
            val queued = store.enqueue(input("history-project"), 0)
            var leased = requireNotNull(store.acquireNext("history-worker", 10, 10_000))
            leased = store.recordProgress(
                leased.lease,
                JobProgress(ReconstructionStage.PREPARED, 1, 100, JobProgressUnit.PHOTOS),
                11,
            )
            val versionAfterFirstProgress = leased.snapshot.stateVersion
            leased = store.recordProgress(
                leased.lease,
                JobProgress(ReconstructionStage.PREPARED, 2, 100, JobProgressUnit.PHOTOS),
                12,
            )
            assertEquals(versionAfterFirstProgress, leased.snapshot.stateVersion)
            leased = store.recordProgress(
                leased.lease,
                JobProgress(ReconstructionStage.PREPARED, 3, 100, JobProgressUnit.PHOTOS),
                1_012,
            )
            assertTrue(leased.snapshot.stateVersion > versionAfterFirstProgress)

            assertEquals(JobCommandDisposition.APPLIED, store.pause(queued.jobId, 1_013).disposition)
            assertEquals(
                JobCommandDisposition.ALREADY_SATISFIED,
                store.pause(queued.jobId, 1_014).disposition,
            )
            val history = store.observeHistory(queued.jobId, 64).first()
            assertEquals(4, history.size)
            assertEquals(JobEventKind.PAUSED, history.last().kind)
            assertEquals(2, history.count { it.kind == JobEventKind.PROGRESS })
            assertFalse(history.any { it.progress?.unit?.name == "PIXELS" })
        } finally {
            database.close()
        }
    }
}
