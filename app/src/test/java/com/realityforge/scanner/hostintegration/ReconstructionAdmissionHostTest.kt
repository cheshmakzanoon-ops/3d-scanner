package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.room.Room
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
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

/** Actual controller early admission and generated Room DAOs; no images/native reconstruction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ReconstructionAdmissionHostTest {
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

    @Test(timeout=120_000) fun publicEnqueueRejectsNoncanonicalOperationBeforeCreatingDurableJob() = host {
        fixture { _,db,repository,controller ->
            val project=repository.create("Admission input only")
            val before=db.jobDao().recentNotificationJobs(1000).size
            for(operation in listOf("request-1","request:1","00000000-0000-0000-0000-00000000ABCD")) {
                try { controller.enqueue(project,0,operation,26.0);fail("Execution admitted noncanonical operation ID") }
                catch(expected:IllegalArgumentException) { assertEquals("Invalid operation identifier.",expected.message) }
            }
            assertEquals(before,db.jobDao().recentNotificationJobs(1000).size)
            // Canonical UUID still follows the unchanged production enqueue/service request path.
            val accepted=controller.enqueue(project,0,UUID.randomUUID().toString(),26.0)
            assertEquals(ReconstructionJobState.QUEUED,accepted.state)
            assertEquals(before+1,db.jobDao().recentNotificationJobs(1000).size)
        }
    }

    @Test(timeout=120_000) fun persistedSafeIdsFailInsideControllerWithoutOrphanRunningLease() = host {
        fixture { _,db,repository,controller ->
            for(request in listOf("request-1","request:1")) {
                val project=repository.create("Stored safe ID, not corrupt data")
                val engine=ReconstructionExecutionController.ENGINE_VERSION
                val input=JobInputSnapshot(project,0,request,"a".repeat(64),"b".repeat(64),
                    "c".repeat(64),sha256Utf8(engine),engine)
                // Deliberately bypass production enqueue: the generic store must still accept safe IDs.
                val queued=controller.jobs.enqueue(input,System.currentTimeMillis())
                assertEquals(ReconstructionJobState.QUEUED,queued.state)
                controller.start(onUpdate={},onIdle={})
                withTimeout(30_000) { controller.awaitWorkerStopped() }
                val failed=requireNotNull(controller.jobs.snapshot(queued.jobId))
                assertEquals(ReconstructionJobState.FAILED,failed.state)
                assertEquals("INVALID_INPUT",failed.outcomeCode);assertEquals(1L,failed.attempt);assertNull(failed.lease)
                assertEquals("Invalid operation identifier.",db.dao().project(project)?.message)
                assertNull(controller.progress.value)
                assertTrue(db.storageReservationDao().all().isEmpty())
                val native=ReconstructionExecutionController::class.java.getDeclaredField("nativeToken").apply { isAccessible=true }
                assertEquals(0L,(native.get(controller) as AtomicLong).get())
                val running=ReconstructionExecutionController::class.java.getDeclaredField("runningJobId").apply { isAccessible=true }
                assertNull((running.get(controller) as AtomicReference<*>).get())
            }
        }
    }

    @Test(timeout=120_000) fun completedAttemptCleanupPreservesDifferentRunningJobIdentity() = host {
        fixture { _,_,repository,controller ->
            val project=repository.create("Cleanup identity fence")
            val engine=ReconstructionExecutionController.ENGINE_VERSION
            val queued=controller.jobs.enqueue(JobInputSnapshot(project,0,"request-1",
                "a".repeat(64),"b".repeat(64),"c".repeat(64),sha256Utf8(engine),engine),System.currentTimeMillis())
            val field=ReconstructionExecutionController::class.java.getDeclaredField("runningJobId").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            val running=field.get(controller) as AtomicReference<JobId?>
            val replacement=JobId(UUID.randomUUID().toString())
            var injected=false
            controller.start(onUpdate={ snapshot ->
                if(snapshot.jobId==queued.jobId && snapshot.state==ReconstructionJobState.FAILED) {
                    // Inject only after the actual failure handler; cleanup must not clear another owner.
                    running.set(replacement)
                    injected=true
                }
            },onIdle={})
            withTimeout(30_000) { controller.awaitWorkerStopped() }
            assertTrue("Failure callback did not run",injected)
            assertEquals(ReconstructionJobState.FAILED,controller.jobs.snapshot(queued.jobId)?.state)
            assertEquals(replacement,running.get())
            running.set(null) // Test-only injected identity; worker is joined before fixture teardown.
        }
    }
}
