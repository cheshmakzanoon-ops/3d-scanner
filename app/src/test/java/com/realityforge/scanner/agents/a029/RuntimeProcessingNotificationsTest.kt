package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.processing.notifications.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeProcessingNotificationsTest {
    @Test fun persistedStageAndActualUnitsSurviveMapping() {
        val job = snapshot(ReconstructionJobState.PAUSED, JobProgress(ReconstructionStage.DENSE, 7, 24, JobProgressUnit.TILES))
        val shown = job.toNotification()
        assertEquals(ProcessingStage.DENSE_DEPTH, shown.stage)
        assertEquals(StageProgress.Known(7, 24), shown.progress)
        assertEquals(job.createdAtEpochMillis, shown.startedAtEpochMillis)
        assertEquals(job.updatedAtEpochMillis, shown.updatedAtEpochMillis)
        assertTrue(shown.actions.canResume)
        assertFalse(shown.actions.canPause)
    }

    @Test fun unknownAndOversizedCountsNeverInventOrTruncateProgress() {
        val unknown = snapshot(ReconstructionJobState.PAUSED,
            JobProgress(ReconstructionStage.DENSE, 12, null, JobProgressUnit.TILES)).toNotification()
        val huge = snapshot(ReconstructionJobState.PAUSED,
            JobProgress(ReconstructionStage.DENSE, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1, JobProgressUnit.BYTES)).toNotification()
        assertEquals(StageProgress.Unknown, unknown.progress)
        assertEquals(StageProgress.Unknown, huge.progress)
        assertEquals(ProcessingStage.CAMERA_REGISTRATION,
            snapshot(ReconstructionJobState.PAUSED).toNotification().stage)
    }

    @Test fun restoredStateOmitsUnownedRunningAndDismissedResults() {
        val running = snapshot(ReconstructionJobState.RUNNING, id = "running")
        val paused = snapshot(ReconstructionJobState.PAUSED, id = "paused")
        val result = snapshot(ReconstructionJobState.COMPLETED, id = "result")
        val dismissed = snapshot(ReconstructionJobState.COMPLETED, id = "dismissed")
        val cancelled = snapshot(ReconstructionJobState.CANCELLED, id = "cancelled")
        val restored = restoredNotificationJobs(listOf(running, paused, result, dismissed, cancelled),
            setOf(running.toNotification().identity.notificationTag(), result.toNotification().identity.notificationTag()))
        assertEquals(setOf("paused", "result", "cancelled"), restored.map { it.identity.jobId }.toSet())
    }

    @Test fun oldAttemptNotificationCannotControlResumedWorker() {
        val before = snapshot(ReconstructionJobState.PAUSED)
        val after = snapshot(ReconstructionJobState.RUNNING, attempt = 2)
        val stale = JobNotificationAction(JobNotificationActionType.CANCEL, before.toNotification().identity)
        assertNotEquals(before.toNotification().identity.fingerprint, after.toNotification().identity.fingerprint)
        assertFalse(JobNotificationActionAuthorizer().authorize(stale, after.toNotification()) is JobActionAuthorization.Allowed)
    }

    private fun snapshot(state: ReconstructionJobState, progress: JobProgress? = null, id: String = "job", attempt: Long = 1): JobSnapshot {
        val input = JobInputSnapshot("project", 9, "request", "a".repeat(64), "b".repeat(64),
            "c".repeat(64), "d".repeat(64), "test-engine")
        val jobId = JobId(id)
        return JobSnapshot(jobId, input.identityKey(), input, state,
            JobStageCursor(ReconstructionStage.SPARSE, "sparse-checkpoint", "e".repeat(64)), attempt,
            if (state == ReconstructionJobState.RUNNING) JobLease(jobId, attempt, "lease", "worker", 1000, 3) else null,
            if (state == ReconstructionJobState.COMPLETED) "verified-result" else null,
            if (state == ReconstructionJobState.COMPLETED) "f".repeat(64) else null,
            null, progress, progress?.let { 20L }, 10, 30, 3)
    }
}
