package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.room.Room
import com.realityforge.scanner.engine.NativeCore
import java.lang.reflect.Modifier
import java.security.MessageDigest
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

/** HOST actual notification transaction and JNI entrypoint cancellation; reflected ownership only, no worker/kernel/service qualification. */
@RunWith(SandboxNativeLoaderRunner::class)
@Config(sdk=[35], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationNativeTokenHostTest {
    /** Controller startup restores notifications on Main; drive Robolectric's real paused looper. */
    private fun host(block:suspend ()->Unit) = runBlocking {
        val work=async(Dispatchers.Default) { block() }
        withTimeout(90_000) {
            while(!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle();delay(5) }
            work.await()
        }
    }

    private suspend fun fixture(block:suspend (Context,ScanDatabase,ProjectRepository,ReconstructionExecutionController,MutableList<Long>)->Unit) {
        val app:Application=RuntimeEnvironment.getApplication()
        val root=File(app.cacheDir,"admission-host-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=root
            override fun getApplicationContext():Context=this
        }
        val name="admission-host-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,ScanDatabase::class.java,name).build()
        val tokens=mutableListOf<Long>()
        var controller:ReconstructionExecutionController?=null
        var primary:Throwable?=null
        try {
            val repository=ProjectRepository(context,db.dao(),database=db)
            val actual=ReconstructionExecutionController(context,db,repository);controller=actual
            actual.awaitRecovery()
            block(context,db,repository,actual,tokens)
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
                    val actions = tokens.map { token -> { check(NativeCore.finishReconstruction(token)); Unit } } + listOf<()->Unit>(
                        { controller?.let { setOwnership(it,null,-1,0) } },
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

    @Test(timeout=120_000) fun realNotificationCommandsSignalOnlyExactCurrentNativeToken() = host {
        verifyOriginalNativeOwnerBeforeLoad()
        fixture { _, _, repository, controller, tokens ->
            val queued=enqueue(repository,controller)
            val first=requireNotNull(controller.jobs.acquireNext("native-first",System.currentTimeMillis(),45_000))
            assertEquals(queued.jobId,first.snapshot.jobId)
            val oldAction=JobNotificationAction(JobNotificationActionType.CANCEL,first.snapshot.toNotification().identity)
            val oldToken=NativeCore.beginReconstruction().also { assertTrue(it>0);tokens+=it }
            verifyNativeMappings()
            assertUncancelled(oldToken)
            // Actual durable attempt transition; old native handle retained solely as isolation sentinel.
            controller.jobs.pause(queued.jobId,System.currentTimeMillis(),"HOST_TOKEN_SENTINEL")
            controller.jobs.resume(queued.jobId,System.currentTimeMillis())
            val current=requireNotNull(controller.jobs.acquireNext("native-current",System.currentTimeMillis(),45_000))
            assertEquals(first.snapshot.attempt+1,current.snapshot.attempt)
            val currentToken=NativeCore.beginReconstruction().also { assertTrue(it>0);tokens+=it }
            assertNotEquals(oldToken,currentToken)
            assertUncancelled(currentToken)
            // No worker is started. These are actual Room ownership IDs and actual native handles.
            setOwnership(controller,current.snapshot.jobId,current.snapshot.attempt,currentToken)
            assertFalse(controller.dispatchNotificationAction(oldAction))
            assertEquals(current.lease,controller.jobs.snapshot(queued.jobId)?.lease)
            assertEquals(ReconstructionJobState.RUNNING,controller.jobs.snapshot(queued.jobId)?.state)
            assertUncancelled(oldToken);assertUncancelled(currentToken)
            val other=enqueue(repository,controller)
            assertTrue(controller.dispatchNotificationAction(JobNotificationAction(JobNotificationActionType.CANCEL,other.toNotification().identity)))
            assertEquals(ReconstructionJobState.CANCELLED,controller.jobs.snapshot(other.jobId)?.state)
            assertEquals(current.lease,controller.jobs.snapshot(queued.jobId)?.lease)
            assertUncancelled(oldToken);assertUncancelled(currentToken)
            assertTrue(controller.dispatchNotificationAction(JobNotificationAction(JobNotificationActionType.CANCEL,current.snapshot.toNotification().identity)))
            val cancelled=requireNotNull(controller.jobs.snapshot(queued.jobId))
            assertEquals(ReconstructionJobState.CANCELLED,cancelled.state);assertNull(cancelled.lease)
            assertUncancelled(oldToken)
            val failure=assertThrows(java.util.concurrent.CancellationException::class.java) {
                NativeCore.reconstructCancellable(DoubleArray(0),0.01,currentToken)
            }
            assertEquals("Reconstruction cancelled",failure.message)
            // Poll is only schema/liveness evidence: it has no cancellation field.
            for(token in tokens) { val progress=NativeCore.pollNativeProgress(token);assertEquals(7,progress.size);assertEquals(1L,progress[0]) }
            verifyNativeMappings();verifyNativeFiles()
            println("HOST_NOTIFICATION_NATIVE staleAttempt=preserved otherJob=preserved currentToken=cancelled sentinel=uncancelled oracle=entrypoint_not_kernel")
        }
    }
    private fun assertUncancelled(token:Long) {
        val failure=assertThrows(IllegalArgumentException::class.java) {
            NativeCore.reconstructCancellable(DoubleArray(0),0.01,token)
        }
        assertEquals("Expected 20–20000 seven-element matches",failure.message)
    }
    private fun setOwnership(controller:ReconstructionExecutionController,id:JobId?,attempt:Long,token:Long) {
        fun field(name:String)=ReconstructionExecutionController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(controller)
        @Suppress("UNCHECKED_CAST")
        val owner=field("runningJobId") as AtomicReference<JobId?>
        owner.set(id);(field("runningAttempt") as AtomicLong).set(attempt);(field("nativeToken") as AtomicLong).set(token)
    }
    private fun required(key:String)=requireNotNull(System.getProperty("rf.loader.$key"))
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun nativeFile(key:String)=File(required(key)).canonicalFile
    private fun verifyNativeFiles() {
        assertEquals("427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce",sha(nativeFile("shim").readBytes()))
        assertEquals("4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b",sha(nativeFile("engine").readBytes()))
    }
    private fun mappings()=File("/proc/self/maps").readLines().mapNotNull { it.split(Regex("\\s+"),limit=6).getOrNull(5) }
        .filter { it.startsWith("/") }.map { File(it).canonicalFile }.filter { it.name.startsWith("librealityforge") || it.name.startsWith("libopencv") }.map { it.path }.toSet()
    private fun verifyNativeMappings() { assertEquals(setOf(nativeFile("shim").path,nativeFile("engine").path),mappings()) }
    private fun verifyOriginalNativeOwnerBeforeLoad() {
        verifyNativeFiles();assertTrue("No parent native preload",mappings().isEmpty())
        val alias=File(required("aliasDir")).canonicalFile
        assertEquals(alias.path,System.getProperty("java.library.path"))
        assertEquals(nativeFile("shim"),File(alias,"librealityforge.so").canonicalFile)
        val loader=javaClass.classLoader
        assertTrue(loader.javaClass.name.contains("SandboxClassLoader"))
        val name="com.realityforge.scanner.engine.NativeCore"
        val owner=Class.forName(name,false,loader)
        assertSame(loader,owner.classLoader)
        val bytes=requireNotNull(loader.getResourceAsStream(name.replace('.','/')+".class")).use { it.readBytes() }
        assertEquals(required("classSha.$name"),sha(bytes))
        assertTrue(Modifier.isNative(owner.getDeclaredMethod("beginReconstruction").modifiers))
        assertTrue(Modifier.isNative(owner.getDeclaredMethod("reconstructCancellable",DoubleArray::class.java,Double::class.javaPrimitiveType,Long::class.javaPrimitiveType).modifiers))
    }
}
