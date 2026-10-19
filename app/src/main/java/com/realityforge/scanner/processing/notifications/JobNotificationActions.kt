package com.realityforge.scanner.processing.notifications

/**
 * Authorizes a decoded notification command against the authoritative persisted job loaded at
 * delivery time. Never authorize against notification extras alone.
 */
object JobNotificationActionPolicy {
    fun stateAllows(
        status: JobNotificationStatus,
        action: JobNotificationActionType,
    ): Boolean = when (action) {
        JobNotificationActionType.PAUSE -> status == JobNotificationStatus.RUNNING
        JobNotificationActionType.RESUME ->
            status == JobNotificationStatus.PAUSED || status == JobNotificationStatus.FAILED
        JobNotificationActionType.CANCEL ->
            status == JobNotificationStatus.QUEUED ||
                status == JobNotificationStatus.RUNNING ||
                status == JobNotificationStatus.PAUSED
        JobNotificationActionType.OPEN -> status != JobNotificationStatus.CANCELLED
    }
}

class JobNotificationActionAuthorizer {
    fun authorize(
        action: JobNotificationAction,
        currentJob: PersistedProcessingJob?,
    ): JobActionAuthorization {
        if (currentJob == null) {
            return JobActionAuthorization.Rejected(JobActionRejection.NO_CURRENT_JOB)
        }
        if (action.identity != currentJob.identity) {
            return JobActionAuthorization.Rejected(JobActionRejection.IDENTITY_MISMATCH)
        }
        if (!JobNotificationActionPolicy.stateAllows(currentJob.status, action.type)) {
            return JobActionAuthorization.Rejected(JobActionRejection.STATE_DISALLOWS_ACTION)
        }
        if (!currentJob.actions.allows(action.type)) {
            return JobActionAuthorization.Rejected(JobActionRejection.ACTION_NOT_AVAILABLE)
        }
        return JobActionAuthorization.Allowed(currentJob)
    }
}

sealed interface JobActionAuthorization {
    data class Allowed(val currentJob: PersistedProcessingJob) : JobActionAuthorization
    data class Rejected(val reason: JobActionRejection) : JobActionAuthorization
}

enum class JobActionRejection {
    NO_CURRENT_JOB,
    IDENTITY_MISMATCH,
    STATE_DISALLOWS_ACTION,
    ACTION_NOT_AVAILABLE,
}
