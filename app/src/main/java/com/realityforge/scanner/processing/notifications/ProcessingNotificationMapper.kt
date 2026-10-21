package com.realityforge.scanner.processing.notifications

/** Supplies localized copy without coupling the pure state mapper to Android resources. */
interface ProcessingNotificationText {
    fun queuedTitle(): String
    fun runningTitle(): String
    fun pausedTitle(): String
    fun failedTitle(): String
    fun completedTitle(): String

    fun queuedBody(): String
    fun runningUnknownBody(stage: String): String
    fun runningKnownBody(stage: String, completed: Int, total: Int): String
    fun pausedBody(stage: String): String
    fun failedBody(): String
    fun completedBody(): String

    fun stage(stage: ProcessingStage?): String
    fun action(action: JobNotificationActionType): String
    fun publicTitle(): String
    fun publicBody(): String
}

/**
 * Maps persisted execution truth to notification truth.
 *
 * Known progress remains scoped to a named stage. Unknown duration is rendered as indeterminate;
 * no aggregate completion percentage or ETA is inferred.
 */
class ProcessingNotificationMapper(
    private val text: ProcessingNotificationText,
) {
    fun map(job: PersistedProcessingJob): ProcessingNotificationPresentation {
        if (job.status == JobNotificationStatus.CANCELLED) {
            return ProcessingNotificationPresentation.Remove(
                notificationId = job.identity.notificationId(),
                identity = job.identity,
            )
        }

        val stageText = text.stage(job.stage)
        val title: String
        val body: String
        val channel: NotificationChannelKind
        val progressBar: NotificationProgressBar
        val ongoing: Boolean
        val autoCancel: Boolean
        val onlyAlertOnce: Boolean

        when (job.status) {
            JobNotificationStatus.QUEUED -> {
                title = text.queuedTitle()
                body = text.queuedBody()
                channel = NotificationChannelKind.PROCESSING
                progressBar = NotificationProgressBar.Indeterminate
                ongoing = true
                autoCancel = false
                onlyAlertOnce = true
            }
            JobNotificationStatus.RUNNING -> {
                title = text.runningTitle()
                channel = NotificationChannelKind.PROCESSING
                ongoing = true
                autoCancel = false
                onlyAlertOnce = true
                when (val progress = job.progress) {
                    StageProgress.Unknown -> {
                        body = text.runningUnknownBody(stageText)
                        progressBar = NotificationProgressBar.Indeterminate
                    }
                    is StageProgress.Known -> {
                        body = text.runningKnownBody(
                            stage = stageText,
                            completed = progress.completed,
                            total = progress.total,
                        )
                        progressBar = NotificationProgressBar.Determinate(
                            value = progress.completed,
                            max = progress.total,
                        )
                    }
                }
            }
            JobNotificationStatus.PAUSED -> {
                title = text.pausedTitle()
                body = text.pausedBody(stageText)
                channel = NotificationChannelKind.PROCESSING
                progressBar = when (val progress = job.progress) {
                    StageProgress.Unknown -> NotificationProgressBar.None
                    is StageProgress.Known -> NotificationProgressBar.Determinate(
                        value = progress.completed,
                        max = progress.total,
                    )
                }
                ongoing = false
                autoCancel = false
                onlyAlertOnce = true
            }
            JobNotificationStatus.FAILED -> {
                title = text.failedTitle()
                body = text.failedBody()
                channel = NotificationChannelKind.RESULT
                progressBar = NotificationProgressBar.None
                ongoing = false
                autoCancel = true
                onlyAlertOnce = true
            }
            JobNotificationStatus.COMPLETED -> {
                title = text.completedTitle()
                body = text.completedBody()
                channel = NotificationChannelKind.RESULT
                progressBar = NotificationProgressBar.None
                ongoing = false
                autoCancel = true
                onlyAlertOnce = true
            }
            JobNotificationStatus.CANCELLED -> error("handled above")
        }

        val actions = buildList {
            if (job.actions.canPause && JobNotificationActionPolicy.stateAllows(
                    job.status,
                    JobNotificationActionType.PAUSE,
                )
            ) {
                add(JobNotificationAction(JobNotificationActionType.PAUSE, job.identity))
            }
            if (job.actions.canResume && JobNotificationActionPolicy.stateAllows(
                    job.status,
                    JobNotificationActionType.RESUME,
                )
            ) {
                add(JobNotificationAction(JobNotificationActionType.RESUME, job.identity))
            }
            if (job.actions.canCancel && JobNotificationActionPolicy.stateAllows(
                    job.status,
                    JobNotificationActionType.CANCEL,
                )
            ) {
                add(JobNotificationAction(JobNotificationActionType.CANCEL, job.identity))
            }
            // Active notifications use the body tap for OPEN so the visible action row stays small.
            if (isEmpty() && job.actions.canOpen) {
                add(JobNotificationAction(JobNotificationActionType.OPEN, job.identity))
            }
        }
        val contentAction = JobNotificationAction(JobNotificationActionType.OPEN, job.identity)

        return ProcessingNotificationPresentation.Show(
            ProcessingNotificationSpec(
                identity = job.identity,
                status = job.status,
                channel = channel,
                title = title,
                text = body,
                publicTitle = text.publicTitle(),
                publicText = text.publicBody(),
                progressBar = progressBar,
                actions = actions,
                contentAction = contentAction,
                ongoing = ongoing,
                autoCancel = autoCancel,
                onlyAlertOnce = onlyAlertOnce,
                whenEpochMillis = if (job.status == JobNotificationStatus.COMPLETED ||
                    job.status == JobNotificationStatus.FAILED
                ) {
                    job.updatedAtEpochMillis
                } else {
                    job.startedAtEpochMillis ?: job.updatedAtEpochMillis
                },
            ),
        )
    }
}
