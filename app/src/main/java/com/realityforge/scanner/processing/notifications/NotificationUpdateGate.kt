package com.realityforge.scanner.processing.notifications

enum class NotificationUpdateDecision {
    PUBLISH,
    UNCHANGED,
    RATE_LIMITED,
}

/**
 * In-memory rate gate for frequent progress ticks. State/stage/action changes bypass the interval;
 * process recreation starts with an empty gate so persisted state is restored immediately.
 */
class NotificationUpdateGate(
    private val minimumProgressIntervalMillis: Long = 1_000L,
) {
    init {
        require(minimumProgressIntervalMillis >= 0L) {
            "minimumProgressIntervalMillis must be non-negative"
        }
    }

    private data class ImmediateSignature(
        val status: JobNotificationStatus,
        val stage: ProcessingStage?,
        val actions: JobActionAvailability,
    )

    private data class PublishedState(
        val immediate: ImmediateSignature,
        val progress: StageProgress,
        val elapsedRealtimeMillis: Long,
    )

    private val published = mutableMapOf<String, PublishedState>()

    @Synchronized
    fun evaluate(
        job: PersistedProcessingJob,
        elapsedRealtimeMillis: Long,
        force: Boolean = false,
    ): NotificationUpdateDecision {
        require(elapsedRealtimeMillis >= 0L) { "elapsedRealtimeMillis must be non-negative" }
        val key = job.identity.fingerprint
        val immediate = ImmediateSignature(job.status, job.stage, job.actions)
        val previous = published[key]

        if (force || previous == null || previous.immediate != immediate) {
            record(key, immediate, job.progress, elapsedRealtimeMillis)
            return NotificationUpdateDecision.PUBLISH
        }
        if (previous.progress == job.progress) {
            return NotificationUpdateDecision.UNCHANGED
        }
        if (job.status.isTerminal) {
            record(key, immediate, job.progress, elapsedRealtimeMillis)
            return NotificationUpdateDecision.PUBLISH
        }

        val elapsed = elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        if (elapsed < 0L || elapsed >= minimumProgressIntervalMillis) {
            record(key, immediate, job.progress, elapsedRealtimeMillis)
            return NotificationUpdateDecision.PUBLISH
        }
        return NotificationUpdateDecision.RATE_LIMITED
    }

    @Synchronized
    fun clear(identity: ProcessingJobIdentity) {
        published.remove(identity.fingerprint)
    }

    @Synchronized
    fun clearAll() {
        published.clear()
    }

    private fun record(
        key: String,
        immediate: ImmediateSignature,
        progress: StageProgress,
        elapsedRealtimeMillis: Long,
    ) {
        published[key] = PublishedState(immediate, progress, elapsedRealtimeMillis)
    }
}
