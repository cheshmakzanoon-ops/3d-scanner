package com.realityforge.scanner.processing.notifications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val ACTION_JOB_NOTIFICATION =
    "com.realityforge.scanner.processing.notifications.JOB_ACTION"
private const val ACTION_URI_AUTHORITY = "processing-action"
private const val ACTION_URI_VERSION = "v1"
private const val EXTRA_ACTION_TYPE = "a029.action_type"
private const val EXTRA_PROJECT_ID = "a029.project_id"
private const val EXTRA_JOB_ID = "a029.job_id"
private const val EXTRA_OPERATION_ID = "a029.operation_id"
private const val EXTRA_SOURCE_REVISION = "a029.source_revision"

sealed interface JobActionDecodeResult {
    data class Decoded(val action: JobNotificationAction) : JobActionDecodeResult
    data class Rejected(val reason: JobActionDecodeRejection) : JobActionDecodeResult
}

enum class JobActionDecodeRejection {
    WRONG_INTENT_ACTION,
    WRONG_PACKAGE,
    WRONG_COMPONENT,
    WRONG_URI,
    MISSING_PAYLOAD,
    ACTION_MISMATCH,
    INVALID_IDENTITY,
    FINGERPRINT_MISMATCH,
}

/** Explicit, immutable PendingIntents whose identity includes the complete durable job key. */
class JobNotificationPendingIntentFactory(
    context: Context,
    private val commandComponent: ComponentName,
    private val openComponent: ComponentName,
) {
    private val appContext = context.applicationContext

    fun create(action: JobNotificationAction): PendingIntent {
        val intent = createIntent(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action.type == JobNotificationActionType.OPEN) {
            PendingIntent.getActivity(appContext, action.identity.requestCode(action.type), intent, flags)
        } else {
            PendingIntent.getService(appContext, action.identity.requestCode(action.type), intent, flags)
        }
    }

    fun createIntent(action: JobNotificationAction): Intent {
        val component = if (action.type == JobNotificationActionType.OPEN) {
            openComponent
        } else {
            commandComponent
        }
        return Intent(ACTION_JOB_NOTIFICATION)
            .setComponent(component)
            .setPackage(appContext.packageName)
            .setData(actionUri(appContext.packageName, action))
            .putExtra(EXTRA_ACTION_TYPE, action.type.wireName)
            .putExtra(EXTRA_PROJECT_ID, action.identity.projectId)
            .putExtra(EXTRA_JOB_ID, action.identity.jobId)
            .putExtra(EXTRA_OPERATION_ID, action.identity.operationId)
            .putExtra(EXTRA_SOURCE_REVISION, action.identity.sourceRevision)
            .also { intent ->
                if (action.type == JobNotificationActionType.OPEN) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
    }

    companion object {
        fun decode(
            intent: Intent,
            expectedPackageName: String,
            expectedComponent: ComponentName? = null,
        ): JobActionDecodeResult {
            if (intent.action != ACTION_JOB_NOTIFICATION) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_INTENT_ACTION)
            }
            if (intent.`package` != expectedPackageName) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_PACKAGE)
            }
            if (expectedComponent != null && intent.component != expectedComponent) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_COMPONENT)
            }
            val data = intent.data
            val segments = data?.pathSegments
            if (data == null || data.scheme != expectedPackageName ||
                data.authority != ACTION_URI_AUTHORITY || segments == null ||
                segments.size != 3 || segments[0] != ACTION_URI_VERSION
            ) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_URI)
            }

            val uriAction = JobNotificationActionType.fromWireName(segments[1])
                ?: return JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_URI)
            val extraAction = JobNotificationActionType.fromWireName(
                intent.getStringExtra(EXTRA_ACTION_TYPE),
            ) ?: return JobActionDecodeResult.Rejected(JobActionDecodeRejection.MISSING_PAYLOAD)
            if (uriAction != extraAction) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.ACTION_MISMATCH)
            }

            val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
            val jobId = intent.getStringExtra(EXTRA_JOB_ID)
            val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            val revision = intent.getLongExtra(EXTRA_SOURCE_REVISION, Long.MIN_VALUE)
            if (projectId == null || jobId == null || operationId == null ||
                revision == Long.MIN_VALUE
            ) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.MISSING_PAYLOAD)
            }
            val identity = try {
                ProcessingJobIdentity(projectId, jobId, operationId, revision)
            } catch (_: IllegalArgumentException) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.INVALID_IDENTITY)
            }
            if (segments[2] != identity.fingerprint) {
                return JobActionDecodeResult.Rejected(JobActionDecodeRejection.FINGERPRINT_MISMATCH)
            }
            return JobActionDecodeResult.Decoded(JobNotificationAction(uriAction, identity))
        }

        private fun actionUri(
            packageName: String,
            action: JobNotificationAction,
        ): Uri = Uri.Builder()
            .scheme(packageName)
            .authority(ACTION_URI_AUTHORITY)
            .appendPath(ACTION_URI_VERSION)
            .appendPath(action.type.wireName)
            .appendPath(action.identity.fingerprint)
            .build()
    }
}
