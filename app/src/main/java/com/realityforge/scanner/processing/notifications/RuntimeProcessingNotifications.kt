package com.realityforge.scanner.processing.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.R
import com.realityforge.scanner.data.jobs.JobSnapshot
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import com.realityforge.scanner.data.jobs.ReconstructionStage
import com.realityforge.scanner.work.execution.ReconstructionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persisted coarse stages and their actual unit counts are the only progress source for restored notifications. */
fun JobSnapshot.toNotification(): PersistedProcessingJob {
    val persistedStage = progress?.stage ?: cursor.acceptedStage
    val stage = when (persistedStage) {
        ReconstructionStage.CREATED, ReconstructionStage.PREPARED -> ProcessingStage.PREPARING
        ReconstructionStage.FEATURES -> ProcessingStage.FEATURES
        ReconstructionStage.MATCHES -> ProcessingStage.MATCHING
        ReconstructionStage.SPARSE -> ProcessingStage.CAMERA_REGISTRATION
        ReconstructionStage.DENSE -> ProcessingStage.DENSE_DEPTH
        ReconstructionStage.SURFACE -> ProcessingStage.SURFACE
        ReconstructionStage.TEXTURE -> ProcessingStage.TEXTURE
        ReconstructionStage.PUBLISHING -> ProcessingStage.FINALIZING
    }
    val known = progress?.let { value ->
        val total = value.totalUnits
        if (total != null && total in 1L..Int.MAX_VALUE.toLong() && value.completedUnits <= Int.MAX_VALUE)
            StageProgress.Known(value.completedUnits.toInt(), total.toInt()) else null
    }
    return PersistedProcessingJob(
        identity = ProcessingJobIdentity(input.projectId, jobId.value, "${input.requestId}:$attempt", input.projectRevision),
        status = when (state) {
            ReconstructionJobState.QUEUED -> JobNotificationStatus.QUEUED
            ReconstructionJobState.RUNNING -> JobNotificationStatus.RUNNING
            ReconstructionJobState.PAUSED -> JobNotificationStatus.PAUSED
            ReconstructionJobState.COMPLETED -> JobNotificationStatus.COMPLETED
            ReconstructionJobState.CANCELLED -> JobNotificationStatus.CANCELLED
            else -> JobNotificationStatus.FAILED
        }, stage = stage, progress = known ?: StageProgress.Unknown,
        updatedAtEpochMillis = updatedAtEpochMillis, startedAtEpochMillis = createdAtEpochMillis,
    )
}

fun processingNotificationController(context: Context) = AndroidProcessingNotificationController(context,
    ComponentName(context, ReconstructionService::class.java), ComponentName(context, MainActivity::class.java), R.drawable.ic_launcher)

/**
 * Reconcile after durable job recovery. A vanished process is not proof that an unexpired RUNNING lease
 * still has live work, so only the actual service may show RUNNING. Dismissed terminal alerts stay dismissed.
 */
suspend fun restoreProcessingNotifications(context: Context, snapshots: List<JobSnapshot>): NotificationRestoreResult =
    withContext(Dispatchers.Main.immediate) {
        val controller = processingNotificationController(context)
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            .mapNotNull { it.tag }.toSet()
        val mapped = restoredNotificationJobs(snapshots, active)
        controller.restore(mapped, AndroidNotificationPermissionFactsReader.read(context,
            hasRequestedRuntimePermission = false, shouldShowRationale = false))
    }

internal fun restoredNotificationJobs(snapshots: List<JobSnapshot>, activeTags: Set<String>): List<PersistedProcessingJob> =
    snapshots.map(JobSnapshot::toNotification).filter { job ->
        job.status == JobNotificationStatus.PAUSED || job.status == JobNotificationStatus.CANCELLED ||
            (job.status.isTerminal && job.identity.notificationTag() in activeTags)
    }
