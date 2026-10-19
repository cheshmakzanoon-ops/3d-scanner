package com.realityforge.scanner.agents.a007

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.data.jobs.IsolatedJobStoreDatabase
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.JobTransitionRejectedException
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableJobStoreStateDeviceTest : DurableJobStoreDeviceSupport() {
    @Test
    fun terminalAndPausedStatesSurviveDatabaseRestartWithDistinctMeanings() = runBlocking {
        val databaseName = "agent-007-states-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val expected = linkedMapOf<JobId, ReconstructionJobState>()
        try {
            val firstDb = Room.databaseBuilder(
                context,
                IsolatedJobStoreDatabase::class.java,
                databaseName,
            ).build()
            try {
                val store = store(firstDb, "states")

                val completed = store.enqueue(input("completed-project"), 10)
                val completedLease = requireNotNull(store.acquireNext("completed-worker", 11, 100)).lease
                store.complete(completedLease, "completed-result", hash("completed"), 12)
                expected[completed.jobId] = ReconstructionJobState.COMPLETED

                val failed = store.enqueue(input("failed-project"), 20)
                val failedLease = requireNotNull(store.acquireNext("failed-worker", 21, 100)).lease
                store.fail(failedLease, "NATIVE_FAILURE", 22)
                expected[failed.jobId] = ReconstructionJobState.FAILED

                val blocked = store.enqueue(input("blocked-project"), 30)
                val blockedLease = requireNotNull(store.acquireNext("blocked-worker", 31, 100)).lease
                store.blockExternal(blockedLease, "DATASET_REQUIRED", 32)
                expected[blocked.jobId] = ReconstructionJobState.BLOCKED_EXTERNAL

                val paused = store.enqueue(input("paused-project"), 40)
                store.pause(paused.jobId, 41)
                expected[paused.jobId] = ReconstructionJobState.PAUSED

                val cancelled = store.enqueue(input("cancelled-project"), 50)
                store.cancel(cancelled.jobId, 51)
                expected[cancelled.jobId] = ReconstructionJobState.CANCELLED

                val stale = store.enqueue(input("stale-project"), 60)
                store.enqueue(
                    input("stale-project").copy(
                        projectRevision = 2,
                        requestId = "request-stale-2",
                        inputSha256 = "8".repeat(64),
                    ),
                    61,
                )
                expected[stale.jobId] = ReconstructionJobState.STALE
            } finally {
                firstDb.close()
            }

            val secondDb = Room.databaseBuilder(
                context,
                IsolatedJobStoreDatabase::class.java,
                databaseName,
            ).build()
            try {
                val store = store(secondDb, "states-reopened")
                expected.forEach { (jobId, state) ->
                    assertEquals(state, requireNotNull(store.snapshot(jobId)).state)
                }
                assertTrue(expected.values.containsAll(ReconstructionJobState.values().filter {
                    it in setOf(
                        ReconstructionJobState.COMPLETED,
                        ReconstructionJobState.FAILED,
                        ReconstructionJobState.PAUSED,
                        ReconstructionJobState.CANCELLED,
                        ReconstructionJobState.STALE,
                        ReconstructionJobState.BLOCKED_EXTERNAL,
                    )
                }))
            } finally {
                secondDb.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun clockRollbackCannotAcquireOrSupersedeQueuedJob() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            IsolatedJobStoreDatabase::class.java,
        ).build()
        try {
            val store = store(database, "clock-rollback")
            val queued = store.enqueue(input("clock-rollback-project"), 100)
            store.pause(queued.jobId, 110)
            store.resume(queued.jobId, 120)
            val before = requireNotNull(store.snapshot(queued.jobId))

            expectTransitionRejected {
                store.acquireNext("clock-rollback-worker", 119, 100)
            }
            assertEquals(before, requireNotNull(store.snapshot(queued.jobId)))

            val changedInput = input("clock-rollback-project").copy(
                projectRevision = 2,
                requestId = "request-clock-rollback-2",
                inputSha256 = "7".repeat(64),
            )
            expectTransitionRejected {
                store.enqueue(changedInput, 119)
            }
            assertEquals(before, requireNotNull(store.snapshot(queued.jobId)))

            val replacement = store.enqueue(changedInput, 121)
            assertNotEquals(queued.jobId, replacement.jobId)
            assertEquals(ReconstructionJobState.QUEUED, replacement.state)
            assertEquals(
                ReconstructionJobState.STALE,
                requireNotNull(store.snapshot(queued.jobId)).state,
            )
        } finally {
            database.close()
        }
    }

    private suspend fun expectTransitionRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected durable timestamp rollback rejection")
        } catch (_: JobTransitionRejectedException) {
            // Expected typed monotonic-time rejection.
        }
    }
}
