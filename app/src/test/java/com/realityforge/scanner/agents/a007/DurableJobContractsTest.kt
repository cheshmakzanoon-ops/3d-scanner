package com.realityforge.scanner.agents.a007

import com.realityforge.scanner.data.jobs.JobInputSnapshot
import com.realityforge.scanner.data.jobs.JobLease
import com.realityforge.scanner.data.jobs.JobLeaseRejectedException
import com.realityforge.scanner.data.jobs.JobProgress
import com.realityforge.scanner.data.jobs.JobProgressUnit
import com.realityforge.scanner.data.jobs.JobSnapshot
import com.realityforge.scanner.data.jobs.JobStageCursor
import com.realityforge.scanner.data.jobs.JobStateMachine
import com.realityforge.scanner.data.jobs.JobTransitionRejectedException
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import com.realityforge.scanner.data.jobs.ReconstructionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DurableJobContractsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)

    private fun input() = JobInputSnapshot(
        projectId = "project-7",
        projectRevision = 11,
        requestId = "request-7",
        inputSha256 = a,
        calibrationSha256 = b,
        settingsSha256 = c,
        engineSha256 = d,
        engineVersion = "0.19.0-m19",
    )

    @Test
    fun canonicalIdentityIsDeterministicAndEveryImmutableInputFencesReuse() {
        val source = input()
        val identity = source.identityKey()
        assertEquals(64, identity.length)
        assertEquals(identity, input().identityKey())
        val changed = listOf(
            source.copy(projectId = "project-8"),
            source.copy(projectRevision = 12),
            source.copy(requestId = "request-8"),
            source.copy(inputSha256 = "1".repeat(64)),
            source.copy(calibrationSha256 = "2".repeat(64)),
            source.copy(settingsSha256 = "3".repeat(64)),
            source.copy(engineSha256 = "4".repeat(64)),
            source.copy(engineVersion = "0.20.0"),
        )
        changed.forEach { assertNotEquals(identity, it.identityKey()) }
    }

    @Test
    fun malformedOrAmbiguousPersistedIdentityIsRejectedBeforeAllocation() {
        expect<IllegalArgumentException> { input().copy(projectId = "../escape") }
        expect<IllegalArgumentException> { input().copy(inputSha256 = "not-a-sha") }
        expect<IllegalArgumentException> { input().copy(engineVersion = "") }
        expect<IllegalArgumentException> {
            JobStageCursor(ReconstructionStage.PREPARED, null, null)
        }
        expect<IllegalArgumentException> {
            JobStageCursor(ReconstructionStage.CREATED, "unexpected", a)
        }
    }

    @Test
    fun acceptedCheckpointIsImmutableAndRecoveryMayOnlyKeepOrRollBackIt() {
        val prepared = JobStageCursor(ReconstructionStage.PREPARED, "prepared-v1", a)
        JobStateMachine.requireStageAdvance(JobStageCursor.initial(), prepared)
        JobStateMachine.requireStageAdvance(prepared, prepared)
        expect<JobTransitionRejectedException> {
            JobStateMachine.requireStageAdvance(
                prepared,
                JobStageCursor(ReconstructionStage.PREPARED, "prepared-v2", b),
            )
        }
        expect<JobTransitionRejectedException> {
            JobStateMachine.requireStageAdvance(
                prepared,
                JobStageCursor(ReconstructionStage.SPARSE, "sparse-v1", c),
            )
        }
        val features = JobStageCursor(ReconstructionStage.FEATURES, "features-v1", b)
        JobStateMachine.requireRecoveryCursor(features, prepared)
        JobStateMachine.requireRecoveryCursor(features, features)
        expect<JobTransitionRejectedException> {
            JobStateMachine.requireRecoveryCursor(
                features,
                JobStageCursor(ReconstructionStage.FEATURES, "features-other", c),
            )
        }
    }

    @Test
    fun leaseRequiresExactAttemptTokenOwnerVersionAndUnexpiredTime() {
        val snapshot = runningSnapshot()
        val lease = requireNotNull(snapshot.lease)
        JobStateMachine.requireValidLease(snapshot, lease, 150)
        expect<JobLeaseRejectedException> {
            JobStateMachine.requireValidLease(snapshot, lease.copy(attempt = 2), 150)
        }
        expect<JobLeaseRejectedException> {
            JobStateMachine.requireValidLease(snapshot, lease.copy(token = "token-other"), 150)
        }
        expect<JobLeaseRejectedException> {
            JobStateMachine.requireValidLease(snapshot, lease.copy(stateVersion = 8), 150)
        }
        expect<JobLeaseRejectedException> {
            JobStateMachine.requireValidLease(snapshot, lease, 200)
        }
        expect<JobLeaseRejectedException> {
            JobStateMachine.requireValidLease(snapshot, lease, 99)
        }
    }

    @Test
    fun progressIsCoarseMonotonicAndCoalescedInsteadOfPersistingPixelEvents() {
        assertFalse(JobProgressUnit.values().any { it.name == "PIXELS" })
        val previous = JobProgress(ReconstructionStage.FEATURES, 10, 100, JobProgressUnit.PHOTOS)
        assertFalse(
            JobStateMachine.shouldPersistProgress(
                previous,
                previousAtEpochMillis = 1_000,
                candidate = previous.copy(completedUnits = 11),
                nowEpochMillis = 1_500,
            ),
        )
        assertTrue(
            JobStateMachine.shouldPersistProgress(
                previous,
                previousAtEpochMillis = 1_000,
                candidate = previous.copy(completedUnits = 11),
                nowEpochMillis = 2_000,
            ),
        )
        assertTrue(
            JobStateMachine.shouldPersistProgress(
                previous,
                previousAtEpochMillis = 1_000,
                candidate = previous.copy(completedUnits = 100),
                nowEpochMillis = 1_001,
            ),
        )
        expect<JobTransitionRejectedException> {
            JobStateMachine.shouldPersistProgress(
                previous,
                previousAtEpochMillis = 1_000,
                candidate = previous.copy(completedUnits = 9, totalUnits = 50),
                nowEpochMillis = 2_000,
            )
        }
    }

    private fun runningSnapshot(): JobSnapshot {
        val jobId = JobId("job-7")
        val lease = JobLease(
            jobId = jobId,
            attempt = 1,
            token = "token-7",
            workerId = "worker-7",
            expiresAtEpochMillis = 200,
            stateVersion = 7,
        )
        return JobSnapshot(
            jobId = jobId,
            identityKey = input().identityKey(),
            input = input(),
            state = ReconstructionJobState.RUNNING,
            cursor = JobStageCursor.initial(),
            attempt = 1,
            lease = lease,
            resultArtifactIdentity = null,
            resultArtifactSha256 = null,
            outcomeCode = null,
            progress = null,
            progressUpdatedAtEpochMillis = null,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
            stateVersion = 7,
        )
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
