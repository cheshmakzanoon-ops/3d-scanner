package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.processing.notifications.*
import org.junit.Assert.*
import org.junit.Test

class JobNotificationActionAuthorizerTest {
    private val authorizer = JobNotificationActionAuthorizer()
    private val original = ProcessingJobIdentity("project-a", "job-1", "operation-1", 4)

    @Test
    fun replacementJobRejectsOldNotificationAfterProcessRecreation() {
        val stale = JobNotificationAction(JobNotificationActionType.CANCEL, original)
        val replacement = running(
            ProcessingJobIdentity("project-a", "job-2", "operation-2", 5),
        )

        val recreatedAuthorizer = JobNotificationActionAuthorizer()
        assertEquals(
            JobActionAuthorization.Rejected(JobActionRejection.IDENTITY_MISMATCH),
            recreatedAuthorizer.authorize(stale, replacement),
        )
    }

    @Test
    fun anotherProjectCannotBeControlledByOldAction() {
        val action = JobNotificationAction(JobNotificationActionType.PAUSE, original)
        val otherProject = running(
            ProcessingJobIdentity("project-b", "job-1", "operation-1", 4),
        )
        assertEquals(
            JobActionAuthorization.Rejected(JobActionRejection.IDENTITY_MISMATCH),
            authorizer.authorize(action, otherProject),
        )
    }

    @Test
    fun cancelledJobRejectsEveryLateAction() {
        val cancelled = PersistedProcessingJob(
            identity = original,
            status = JobNotificationStatus.CANCELLED,
            actions = JobActionAvailability(false, false, false, canOpen = true),
            updatedAtEpochMillis = 10,
        )
        JobNotificationActionType.entries.forEach { type ->
            val result = authorizer.authorize(JobNotificationAction(type, original), cancelled)
            assertEquals(
                JobActionAuthorization.Rejected(JobActionRejection.STATE_DISALLOWS_ACTION),
                result,
            )
        }
    }

    @Test
    fun exactPersistedIdentitySurvivesAuthorizerRecreation() {
        val current = running(original)
        val action = JobNotificationAction(JobNotificationActionType.PAUSE, original)
        val result = JobNotificationActionAuthorizer().authorize(action, current)
        assertEquals(JobActionAuthorization.Allowed(current), result)
    }

    @Test
    fun capabilityFlagsCannotOverrideStateMachine() {
        val malformedCapabilities = PersistedProcessingJob(
            identity = original,
            status = JobNotificationStatus.COMPLETED,
            actions = JobActionAvailability(
                canPause = true,
                canResume = true,
                canCancel = true,
                canOpen = true,
            ),
            updatedAtEpochMillis = 20,
        )
        assertEquals(
            JobActionAuthorization.Rejected(JobActionRejection.STATE_DISALLOWS_ACTION),
            authorizer.authorize(
                JobNotificationAction(JobNotificationActionType.CANCEL, original),
                malformedCapabilities,
            ),
        )
        assertEquals(
            JobActionAuthorization.Allowed(malformedCapabilities),
            authorizer.authorize(
                JobNotificationAction(JobNotificationActionType.OPEN, original),
                malformedCapabilities,
            ),
        )
    }

    @Test
    fun stableIdsIncludeOperationAndAction() {
        val changedOperation = original.copy(operationId = "operation-2")
        assertNotEquals(original.fingerprint, changedOperation.fingerprint)
        assertNotEquals(original.notificationId(), changedOperation.notificationId())
        assertNotEquals(
            original.requestCode(JobNotificationActionType.PAUSE),
            original.requestCode(JobNotificationActionType.CANCEL),
        )
    }

    private fun running(identity: ProcessingJobIdentity) = PersistedProcessingJob(
        identity = identity,
        status = JobNotificationStatus.RUNNING,
        stage = ProcessingStage.MATCHING,
        updatedAtEpochMillis = 10,
    )
}
