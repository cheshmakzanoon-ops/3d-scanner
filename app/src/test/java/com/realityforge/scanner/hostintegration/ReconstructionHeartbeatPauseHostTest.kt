package com.realityforge.scanner.hostintegration

import android.app.Application
import android.app.ActivityManager
import android.os.PowerManager
import android.os.BatteryManager
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.processing.resources.ResourceCheckpointPause
import org.robolectric.shadows.ShadowStatFs
import java.util.concurrent.atomic.AtomicInteger
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
class ReconstructionHeartbeatPauseHostTest {
    /** Controller startup restores notifications on Main; drive Robolectric's real paused looper. */
    private fun host(block:suspend ()->Unit) = runBlocking {
        val work=async(Dispatchers.Default) { block() }
        withTimeout(90_000) {
            while(!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle();delay(5) }
            work.await()
        }
    }

    private suspend fun fixture(daoTransform: (ScanDao)->ScanDao = { it }, block:suspend (Context,ScanDatabase,ProjectRepository,ReconstructionExecutionController)->Unit) {
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
            val repository=ProjectRepository(context,daoTransform(db.dao()),database=db)
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

    @Test(timeout=120_000) fun periodicThermalPauseDoesNotEscapeTheActualControllerWorker() = host {
        val reads=AtomicInteger()
        val enteredSecondRead=CompletableDeferred<Unit>()
        fixture(daoTransform={ raw -> object:ScanDao by raw {
            override suspend fun photos(id:String):List<ScanPhoto> {
                if(reads.incrementAndGet()==2) {
                    enteredSecondRead.complete(Unit)
                    awaitCancellation() // Before JNI; cancellation must unwind this actual repository read.
                }
                return raw.photos(id)
            }
        } }) { context,db,repository,controller ->
            val activity=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            shadowOf(activity).setMemoryInfo(ActivityManager.MemoryInfo().apply {
                lowMemory=false;availMem=2L*1024*1024*1024;totalMem=4L*1024*1024*1024
            })
            val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
            shadowOf(power).setCurrentThermalStatus(0)
            shadowOf(power).setIsPowerSaveMode(false)
            val battery=context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            shadowOf(battery).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY,80)
            shadowOf(battery).setIsCharging(true)
            ShadowStatFs.registerStats(context.filesDir,2_000_000,1_500_000,1_500_000)
            val escaped=AtomicReference<Throwable?>()
            val scopeField=ReconstructionExecutionController::class.java.getDeclaredField("scope").apply { isAccessible=true }
            val actualScope=scopeField.get(controller) as CoroutineScope
            // Keep the actual SupervisorJob/IO dispatcher; only observe otherwise uncaught failure.
            scopeField.set(controller,CoroutineScope(actualScope.coroutineContext +
                CoroutineExceptionHandler { _,failure -> escaped.set(failure) }))
            val project=repository.create("HOST actual heartbeat before native")
            val engine=ReconstructionExecutionController.ENGINE_VERSION
            val settings="""{"schema":1,"focal35":26.0}"""
            val queued=controller.jobs.enqueue(JobInputSnapshot(project,0,UUID.randomUUID().toString(),
                "a".repeat(64),"b".repeat(64),sha256Utf8(settings),sha256Utf8(engine),engine,
                settingsJson=settings),System.currentTimeMillis())
            controller.start(onUpdate={},onIdle={})
            withTimeout(20_000) { enteredSecondRead.await() }
            shadowOf(power).setCurrentThermalStatus(3)
            // Intentionally use production 10-second heartbeat, no clock/delay substitution.
            withTimeout(25_000) { controller.awaitWorkerStopped() }
            val paused=requireNotNull(controller.jobs.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.PAUSED,paused.state)
            assertEquals("RESOURCE_PAUSED",paused.outcomeCode)
            assertNull(paused.lease)
            assertEquals("INTERRUPTED",db.dao().project(project)?.state)
            assertNull(db.dao().project(project)?.resultKey)
            assertTrue(db.storageReservationDao().all().isEmpty())
            assertNull(controller.progress.value)
            val native=ReconstructionExecutionController::class.java.getDeclaredField("nativeToken").apply { isAccessible=true }
            assertEquals(0L,(native.get(controller) as AtomicLong).get())
            val running=ReconstructionExecutionController::class.java.getDeclaredField("runningJobId").apply { isAccessible=true }
            assertNull((running.get(controller) as AtomicReference<*>).get())
            assertNull("Resource pause escaped the worker after durable PAUSED and cleanup",escaped.get())
        }
    }
}
