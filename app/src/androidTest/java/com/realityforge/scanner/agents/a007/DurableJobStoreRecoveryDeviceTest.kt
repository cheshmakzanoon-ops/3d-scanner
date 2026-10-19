package com.realityforge.scanner.agents.a007

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.data.jobs.CheckpointRecoveryDecision
import com.realityforge.scanner.data.jobs.IsolatedJobStoreDatabase
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.JobStageCursor
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import com.realityforge.scanner.data.jobs.ReconstructionStage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableJobStoreRecoveryDeviceTest : DurableJobStoreDeviceSupport() {
    @Test
    fun databaseReopenRecoversOnlyVerifiedCheckpointAndChangedInputStartsNewJob() = runBlocking {
        val databaseName = "agent-007-recovery-${UUID.randomUUID()}.db"
        val checkpoint = File(context.cacheDir, "agent-007-${UUID.randomUUID()}.checkpoint")
        checkpoint.writeText("accepted prepared artifact")
        val acceptedCursor = JobStageCursor(
            acceptedStage = ReconstructionStage.PREPARED,
            checkpointIdentity = "prepared-checkpoint-v1",
            checkpointSha256 = sha256(checkpoint),
        )
        context.deleteDatabase(databaseName)
        var originalJobId: JobId? = null
        var expiredLeaseToken: String? = null
        try {
            val firstDb = Room.databaseBuilder(
                context,
                IsolatedJobStoreDatabase::class.java,
                databaseName,
            ).build()
            try {
                val firstStore = store(firstDb, "restart-a")
                val queued = firstStore.enqueue(input("restart-project"), 1_000)
                originalJobId = queued.jobId
                val acquired = requireNotNull(firstStore.acquireNext("worker-before-kill", 1_001, 10))
                val accepted = firstStore.acceptStage(acquired.lease, acceptedCursor, 1_002)
                expiredLeaseToken = accepted.lease.token
                assertEquals(ReconstructionJobState.RUNNING, accepted.snapshot.state)
            } finally {
                firstDb.close()
            }

            val secondDb = Room.databaseBuilder(
                context,
                IsolatedJobStoreDatabase::class.java,
                databaseName,
            ).build()
            try {
                val secondStore = store(secondDb, "restart-b")
                val jobId = requireNotNull(originalJobId)
                val beforeRecovery = requireNotNull(secondStore.snapshot(jobId))
                assertEquals(ReconstructionJobState.RUNNING, beforeRecovery.state)
                assertEquals(expiredLeaseToken, beforeRecovery.lease?.token)

                val summary = secondStore.recoverExpired(
                    nowEpochMillis = 1_012,
                    validator = { snapshot ->
                        if (
                            snapshot.cursor == acceptedCursor &&
                            checkpoint.isFile &&
                            sha256(checkpoint) == acceptedCursor.checkpointSha256
                        ) {
                            CheckpointRecoveryDecision.ResumeFrom(acceptedCursor)
                        } else {
                            CheckpointRecoveryDecision.RestartFromBeginning("CHECKPOINT_MISSING")
                        }
                    },
                )
                assertEquals(1, summary.considered)
                assertEquals(1, summary.recovered)
                assertEquals(0, summary.conflicts)
                assertEquals(0, summary.validationFailures)

                val paused = requireNotNull(secondStore.snapshot(jobId))
                assertEquals(ReconstructionJobState.PAUSED, paused.state)
                assertEquals(acceptedCursor, paused.cursor)
                assertNull(paused.lease)

                secondStore.resume(paused.jobId, 1_013)
                val resumed = requireNotNull(secondStore.acquireNext("worker-after-kill", 1_014, 100))
                assertEquals(2L, resumed.lease.attempt)
                assertEquals(acceptedCursor, resumed.snapshot.cursor)

                val changed = secondStore.enqueue(
                    input("restart-project").copy(
                        projectRevision = 2,
                        requestId = "request-revision-2",
                        inputSha256 = "9".repeat(64),
                    ),
                    nowEpochMillis = 1_015,
                )
                assertNotEquals(paused.jobId, changed.jobId)
                assertEquals(ReconstructionJobState.QUEUED, changed.state)
                assertEquals(
                    ReconstructionJobState.STALE,
                    requireNotNull(secondStore.snapshot(paused.jobId)).state,
                )
                expectLeaseRejected {
                    secondStore.complete(
                        resumed.lease,
                        resultArtifactIdentity = "superseded-result",
                        resultArtifactSha256 = hash("superseded-result"),
                        nowEpochMillis = 1_016,
                    )
                }
            } finally {
                secondDb.close()
            }

            val thirdDb = Room.databaseBuilder(
                context,
                IsolatedJobStoreDatabase::class.java,
                databaseName,
            ).build()
            try {
                val persisted = requireNotNull(
                    store(thirdDb, "restart-c").snapshot(requireNotNull(originalJobId)),
                )
                assertEquals(ReconstructionJobState.STALE, persisted.state)
                assertEquals("INPUT_IDENTITY_CHANGED", persisted.outcomeCode)
                assertEquals(2L, persisted.attempt)
            } finally {
                thirdDb.close()
            }
        } finally {
            checkpoint.delete()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedValidationInvalidCursorAndCancellationLeaveExpiredRowUntouched() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            IsolatedJobStoreDatabase::class.java,
        ).build()
        try {
            val store = store(database, "recovery-failure")
            val queued = store.enqueue(input("recovery-failure-project"), 2_000)
            val acquired = requireNotNull(
                store.acquireNext("recovery-failure-worker", 2_001, 10),
            )
            val acceptedCursor = JobStageCursor(
                acceptedStage = ReconstructionStage.PREPARED,
                checkpointIdentity = "recovery-failure-checkpoint",
                checkpointSha256 = hash("recovery-failure-checkpoint"),
            )
            val accepted = store.acceptStage(acquired.lease, acceptedCursor, 2_002)
            val before = accepted.snapshot

            val validatorFailure = store.recoverExpired(
                nowEpochMillis = 2_011,
                validator = { throw IllegalStateException("transient validator failure") },
            )
            assertEquals(1, validatorFailure.considered)
            assertEquals(0, validatorFailure.recovered)
            assertEquals(0, validatorFailure.conflicts)
            assertEquals(1, validatorFailure.validationFailures)
            assertEquals(before, requireNotNull(store.snapshot(queued.jobId)))

            try {
                store.recoverExpired(
                    nowEpochMillis = 2_011,
                    validator = { throw CancellationException("cancelled recovery") },
                )
                fail("Expected recovery cancellation to propagate")
            } catch (_: CancellationException) {
                // Cancellation is control flow, not a checkpoint rejection.
            }
            assertEquals(before, requireNotNull(store.snapshot(queued.jobId)))

            val invalidCursor = JobStageCursor(
                acceptedStage = ReconstructionStage.PREPARED,
                checkpointIdentity = "different-checkpoint",
                checkpointSha256 = hash("different-checkpoint"),
            )
            val rejectedCursor = store.recoverExpired(
                nowEpochMillis = 2_011,
                validator = { CheckpointRecoveryDecision.ResumeFrom(invalidCursor) },
            )
            assertEquals(1, rejectedCursor.considered)
            assertEquals(0, rejectedCursor.recovered)
            assertEquals(0, rejectedCursor.conflicts)
            assertEquals(1, rejectedCursor.validationFailures)
            assertEquals(before, requireNotNull(store.snapshot(queued.jobId)))

            val restarted = store.recoverExpired(
                nowEpochMillis = 2_011,
                validator = {
                    CheckpointRecoveryDecision.RestartFromBeginning("CHECKPOINT_MISSING")
                },
            )
            assertEquals(1, restarted.considered)
            assertEquals(1, restarted.recovered)
            assertEquals(0, restarted.conflicts)
            assertEquals(0, restarted.validationFailures)
            val paused = requireNotNull(store.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.PAUSED, paused.state)
            assertEquals(JobStageCursor.initial(), paused.cursor)
            assertNull(paused.lease)
        } finally {
            database.close()
        }
    }
}
