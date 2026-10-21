package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.pipeline.withNativeStageProgress
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic observations; actual production wrappers and native solvers. No image or device claim. */
class ProductionWrapperHostTest {
    private suspend fun closeToken(token:Long,child:Job?,primary:Throwable?) {
        try {
            withContext(NonCancellable) {
                // Never release the token while a launched child still owns native work.
                if(child!=null) { child.cancel();withTimeout(10000) { child.join() } }
                check(NativeCore.finishReconstruction(token)) { "Native lease cleanup failed" }
            }
        } catch(cleanup:Throwable) {
            if(primary!=null) { if(primary!==cleanup) primary.addSuppressed(cleanup) } else throw cleanup
        }
    }

    private fun execute(f:FinalSparseJniFixture, token:Long):FinalSparseExecution.Result {
        val frontier=NativeFrontierGeometry.forOperation(token)
        return FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,40,
            triangulate={ window -> frontier.triangulate(window.cameraRows,window.normalizedRows,.003).map { p ->
                require(p.track in window.globalTrackIds.indices)
                BoundedSparseCoordinator.Candidate(window.globalTrackIds[p.track],p.xyz[0],p.xyz[1],p.xyz[2])
            } }, bundle={ input -> NativeResultJson.decode(NativeCore.bundleAdjustProblemCancellable(
                input.cameraRows,input.seedRows,input.pixelRows,input.intrinsicRows,input.groupIds,
                input.photoIds,input.sourceTrackIds,input.measured,false,.003,15,token)) })
    }
    private fun anchors(f:FinalSparseJniFixture):Pair<JSONObject,JSONArray> =
        JSONObject().put("schema",1).put("pointCount",1).put("sentinel","original_anchor")
            .put("points",JSONArray().put(JSONArray(listOf(0.0,0.0,3.0,130,90,60,0.0,2.0,123)))) to
        JSONArray(f.views.mapIndexed { i,v -> JSONObject().put("photoId",f.photoIds[i])
            .put("rotation",JSONArray(v.rotation.toList())).put("translation",JSONArray(v.translation.toList())) })

    @Test fun realFrontierAndFinalSparseThroughProgressWrapperPublishOffsideAndStopCallbacks() = runBlocking {
        val f=FinalSparseJniFixture();val token=NativeCore.beginReconstruction()
        var primary:Throwable?=null;var child:Job?=null
        val count=AtomicInteger();val observed=CountDownLatch(1)
        try {
            val result=withContext(Dispatchers.Default) { withNativeStageProgress(token,{ label,done,total ->
                assertTrue(label.isNotBlank());assertTrue(done>=0);assertTrue(total>=0)
                count.incrementAndGet();observed.countDown()
            }) {
                val frontier=NativeFrontierGeometry.forOperation(token)
                val rows=DoubleArray(f.trackCount*5) { i -> val k=i/5; val c=i%5
                    if(c<3) f.xyz[k][c] else f.views[0].normalized(k).let { if(c==3) it.first else it.second } }
                val registered=frontier.register(rows,.003)
                assertTrue(registered.inliers.size>=16)
                val actual=execute(f,token)
                // Test scheduling barrier only: progress belongs to the completed real native calls.
                assertTrue("No actual native progress callback",observed.await(5,TimeUnit.SECONDS))
                actual
            } }
            assertEquals(48,result.geometry.getInt("pointCount"));assertEquals(576,result.receipt.acceptedObservations)
            val (anchor,cameras)=anchors(f);val before=anchor.toString();val beforeCameras=cameras.toString()
            val staged=FinalSparsePublication.prepare(anchor,cameras,f.photoIds,f.views,f.snapshot,result)
            assertEquals(48,staged.output.getInt("pointCount"));assertEquals(48,staged.observations.size)
            assertEquals(before,anchor.toString());assertEquals(beforeCameras,cameras.toString())
            val after=count.get();delay(250);assertEquals("Reporter escaped completed scope",after,count.get())
            assertTrue(after>0)
        } catch(t:Throwable) { primary=t;throw t } finally { closeToken(token,child,primary) }
    }

    /** Probe real JNI until the reporter's cancellation finally marks this lease cancelled. */
    private fun awaitNativeCancellation(f:FinalSparseJniFixture,token:Long):Boolean {
        val frontier=NativeFrontierGeometry.forOperation(token)
        val plan=f.plan();var cancelled=false
        // Use the actual plan window without inventing packed native observations.
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<deadline && !cancelled) {
            try { plan.execute(.003,solver={ w -> frontier.triangulate(w.cameraRows,w.normalizedRows,.003).map { p ->
                BoundedSparseCoordinator.Candidate(w.globalTrackIds[p.track],p.xyz[0],p.xyz[1],p.xyz[2])
            } }) } catch(expected:java.util.concurrent.CancellationException) { cancelled=true }
            if(!cancelled) Thread.sleep(5)
        }
        return cancelled
    }

    @Test fun coroutineCancellationThroughReporterCancelsActualNativeLeaseAndNeverPublishes() = runBlocking {
        val f=FinalSparseJniFixture();val token=NativeCore.beginReconstruction()
        var primary:Throwable?=null;var child:Job?=null
        val entered=CountDownLatch(1);val cancelled=AtomicBoolean();val published=AtomicBoolean()
        val completion=AtomicReference<Throwable?>()
        try {
            val job=launch(Dispatchers.Default) {
                val result=withNativeStageProgress(token,{_,_,_->}) {
                    val actual=execute(f,token);entered.countDown()
                    cancelled.set(awaitNativeCancellation(f,token));actual
                }
                ensureActive()
                val (anchor,cameras)=anchors(f)
                FinalSparsePublication.prepare(anchor,cameras,f.photoIds,f.views,f.snapshot,result)
                published.set(true)
            }
            child=job
            job.invokeOnCompletion { completion.set(it) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
            job.cancel(CancellationException("owner-cancel-probe"));withTimeout(10000) { job.join() }
            assertTrue(completion.get() is CancellationException)
            assertEquals("owner-cancel-probe",completion.get()?.message)
            assertTrue(job.isCancelled);assertTrue("Real native token was not cancelled",cancelled.get())
            assertFalse(published.get())
        } catch(t:Throwable) { primary=t;throw t } finally { closeToken(token,child,primary) }
    }

    @Test fun progressSinkFailureCancelsRealLeasePropagatesAndCannotPublish() = runBlocking {
        val f=FinalSparseJniFixture();val token=NativeCore.beginReconstruction()
        var primary:Throwable?=null;var child:Job?=null
        val cancelled=AtomicBoolean();val published=AtomicBoolean();val callbacks=AtomicInteger()
        val ready=CompletableDeferred<Unit>()
        try {
            val failure=try {
                withContext(Dispatchers.Default) {
                    val result=withNativeStageProgress(token,{_,_,_-> ready.await();callbacks.incrementAndGet();throw IllegalStateException("injected-progress-sink") }) {
                        val actual=execute(f,token);ready.complete(Unit)
                        cancelled.set(awaitNativeCancellation(f,token));actual
                    }
                    val (anchor,cameras)=anchors(f)
                    FinalSparsePublication.prepare(anchor,cameras,f.photoIds,f.views,f.snapshot,result)
                    published.set(true)
                }
                null
            } catch(t:Throwable) { t }
            assertNotNull("Sink failure swallowed",failure)
            assertTrue("Unexpected primary: $failure",failure is IllegalStateException)
            assertEquals("injected-progress-sink",failure?.message)
            assertTrue(callbacks.get()>0);assertTrue(cancelled.get());assertFalse(published.get())
            val after=callbacks.get();delay(250);assertEquals(after,callbacks.get())
        } catch(t:Throwable) { primary=t;throw t } finally { closeToken(token,child,primary) }
    }
}
