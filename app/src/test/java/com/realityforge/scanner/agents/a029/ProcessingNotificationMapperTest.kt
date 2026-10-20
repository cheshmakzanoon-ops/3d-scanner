package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.processing.notifications.*
import org.junit.Assert.*
import org.junit.Test

class ProcessingNotificationMapperTest {
    private val mapper = ProcessingNotificationMapper(TestText)
    private val identity = ProcessingJobIdentity("project-a", "job-1", "operation-9", 7)

    @Test
    fun unknownDurationIsIndeterminateAndNeverInventsPercent() {
        val presentation = mapper.map(
            job(
                status = JobNotificationStatus.RUNNING,
                stage = ProcessingStage.DENSE_DEPTH,
                progress = StageProgress.Unknown,
            ),
        ) as ProcessingNotificationPresentation.Show

        assertEquals(NotificationProgressBar.Indeterminate, presentation.spec.progressBar)
        assertEquals("Estimating observed depth / unknown duration", presentation.spec.text)
        assertFalse(presentation.spec.text.contains('%'))
        assertTrue(presentation.spec.ongoing)
        assertEquals(NotificationChannelKind.PROCESSING, presentation.spec.channel)
    }

    @Test
    fun knownProgressRemainsScopedToCurrentStage() {
        val presentation = mapper.map(
            job(
                status = JobNotificationStatus.RUNNING,
                stage = ProcessingStage.TEXTURE,
                progress = StageProgress.Known(3, 8),
            ),
        ) as ProcessingNotificationPresentation.Show

        assertEquals(NotificationProgressBar.Determinate(3, 8), presentation.spec.progressBar)
        assertEquals("Baking photographic texture / 3 of 8", presentation.spec.text)
        assertFalse(presentation.spec.text.contains("complete", ignoreCase = true))
    }

    @Test
    fun notificationCopyCannotContainPrivateProjectIdentityOrPath() {
        val privateIdentity = ProcessingJobIdentity(
            projectId = "private-project-name",
            jobId = "job-/storage/emulated/0/DCIM/private.jpg",
            operationId = "operation-secret",
            sourceRevision = 11,
        )
        val presentation = mapper.map(
            PersistedProcessingJob(
                identity = privateIdentity,
                status = JobNotificationStatus.FAILED,
                stage = ProcessingStage.SURFACE,
                updatedAtEpochMillis = 100,
            ),
        ) as ProcessingNotificationPresentation.Show

        val visibleCopy = listOf(
            presentation.spec.title,
            presentation.spec.text,
            presentation.spec.publicTitle,
            presentation.spec.publicText,
        ).joinToString(" ")
        assertFalse(visibleCopy.contains("private-project-name"))
        assertFalse(visibleCopy.contains("/storage/"))
        assertEquals(NotificationChannelKind.RESULT, presentation.spec.channel)
        assertEquals(listOf(JobNotificationActionType.OPEN), presentation.spec.actions.map { it.type })
    }

    @Test
    fun activeControlsAreTypedAndOpenIsBoundAsContentAction() {
        val presentation = mapper.map(
            job(
                status = JobNotificationStatus.RUNNING,
                stage = ProcessingStage.MATCHING,
            ),
        ) as ProcessingNotificationPresentation.Show

        assertEquals(
            listOf(JobNotificationActionType.PAUSE, JobNotificationActionType.CANCEL),
            presentation.spec.actions.map { it.type },
        )
        assertEquals(JobNotificationActionType.OPEN, presentation.spec.contentAction.type)
        assertTrue(presentation.spec.actions.all { it.identity == identity })
        assertEquals(identity, presentation.spec.contentAction.identity)
    }

    @Test
    fun malformedCapabilitiesDoNotRenderStateInvalidControls() {
        val presentation = mapper.map(
            PersistedProcessingJob(
                identity = identity,
                status = JobNotificationStatus.COMPLETED,
                actions = JobActionAvailability(
                    canPause = true,
                    canResume = true,
                    canCancel = true,
                    canOpen = true,
                ),
                updatedAtEpochMillis = 200,
            ),
        ) as ProcessingNotificationPresentation.Show
        assertEquals(
            listOf(JobNotificationActionType.OPEN),
            presentation.spec.actions.map { it.type },
        )
    }

    @Test
    fun cancelledJobRemovesItsExactNotification() {
        val presentation = mapper.map(job(status = JobNotificationStatus.CANCELLED))
        assertEquals(
            ProcessingNotificationPresentation.Remove(identity.notificationId(), identity),
            presentation,
        )
    }

    @Test
    fun missingPersistedStageIsShownAsUnavailableRatherThanGuessed() {
        val presentation = mapper.map(
            job(status = JobNotificationStatus.RUNNING, stage = null),
        ) as ProcessingNotificationPresentation.Show
        assertEquals("Current stage unavailable / unknown duration", presentation.spec.text)
    }

    private fun job(
        status: JobNotificationStatus,
        stage: ProcessingStage? = null,
        progress: StageProgress = StageProgress.Unknown,
    ) = PersistedProcessingJob(
        identity = identity,
        status = status,
        stage = stage,
        progress = progress,
        updatedAtEpochMillis = 200,
        startedAtEpochMillis = 100,
    )

    private object TestText : ProcessingNotificationText {
        override fun queuedTitle() = "Queued"
        override fun runningTitle() = "Running"
        override fun pausedTitle() = "Paused"
        override fun failedTitle() = "Failed"
        override fun completedTitle() = "Completed"
        override fun queuedBody() = "Waiting"
        override fun runningUnknownBody(stage: String) = "$stage / unknown duration"
        override fun runningKnownBody(stage: String, completed: Int, total: Int) =
            "$stage / $completed of $total"
        override fun pausedBody(stage: String) = "Paused at $stage"
        override fun failedBody() = "Open app for details"
        override fun completedBody() = "Open saved result"
        override fun stage(stage: ProcessingStage?) = when (stage) {
            null -> "Current stage unavailable"
            ProcessingStage.PREPARING -> "Preparing inputs"
            ProcessingStage.FEATURES -> "Detecting image features"
            ProcessingStage.MATCHING -> "Matching photographs"
            ProcessingStage.CAMERA_REGISTRATION -> "Registering cameras"
            ProcessingStage.DENSE_DEPTH -> "Estimating observed depth"
            ProcessingStage.SURFACE -> "Building observed surface"
            ProcessingStage.TEXTURE -> "Baking photographic texture"
            ProcessingStage.FINALIZING -> "Validating saved output"
        }
        override fun action(action: JobNotificationActionType) = action.wireName
        override fun publicTitle() = "Generic processing"
        override fun publicBody() = "Unlock to view status"
    }
}
