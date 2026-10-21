package com.realityforge.scanner.processing.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.realityforge.scanner.R

object AndroidNotificationPermissionFactsReader {
    fun read(
        context: Context,
        hasRequestedRuntimePermission: Boolean,
        shouldShowRationale: Boolean,
        channel: NotificationChannelKind = NotificationChannelKind.PROCESSING,
    ): NotificationPermissionFacts {
        val granted = Build.VERSION.SDK_INT < NotificationPermissionPolicy.POST_NOTIFICATIONS_API ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        val platformChannel = manager.getNotificationChannel(channel.channelId)
        return NotificationPermissionFacts(
            sdkInt = Build.VERSION.SDK_INT,
            runtimePermissionGranted = granted,
            notificationsEnabled = NotificationManagerCompat.from(context)
                .areNotificationsEnabled(),
            channelEnabled = platformChannel == null ||
                platformChannel.importance != NotificationManager.IMPORTANCE_NONE,
            hasRequestedRuntimePermission = hasRequestedRuntimePermission,
            shouldShowRationale = shouldShowRationale,
        )
    }

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun channelNotificationSettingsIntent(
        context: Context,
        channel: NotificationChannelKind,
    ): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel.channelId)
}

class ResourceProcessingNotificationText(
    private val context: Context,
) : ProcessingNotificationText {
    override fun queuedTitle(): String = context.getString(R.string.a029_notification_queued_title)
    override fun runningTitle(): String = context.getString(R.string.a029_notification_running_title)
    override fun pausedTitle(): String = context.getString(R.string.a029_notification_paused_title)
    override fun failedTitle(): String = context.getString(R.string.a029_notification_failed_title)
    override fun completedTitle(): String = context.getString(R.string.a029_notification_completed_title)

    override fun queuedBody(): String = context.getString(R.string.a029_notification_queued_body)

    override fun runningUnknownBody(stage: String): String = context.getString(
        R.string.a029_notification_running_unknown_body,
        stage,
    )

    override fun runningKnownBody(stage: String, completed: Int, total: Int): String =
        context.getString(
            R.string.a029_notification_running_known_body,
            stage,
            completed,
            total,
        )

    override fun pausedBody(stage: String): String = context.getString(
        R.string.a029_notification_paused_body,
        stage,
    )

    override fun failedBody(): String = context.getString(R.string.a029_notification_failed_body)
    override fun completedBody(): String =
        context.getString(R.string.a029_notification_completed_body)

    override fun stage(stage: ProcessingStage?): String = context.getString(
        when (stage) {
            null -> R.string.a029_stage_unknown
            ProcessingStage.PREPARING -> R.string.a029_stage_preparing
            ProcessingStage.FEATURES -> R.string.a029_stage_features
            ProcessingStage.MATCHING -> R.string.a029_stage_matching
            ProcessingStage.CAMERA_REGISTRATION -> R.string.a029_stage_camera_registration
            ProcessingStage.DENSE_DEPTH -> R.string.a029_stage_dense_depth
            ProcessingStage.SURFACE -> R.string.a029_stage_surface
            ProcessingStage.TEXTURE -> R.string.a029_stage_texture
            ProcessingStage.FINALIZING -> R.string.a029_stage_finalizing
        },
    )

    override fun action(action: JobNotificationActionType): String = context.getString(
        when (action) {
            JobNotificationActionType.PAUSE -> R.string.a029_action_pause
            JobNotificationActionType.RESUME -> R.string.a029_action_resume
            JobNotificationActionType.CANCEL -> R.string.a029_action_cancel
            JobNotificationActionType.OPEN -> R.string.a029_action_open
        },
    )

    override fun publicTitle(): String = context.getString(R.string.a029_public_title)
    override fun publicBody(): String = context.getString(R.string.a029_public_body)
}
