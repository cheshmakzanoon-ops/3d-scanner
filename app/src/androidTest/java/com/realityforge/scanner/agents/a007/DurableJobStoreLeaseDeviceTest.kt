package com.realityforge.scanner.agents.a007

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.data.jobs.IsolatedJobStoreDatabase
import com.realityforge.scanner.data.jobs.JobCommandDisposition
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableJobStoreLeaseDeviceTest : DurableJobStoreDeviceSupport() {
    @Test fun checkpointRestartIsFencedAndRequiresExplicitBackwardRecovery() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, IsolatedJobStoreDatabase::class.java).build()
        try {
            val store = store(database, "rewind")
            store.enqueue(input("rewind-project"), 100)
            val acquired = requireNotNull(store.acquireNext("worker-a", 101, 100))
            val prepared = com.realityforge.scanner.data.jobs.JobStageCursor(
                com.realityforge.scanner.data.jobs.ReconstructionStage.PREPARED, "input-checkpoint", hash("prepared"))
            val accepted = store.acceptStage(acquired.lease, prepared, 102)
            val reset = store.rewindCheckpoint(accepted.lease, com.realityforge.scanner.data.jobs.JobStageCursor.initial(),
                "CHECKPOINT_REVALIDATION_FAILED", 103)
            assertEquals(com.realityforge.scanner.data.jobs.ReconstructionStage.CREATED, reset.snapshot.cursor.acceptedStage)
            assertNull(reset.snapshot.progress)
            expectLeaseRejected {
                store.rewindCheckpoint(accepted.lease, com.realityforge.scanner.data.jobs.JobStageCursor.initial(), "STALE_WORKER", 104)
            }
            try {
                store.rewindCheckpoint(reset.lease, prepared, "INVALID_ADVANCE", 104)
                org.junit.Assert.fail("Recovery advanced an unaccepted stage.")
            } catch (_: com.realityforge.scanner.data.jobs.JobTransitionRejectedException) { }
            val replay = store.acceptStage(reset.lease, prepared, 105)
            assertEquals(prepared, replay.snapshot.cursor)
        } finally { database.close() }
    }

    @Test
    fun concurrentWorkersCannotShareLeaseAndSupersededAttemptCannotCommit() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, IsolatedJobStoreDatabase::class.java).build()
        try {
            val firstStore = store(database, "first")
            val secondStore = store(database, "second")
            val queued = firstStore.enqueue(input("lease-project"), nowEpochMillis = 100)
            val attempts = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { firstStore.acquireNext("worker-a", 101, 100) },
                    async(Dispatchers.IO) { secondStore.acquireNext("worker-b", 101, 100) },
                ).awaitAll()
            }
            assertEquals(1, attempts.count { it != null })
            val acquired = requireNotNull(attempts.single { it != null })
            assertEquals(queued.jobId, acquired.snapshot.jobId)
            assertNull(secondStore.acquireNext("worker-c", 102, 100))

            val renewed = firstStore.renew(acquired.lease, 103, 100)
            expectLeaseRejected {
                secondStore.complete(acquired.lease, "stale-result", hash("stale"), 104)
            }
            val relinquished = firstStore.relinquish(
                renewed.lease,
                reasonCode = "SERVICE_TIMEOUT",
                nowEpochMillis = 105,
            )
            assertEquals(ReconstructionJobState.PAUSED, relinquished.state)
            assertNull(relinquished.lease)

            assertEquals(
                JobCommandDisposition.APPLIED,
                secondStore.resume(queued.jobId, 106).disposition,
            )
            val replacement = requireNotNull(secondStore.acquireNext("worker-b", 107, 100))
            assertEquals(2L, replacement.lease.attempt)
            assertNotEquals(acquired.lease.token, replacement.lease.token)
            expectLeaseRejected {
                firstStore.complete(acquired.lease, "stale-result", hash("stale"), 108)
            }
            val completed = secondStore.complete(
                replacement.lease,
                resultArtifactIdentity = "verified-result",
                resultArtifactSha256 = hash("verified-result"),
                nowEpochMillis = 109,
            )
            assertEquals(ReconstructionJobState.COMPLETED, completed.state)
            assertEquals(2L, completed.attempt)
        } finally {
            database.close()
        }
    }
}
