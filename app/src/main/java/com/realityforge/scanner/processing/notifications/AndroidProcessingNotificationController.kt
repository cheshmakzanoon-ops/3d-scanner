package com.realityforge.scanner.processing.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.realityforge.scanner.R

/**
 * Android notification adapter only. Agent 008 remains responsible for Service lifecycle and
 * startForeground; Agent 007 remains the authority for persisted job state.
 */
class AndroidProcessingNotificationController(
    context: Context,
    commandComponent: ComponentName,
    openComponent: ComponentName,
    private val smallIconRes: Int,
    minimumProgressIntervalMillis: Long = 1_000L,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : ProcessingNotificationController {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val platformNotificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val text = ResourceProcessingNotificationText(appContext)
    private val mapper = ProcessingNotificationMapper(text)
    private val intents = JobNotificationPendingIntentFactory(
        appContext,
        commandComponent,
        openComponent,
    )
    private val builder = ProcessingNotificationBuilder(appContext, text, intents, smallIconRes)
    private val updateGate = NotificationUpdateGate(minimumProgressIntervalMillis)
    private val lastPermissionState = mutableMapOf<String, NotificationPermissionState>()

    init {
        require(smallIconRes != 0) { "smallIconRes must be a valid drawable resource" }
    }

    override fun ensureChannels() {
        val processing = NotificationChannel(
            NotificationChannelKind.PROCESSING.channelId,
            appContext.getString(R.string.a029_channel_processing_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.a029_channel_processing_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val results = NotificationChannel(
            NotificationChannelKind.RESULT.channelId,
            appContext.getString(R.string.a029_channel_results_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.a029_channel_results_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        platformNotificationManager.createNotificationChannels(listOf(processing, results))
    }

    override fun build(job: PersistedProcessingJob): BuiltProcessingNotification? =
        when (val presentation = mapper.map(job)) {
            is ProcessingNotificationPresentation.Remove -> null
            is ProcessingNotificationPresentation.Show -> builder.build(presentation.spec)
        }

    @SuppressLint("MissingPermission") // Runtime policy checked; manifest belongs to Agent 008.
    override fun publish(
        job: PersistedProcessingJob,
        permissionFacts: NotificationPermissionFacts,
        force: Boolean,
    ): NotificationPublishResult {
        val identity = job.identity
        val tag = identity.notificationTag()
        val id = identity.notificationId()
        val presentation = mapper.map(job)
        val effectivePermissionFacts = when (presentation) {
            is ProcessingNotificationPresentation.Remove -> permissionFacts
            is ProcessingNotificationPresentation.Show -> {
                ensureChannels()
                permissionFacts.copy(
                    channelEnabled = platformNotificationManager
                        .getNotificationChannel(presentation.spec.channel.channelId)
                        ?.importance != NotificationManager.IMPORTANCE_NONE,
                )
            }
        }
        val permission = NotificationPermissionPolicy.evaluate(effectivePermissionFacts)
        val permissionChanged = synchronized(lastPermissionState) {
            lastPermissionState.put(identity.fingerprint, permission.state) != permission.state
        }
        return when (updateGate.evaluate(job, elapsedRealtime(), force || permissionChanged)) {
            NotificationUpdateDecision.UNCHANGED -> NotificationPublishResult.Unchanged(tag, id)
            NotificationUpdateDecision.RATE_LIMITED -> NotificationPublishResult.RateLimited(tag, id)
            NotificationUpdateDecision.PUBLISH -> publishNow(
                presentation = presentation,
                permission = permission,
                identity = identity,
                tag = tag,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishNow(
        presentation: ProcessingNotificationPresentation,
        permission: NotificationPermissionDecision,
        identity: ProcessingJobIdentity,
        tag: String,
    ): NotificationPublishResult = when (presentation) {
        is ProcessingNotificationPresentation.Remove -> {
            notificationManager.cancel(tag, presentation.notificationId)
            clearLocalState(presentation.identity)
            NotificationPublishResult.Removed(tag, presentation.notificationId)
        }
        is ProcessingNotificationPresentation.Show -> {
            val built = builder.build(presentation.spec)
            if (!permission.canPostToNotificationDrawer) {
                NotificationPublishResult.PermissionBlocked(built, permission)
            } else {
                try {
                    notificationManager.notify(
                        built.notificationTag,
                        built.notificationId,
                        built.notification,
                    )
                    NotificationPublishResult.Posted(built, permission)
                } catch (_: SecurityException) {
                    // Permission can change between reading facts and notify(). Retry later.
                    clearLocalState(identity)
                    NotificationPublishResult.PlatformRejected(built, permission)
                }
            }
        }
    }

    override fun restore(
        jobs: Collection<PersistedProcessingJob>,
        permissionFacts: NotificationPermissionFacts,
    ): NotificationRestoreResult {
        val byFingerprint = jobs.associateBy { it.identity.fingerprint }
        require(byFingerprint.size == jobs.size) { "restore jobs must have unique identities" }
        val retainedTags = byFingerprint.values
            .filter { it.status != JobNotificationStatus.CANCELLED }
            .mapTo(mutableSetOf()) { it.identity.notificationTag() }
        var removed = 0
        platformNotificationManager.activeNotifications.forEach { active ->
            val tag = active.tag ?: return@forEach
            if (tag.startsWith(NOTIFICATION_TAG_PREFIX) && tag !in retainedTags) {
                notificationManager.cancel(tag, active.id)
                removed++
            }
        }
        return NotificationRestoreResult(
            publishResults = byFingerprint.values.map { publish(it, permissionFacts, force = true) },
            removedOrphanCount = removed,
        )
    }

    override fun remove(identity: ProcessingJobIdentity) {
        notificationManager.cancel(identity.notificationTag(), identity.notificationId())
        clearLocalState(identity)
    }

    private fun clearLocalState(identity: ProcessingJobIdentity) {
        updateGate.clear(identity)
        synchronized(lastPermissionState) { lastPermissionState.remove(identity.fingerprint) }
    }
}
