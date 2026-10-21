package com.realityforge.scanner.processing.notifications

import android.content.Context
import androidx.core.app.NotificationCompat

internal class ProcessingNotificationBuilder(
    context: Context,
    private val text: ProcessingNotificationText,
    private val intents: JobNotificationPendingIntentFactory,
    private val smallIconRes: Int,
) {
    private val appContext = context.applicationContext

    fun build(spec: ProcessingNotificationSpec): BuiltProcessingNotification {
        val publicVersion = NotificationCompat.Builder(appContext, spec.channel.channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(spec.publicTitle)
            .setContentText(spec.publicText)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .build()

        val builder = NotificationCompat.Builder(appContext, spec.channel.channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setContentIntent(intents.create(spec.contentAction))
            .setOngoing(spec.ongoing)
            .setAutoCancel(spec.autoCancel)
            .setOnlyAlertOnce(spec.onlyAlertOnce)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setShowWhen(true)
            .setWhen(spec.whenEpochMillis)
            .setCategory(
                if (spec.status == JobNotificationStatus.RUNNING ||
                    spec.status == JobNotificationStatus.QUEUED
                ) {
                    NotificationCompat.CATEGORY_PROGRESS
                } else {
                    NotificationCompat.CATEGORY_STATUS
                },
            )
            .setPriority(
                if (spec.channel == NotificationChannelKind.PROCESSING) {
                    NotificationCompat.PRIORITY_LOW
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )

        if (spec.channel == NotificationChannelKind.PROCESSING) {
            builder.setSilent(true)
        }
        if (spec.status == JobNotificationStatus.RUNNING) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        when (val progress = spec.progressBar) {
            NotificationProgressBar.None -> Unit
            NotificationProgressBar.Indeterminate -> builder.setProgress(0, 0, true)
            is NotificationProgressBar.Determinate ->
                builder.setProgress(progress.max, progress.value, false)
        }
        spec.actions.forEach { action ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    text.action(action.type),
                    intents.create(action),
                ).build(),
            )
        }

        return BuiltProcessingNotification(
            notificationTag = spec.identity.notificationTag(),
            notificationId = spec.notificationId,
            notification = builder.build(),
            requiresForegroundService = spec.status == JobNotificationStatus.RUNNING,
            identity = spec.identity,
        )
    }
}
