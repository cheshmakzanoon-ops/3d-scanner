package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.processing.notifications.*
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationUpdateGateTest {
    private val identity = ProcessingJobIdentity("p", "j", "o", 1)

    @Test
    fun identicalUpdateIsSuppressedAndFastProgressIsRateLimited() {
        val gate = NotificationUpdateGate(minimumProgressIntervalMillis = 1_000)
        val first = running(StageProgress.Known(1, 10))
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(first, 1_000))
        assertEquals(NotificationUpdateDecision.UNCHANGED, gate.evaluate(first, 1_100))
        assertEquals(
            NotificationUpdateDecision.RATE_LIMITED,
            gate.evaluate(running(StageProgress.Known(2, 10)), 1_500),
        )
        assertEquals(
            NotificationUpdateDecision.PUBLISH,
            gate.evaluate(running(StageProgress.Known(2, 10)), 2_000),
        )
    }

    @Test
    fun stageStatusAndActionsPublishImmediately() {
        val gate = NotificationUpdateGate(minimumProgressIntervalMillis = 10_000)
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(running(), 100))
        assertEquals(
            NotificationUpdateDecision.PUBLISH,
            gate.evaluate(running(stage = ProcessingStage.SURFACE), 101),
        )
        val paused = PersistedProcessingJob(
            identity = identity,
            status = JobNotificationStatus.PAUSED,
            stage = ProcessingStage.SURFACE,
            updatedAtEpochMillis = 3,
        )
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(paused, 102))
    }

    @Test
    fun processRecreationRestoresPersistedStateImmediately() {
        val job = running(StageProgress.Known(4, 10))
        val beforeDeath = NotificationUpdateGate(60_000)
        assertEquals(NotificationUpdateDecision.PUBLISH, beforeDeath.evaluate(job, 100))
        val afterRecreation = NotificationUpdateGate(60_000)
        assertEquals(NotificationUpdateDecision.PUBLISH, afterRecreation.evaluate(job, 101))
    }

    @Test
    fun clearAllowsSameIdentityToBeRepublished() {
        val gate = NotificationUpdateGate(60_000)
        val job = running()
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(job, 100))
        gate.clear(identity)
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(job, 101))
    }

    @Test
    fun elapsedRealtimeResetDoesNotSuppressProgressForever() {
        val gate = NotificationUpdateGate(60_000)
        assertEquals(NotificationUpdateDecision.PUBLISH, gate.evaluate(running(), 1_000))
        assertEquals(
            NotificationUpdateDecision.PUBLISH,
            gate.evaluate(running(StageProgress.Known(1, 2)), 10),
        )
    }

    private fun running(
        progress: StageProgress = StageProgress.Unknown,
        stage: ProcessingStage = ProcessingStage.DENSE_DEPTH,
    ) = PersistedProcessingJob(
        identity = identity,
        status = JobNotificationStatus.RUNNING,
        stage = stage,
        progress = progress,
        updatedAtEpochMillis = 2,
    )
}
