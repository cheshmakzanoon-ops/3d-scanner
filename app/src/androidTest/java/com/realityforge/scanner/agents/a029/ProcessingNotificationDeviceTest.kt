package com.realityforge.scanner.agents.a029

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.R
import com.realityforge.scanner.processing.notifications.AndroidProcessingNotificationController
import com.realityforge.scanner.processing.notifications.JobActionDecodeRejection
import com.realityforge.scanner.processing.notifications.JobActionDecodeResult
import com.realityforge.scanner.processing.notifications.JobNotificationAction
import com.realityforge.scanner.processing.notifications.JobNotificationActionType
import com.realityforge.scanner.processing.notifications.JobNotificationPendingIntentFactory
import com.realityforge.scanner.processing.notifications.JobNotificationStatus
import com.realityforge.scanner.processing.notifications.NotificationChannelKind
import com.realityforge.scanner.processing.notifications.PersistedProcessingJob
import com.realityforge.scanner.processing.notifications.ProcessingJobIdentity
import com.realityforge.scanner.processing.notifications.ProcessingStage
import com.realityforge.scanner.processing.notifications.StageProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingNotificationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val commandComponent = ComponentName(
        context,
        "${context.packageName}.processing.ReconstructionService",
    )
    private val openComponent = ComponentName(
        context,
        "${context.packageName}.MainActivity",
    )
    private val identity = ProcessingJobIdentity(
        projectId = "project-private-name",
        jobId = "job-/storage/emulated/0/DCIM/object.jpg",
        operationId = "operation-29",
        sourceRevision = 17,
    )

    @Test
    fun explicitActionIntentRoundTripsAndUriContainsOnlyDigest() {
        val factory = JobNotificationPendingIntentFactory(
            context,
            commandComponent,
            openComponent,
        )
        val action = JobNotificationAction(JobNotificationActionType.PAUSE, identity)
        val intent = factory.createIntent(action)

        assertEquals(commandComponent, intent.component)
        assertEquals(context.packageName, intent.`package`)
        val uri = requireNotNull(intent.data).toString()
        assertTrue(uri.contains(identity.fingerprint))
        assertFalse(uri.contains(identity.projectId))
        assertFalse(uri.contains("/storage/"))
        assertEquals(
            JobActionDecodeResult.Decoded(action),
            JobNotificationPendingIntentFactory.decode(
                intent,
                context.packageName,
                commandComponent,
            ),
        )
    }

    @Test
    fun tamperedDigestCannotBecomeACommand() {
        val factory = JobNotificationPendingIntentFactory(
            context,
            commandComponent,
            openComponent,
        )
        val original = factory.createIntent(
            JobNotificationAction(JobNotificationActionType.CANCEL, identity),
        )
        val tampered = Intent(original).setData(
            Uri.parse(
                "${context.packageName}://processing-action/v1/cancel/${"0".repeat(64)}",
            ),
        )

        assertEquals(
            JobActionDecodeResult.Rejected(JobActionDecodeRejection.FINGERPRINT_MISMATCH),
            JobNotificationPendingIntentFactory.decode(
                tampered,
                context.packageName,
                commandComponent,
            ),
        )
    }


    @Test
    fun wrongPackageOrComponentCannotBecomeACommand() {
        val factory = JobNotificationPendingIntentFactory(
            context,
            commandComponent,
            openComponent,
        )
        val original = factory.createIntent(
            JobNotificationAction(JobNotificationActionType.PAUSE, identity),
        )

        assertEquals(
            JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_PACKAGE),
            JobNotificationPendingIntentFactory.decode(
                Intent(original).setPackage("com.example.attacker"),
                context.packageName,
                commandComponent,
            ),
        )
        assertEquals(
            JobActionDecodeResult.Rejected(JobActionDecodeRejection.WRONG_COMPONENT),
            JobNotificationPendingIntentFactory.decode(
                Intent(original).setComponent(openComponent),
                context.packageName,
                commandComponent,
            ),
        )
    }

    @Test
    fun replacementOperationGetsDistinctImmutablePendingIntentIdentity() {
        val factory = JobNotificationPendingIntentFactory(
            context,
            commandComponent,
            openComponent,
        )
        val first = factory.create(
            JobNotificationAction(JobNotificationActionType.CANCEL, identity),
        )
        val replacement = factory.create(
            JobNotificationAction(
                JobNotificationActionType.CANCEL,
                identity.copy(operationId = "replacement-operation"),
            ),
        )
        try {
            assertNotEquals(first, replacement)
            if (Build.VERSION.SDK_INT >= 31) {
                assertTrue(first.isImmutable)
                assertTrue(replacement.isImmutable)
            }
        } finally {
            first.cancel()
            replacement.cancel()
        }
    }

    @Test
    fun builtRunningNotificationIsPrivateGenericAndStageScoped() {
        val controller = AndroidProcessingNotificationController(
            context = context,
            commandComponent = commandComponent,
            openComponent = openComponent,
            smallIconRes = R.drawable.ic_launcher,
        )
        val built = requireNotNull(
            controller.build(
                PersistedProcessingJob(
                    identity = identity,
                    status = JobNotificationStatus.RUNNING,
                    stage = ProcessingStage.DENSE_DEPTH,
                    progress = StageProgress.Known(3, 8),
                    startedAtEpochMillis = 100,
                    updatedAtEpochMillis = 200,
                ),
            ),
        )

        assertTrue(built.requiresForegroundService)
        assertEquals(NotificationChannelKind.PROCESSING.channelId, built.notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, built.notification.visibility)
        assertNotNull(built.notification.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, built.notification.publicVersion.visibility)
        assertEquals(8, built.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(3, built.notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(built.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(2, requireNotNull(built.notification.actions).size)

        val privateCopy = listOf(
            built.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            built.notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ")
        val publicCopy = listOf(
            built.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE),
            built.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ")
        assertFalse(privateCopy.contains(identity.projectId))
        assertFalse(privateCopy.contains("/storage/"))
        assertFalse(publicCopy.contains(identity.projectId))
        assertFalse(publicCopy.contains("/storage/"))
    }
}
