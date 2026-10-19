package com.realityforge.scanner.processing.notifications

import java.security.MessageDigest

/**
 * Immutable identity for one durable processing attempt.
 *
 * Project, job, operation and source revision all participate in equality and in every
 * notification action. Reusing only a project id would allow an old notification to control a
 * replacement job after process recreation.
 */
data class ProcessingJobIdentity(
    val projectId: String,
    val jobId: String,
    val operationId: String,
    val sourceRevision: Long,
) {
    init {
        validateIdentifier("projectId", projectId)
        validateIdentifier("jobId", jobId)
        validateIdentifier("operationId", operationId)
        require(sourceRevision >= 0L) { "sourceRevision must be non-negative" }
    }

    /** A non-sensitive digest suitable for Intent data and notification/request ids. */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        sha256(canonicalBytes()).toHex()
    }

    fun notificationTag(): String = NOTIFICATION_TAG_PREFIX + fingerprint

    fun notificationId(): Int = positiveInt(sha256(canonicalBytes()))

    fun requestCode(action: JobNotificationActionType): Int =
        positiveInt(sha256(canonicalBytes() + byteArrayOf(0) + action.wireName.toByteArray(Charsets.UTF_8)))

    internal fun canonicalBytes(): ByteArray = buildString {
        appendLengthPrefixed(projectId)
        appendLengthPrefixed(jobId)
        appendLengthPrefixed(operationId)
        append(sourceRevision)
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendLengthPrefixed(value: String) {
        append(value.length)
        append(':')
        append(value)
        append('|')
    }

    private companion object {
        private fun validateIdentifier(name: String, value: String) {
            require(value.isNotBlank()) { "$name must not be blank" }
            require(value.length <= 256) { "$name is too long" }
            require(value.none(Char::isISOControl)) { "$name must not contain control characters" }
        }
    }
}

enum class JobNotificationStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == FAILED || this == COMPLETED || this == CANCELLED
}

enum class ProcessingStage(val wireName: String) {
    PREPARING("preparing"),
    FEATURES("features"),
    MATCHING("matching"),
    CAMERA_REGISTRATION("camera-registration"),
    DENSE_DEPTH("dense-depth"),
    SURFACE("surface"),
    TEXTURE("texture"),
    FINALIZING("finalizing"),
}

sealed interface StageProgress {
    data object Unknown : StageProgress

    /** Progress inside the current persisted stage, never an invented whole-job percentage. */
    data class Known(val completed: Int, val total: Int) : StageProgress {
        init {
            require(total > 0) { "total must be positive" }
            require(completed in 0..total) { "completed must be between zero and total" }
        }
    }
}

enum class JobNotificationActionType(val wireName: String) {
    PAUSE("pause"),
    RESUME("resume"),
    CANCEL("cancel"),
    OPEN("open"),
    ;

    companion object {
        fun fromWireName(value: String?): JobNotificationActionType? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class JobActionAvailability(
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canOpen: Boolean = true,
) {
    fun allows(action: JobNotificationActionType): Boolean = when (action) {
        JobNotificationActionType.PAUSE -> canPause
        JobNotificationActionType.RESUME -> canResume
        JobNotificationActionType.CANCEL -> canCancel
        JobNotificationActionType.OPEN -> canOpen
    }

    companion object {
        fun defaultFor(status: JobNotificationStatus): JobActionAvailability = when (status) {
            JobNotificationStatus.QUEUED -> JobActionAvailability(
                canPause = false,
                canResume = false,
                canCancel = true,
            )
            JobNotificationStatus.RUNNING -> JobActionAvailability(
                canPause = true,
                canResume = false,
                canCancel = true,
            )
            JobNotificationStatus.PAUSED -> JobActionAvailability(
                canPause = false,
                canResume = true,
                canCancel = true,
            )
            JobNotificationStatus.FAILED -> JobActionAvailability(
                canPause = false,
                canResume = false,
                canCancel = false,
            )
            JobNotificationStatus.COMPLETED,
            JobNotificationStatus.CANCELLED,
            -> JobActionAvailability(
                canPause = false,
                canResume = false,
                canCancel = false,
            )
        }
    }
}

/**
 * Minimal durable state consumed by Agent 29. It deliberately excludes project names, paths,
 * thumbnails and failure detail so notification rendering cannot leak them to a lock screen.
 */
data class PersistedProcessingJob(
    val identity: ProcessingJobIdentity,
    val status: JobNotificationStatus,
    val stage: ProcessingStage? = null,
    val progress: StageProgress = StageProgress.Unknown,
    val actions: JobActionAvailability = JobActionAvailability.defaultFor(status),
    val startedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "updatedAtEpochMillis must be non-negative" }
        require(startedAtEpochMillis == null || startedAtEpochMillis >= 0L) {
            "startedAtEpochMillis must be non-negative"
        }
        if (progress is StageProgress.Known) {
            require(stage != null) { "known progress requires a named persisted stage" }
        }
        require(status == JobNotificationStatus.CANCELLED || actions.canOpen) {
            "non-cancelled notifications require an OPEN destination"
        }
    }

    val requiresForegroundNotification: Boolean
        get() = status == JobNotificationStatus.RUNNING
}

data class JobNotificationAction(
    val type: JobNotificationActionType,
    val identity: ProcessingJobIdentity,
)

enum class NotificationChannelKind(val channelId: String) {
    PROCESSING("rf_processing"),
    RESULT("rf_processing_results"),
}

sealed interface NotificationProgressBar {
    data object None : NotificationProgressBar
    data object Indeterminate : NotificationProgressBar
    data class Determinate(val value: Int, val max: Int) : NotificationProgressBar {
        init {
            require(max > 0) { "max must be positive" }
            require(value in 0..max) { "value must be between zero and max" }
        }
    }
}

data class ProcessingNotificationSpec(
    val identity: ProcessingJobIdentity,
    val status: JobNotificationStatus,
    val channel: NotificationChannelKind,
    val title: String,
    val text: String,
    val publicTitle: String,
    val publicText: String,
    val progressBar: NotificationProgressBar,
    val actions: List<JobNotificationAction>,
    val contentAction: JobNotificationAction,
    val ongoing: Boolean,
    val autoCancel: Boolean,
    val onlyAlertOnce: Boolean,
    val whenEpochMillis: Long,
) {
    val notificationId: Int
        get() = identity.notificationId()
}

sealed interface ProcessingNotificationPresentation {
    data class Show(val spec: ProcessingNotificationSpec) : ProcessingNotificationPresentation
    data class Remove(val notificationId: Int, val identity: ProcessingJobIdentity) :
        ProcessingNotificationPresentation
}

internal const val NOTIFICATION_TAG_PREFIX = "rf-processing:"

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun positiveInt(bytes: ByteArray): Int {
    require(bytes.size >= 4)
    val raw = ((bytes[0].toInt() and 0xff) shl 24) or
        ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or
        (bytes[3].toInt() and 0xff)
    val positive = raw and Int.MAX_VALUE
    return if (positive == 0) 1 else positive
}
