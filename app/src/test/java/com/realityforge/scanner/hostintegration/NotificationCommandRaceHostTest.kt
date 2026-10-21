package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.room.Room
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.processing.notifications.*
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.JobInputSnapshot
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import com.realityforge.scanner.engine.pipeline.sha256Utf8
import com.realityforge.scanner.work.execution.ReconstructionExecutionController
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Actual Room/controller notification command interleaving; no service scheduler or native kernel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationCommandRaceHostTest {
    /** Controller startup restores notifications on Main; drive Robolectric's real paused looper. */
    private fun host(block:suspend ()->Unit) = runBlocking {
        val work=async(Dispatchers.Default) { block() }
        withTimeout(90_000) {
            while(!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle();delay(5) }
            work.await()
        }
    }

    private suspend fun fixture(block:suspend (Context,ScanDatabase,ProjectRepository,ReconstructionExecutionController)->Unit) {
        val app:Application=RuntimeEnvironment.getApplication()
        val root=File(app.cacheDir,"admission-host-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=root
            override fun getApplicationContext():Context=this
        }
        val name="admission-host-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,ScanDatabase::class.java,name).build()
        var controller:ReconstructionExecutionController?=null
        var primary:Throwable?=null
        try {
            val repository=ProjectRepository(context,db.dao(),database=db)
            val actual=ReconstructionExecutionController(context,db,repository);controller=actual
            actual.awaitRecovery()
            block(context,db,repository,actual)
        } catch(failure:Throwable) { primary=failure;throw failure }
        finally {
            // No new production shutdown API: stop/join the actual controller's singleton scope.
            // This is lifecycle cleanup only, not a replacement execution implementation.
            withContext(NonCancellable) {
                var stopped=controller==null
                var cleanupFailure:Throwable?=null
                fun record(cleanup:Throwable) {
                    val previous=cleanupFailure
                    if(previous==null) cleanupFailure=cleanup
                    else if(previous!==cleanup) previous.addSuppressed(cleanup)
                }
                try {
                    controller?.let { actual ->
                        val field=ReconstructionExecutionController::class.java.getDeclaredField("scope").apply { isAccessible=true }
                        val scope=field.get(actual) as CoroutineScope
                        withTimeout(10_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
                        stopped=true
                    }
                } catch(cleanup:Throwable) { record(cleanup) }
                if(stopped) {
                    val actions=listOf<()->Unit>(
                        { db.close() },
                        { check(!context.getDatabasePath(name).exists() || context.deleteDatabase(name)) },
                        { check(!root.exists() || root.deleteRecursively()) },
                    )
                    for(action in actions) try { action() } catch(cleanup:Throwable) { record(cleanup) }
                }
                cleanupFailure?.let { cleanup ->
                    val original=primary
                    if(original==null) throw cleanup
                    if(original!==cleanup) original.addSuppressed(cleanup)
                }
            }
        }
    }

    private suspend fun enqueue(repository: ProjectRepository, controller: ReconstructionExecutionController): JobSnapshot {
        val project = repository.create("Notification command race")
        val engine = ReconstructionExecutionController.ENGINE_VERSION
        return controller.jobs.enqueue(JobInputSnapshot(project, 0, UUID.randomUUID().toString(),
            "a".repeat(64), "b".repeat(64), "c".repeat(64), sha256Utf8(engine), engine), System.currentTimeMillis())
    }

    @Test(timeout=120_000) fun authorizedOldNotificationMustNotCancelNewAttempt() = host {
        fixture { _, _, repository, controller ->
            val queued = enqueue(repository, controller)
            val action = JobNotificationAction(JobNotificationActionType.CANCEL, queued.toNotification().identity)
            assertTrue(JobNotificationActionAuthorizer().authorize(action, queued.toNotification()) is JobActionAuthorization.Allowed)
            val next = requireNotNull(controller.jobs.acquireNext("replacement-worker", System.currentTimeMillis(), 45_000))
            assertEquals(queued.attempt + 1, next.snapshot.attempt)
            // Delivery revalidates the original notification identity after the actual acquisition.
            assertFalse(controller.dispatchNotificationAction(action))
            val after = requireNotNull(controller.jobs.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.RUNNING, after.state)
            assertEquals(next.lease, after.lease)
        }
    }
    @Test(timeout=120_000) fun currentPauseResumeAndCancelRemainUsableAfterLeaseRenewal() = host {
        fixture { _, _, repository, controller ->
            val queued = enqueue(repository, controller)
            val running = requireNotNull(controller.jobs.acquireNext("worker", System.currentTimeMillis(), 45_000))
            val pause = JobNotificationAction(JobNotificationActionType.PAUSE, running.snapshot.toNotification().identity)
            controller.jobs.renew(running.lease, System.currentTimeMillis(), 45_000)
            assertTrue(controller.dispatchNotificationAction(pause))
            val paused = requireNotNull(controller.jobs.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.PAUSED, paused.state); assertNull(paused.lease)
            assertTrue(controller.dispatchNotificationAction(JobNotificationAction(JobNotificationActionType.RESUME, paused.toNotification().identity)))
            assertEquals(ReconstructionJobState.QUEUED, controller.jobs.snapshot(queued.jobId)?.state)
            val resumed = requireNotNull(controller.jobs.acquireNext("worker-2", System.currentTimeMillis(), 45_000))
            assertTrue(controller.dispatchNotificationAction(JobNotificationAction(JobNotificationActionType.CANCEL, resumed.snapshot.toNotification().identity)))
            val cancelled = requireNotNull(controller.jobs.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.CANCELLED, cancelled.state); assertNull(cancelled.lease)
        }
    }
    @Test(timeout=120_000) fun cancellingOtherQueuedJobPreservesRunningJobPresentation() = host {
        fixture { _, _, repository, controller ->
            val runningJob = enqueue(repository, controller)
            val acquired = requireNotNull(controller.jobs.acquireNext("presentation-worker", System.currentTimeMillis(), 45_000))
            assertEquals(runningJob.jobId, acquired.snapshot.jobId)
            val other = enqueue(repository, controller)
            val runningField = ReconstructionExecutionController::class.java.getDeclaredField("runningJobId").apply { isAccessible = true }
            val currentField = ReconstructionExecutionController::class.java.getDeclaredField("mutableCurrent").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val running = runningField.get(controller) as AtomicReference<JobId?>
            @Suppress("UNCHECKED_CAST")
            val current = currentField.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<JobSnapshot?>
            // Presentation ownership only: real Room lease, no fabricated JNI token or running kernel.
            running.set(acquired.snapshot.jobId); current.value = acquired.snapshot
            try {
                assertTrue(controller.dispatchNotificationAction(JobNotificationAction(JobNotificationActionType.CANCEL,
                    other.toNotification().identity)))
                assertEquals(ReconstructionJobState.CANCELLED, controller.jobs.snapshot(other.jobId)?.state)
                assertEquals(acquired.snapshot, controller.current.value)
                assertEquals(acquired.lease, controller.jobs.snapshot(runningJob.jobId)?.lease)
            } finally { running.set(null) }
        }
    }
}
