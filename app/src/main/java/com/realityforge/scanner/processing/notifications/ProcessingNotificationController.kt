package com.realityforge.scanner.processing.notifications

import android.app.Notification

data class BuiltProcessingNotification(
    val notificationTag: String,
    val notificationId: Int,
    val notification: Notification,
    val requiresForegroundService: Boolean,
    val identity: ProcessingJobIdentity,
)

sealed interface NotificationPublishResult {
    data class Posted(
        val built: BuiltProcessingNotification,
        val permission: NotificationPermissionDecision,
    ) : NotificationPublishResult

    /**
     * The drawer post was intentionally skipped. A running FGS owner must still pass
     * [BuiltProcessingNotification.notification] to startForeground.
     */
    data class PermissionBlocked(
        val built: BuiltProcessingNotification,
        val permission: NotificationPermissionDecision,
    ) : NotificationPublishResult

    data class PlatformRejected(
        val built: BuiltProcessingNotification,
        val permission: NotificationPermissionDecision,
    ) : NotificationPublishResult

    data class Removed(val notificationTag: String, val notificationId: Int) : NotificationPublishResult
    data class Unchanged(val notificationTag: String, val notificationId: Int) : NotificationPublishResult
    data class RateLimited(val notificationTag: String, val notificationId: Int) : NotificationPublishResult
}

data class NotificationRestoreResult(
    val publishResults: List<NotificationPublishResult>,
    val removedOrphanCount: Int,
)

interface ProcessingNotificationController {
    fun ensureChannels()
    fun build(job: PersistedProcessingJob): BuiltProcessingNotification?

    fun publish(
        job: PersistedProcessingJob,
        permissionFacts: NotificationPermissionFacts,
        force: Boolean = false,
    ): NotificationPublishResult

    /** Reconciles app-owned notifications, then force-renders the supplied persisted snapshots. */
    fun restore(
        jobs: Collection<PersistedProcessingJob>,
        permissionFacts: NotificationPermissionFacts,
    ): NotificationRestoreResult

    fun remove(identity: ProcessingJobIdentity)
}
