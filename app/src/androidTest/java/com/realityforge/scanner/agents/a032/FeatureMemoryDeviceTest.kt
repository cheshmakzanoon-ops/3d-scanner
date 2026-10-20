package com.realityforge.scanner.agents.a032

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.features.FeatureExtractor
import com.realityforge.scanner.engine.features.NativeFeatureCallEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual Android/OpenCV process probe. Default input is explicitly synthetic, not a phone capture.
 * Arguments: a032_iterations=1..20 (default2), a032_rss_delta_budget_bytes (default96MiB),
 * a032_photo_path (optional authorized readable photograph), a032_source_sha (receipt binding).
 * VmRSS and malloc-accounted native heap are sampled every2ms, not claimed exact instantaneous peaks.
 */
class FeatureMemoryDeviceTest {
    @Test fun repeatedExtractionAndNativeCancellationRetainMeasuredEvidence() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val iterations=arguments.getString("a032_iterations")?.toIntOrNull()?:2
        val budget=arguments.getString("a032_rss_delta_budget_bytes")?.toLongOrNull()?:96L*1024*1024
        require(iterations in 1..20 && budget in 8L*1024*1024..1024L*1024*1024)
        val supplied=arguments.getString("a032_photo_path")
        val source=if(supplied!=null)File(supplied) else File.createTempFile("a032-benchmark-",".jpg",instrumentation.targetContext.cacheDir).also{file->
            instrumentation.context.assets.open("relief/view-01.jpg").use{input->file.outputStream().use{input.copyTo(it)}}
        }
        val report=JSONObject().put("schema",1).put("syntheticFixture",supplied==null)
            .put("physicalDeviceClaim",false).put("sourceSha",arguments.getString("a032_source_sha")?:JSONObject.NULL)
            .put("androidApi",android.os.Build.VERSION.SDK_INT).put("abis",JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("iterations",iterations).put("rssDeltaBudgetBytes",budget)
            .put("scope","single-image SIFT extraction after warmup; sampled process VmRSS and malloc native heap; no reconstruction/device-profile qualification")
        val runs=JSONArray();val failures=JSONArray();report.put("runs",runs).put("failures",failures)
        val sampling=AtomicBoolean(false);val samples=AtomicInteger();val rssPeak=AtomicLong();val heapPeak=AtomicLong()
        var sampler:Thread?=null
        val started=System.nanoTime();val cpuStarted=android.os.Process.getElapsedCpuTime()
        try {
            require(source.isFile)
            val digest=MessageDigest.getInstance("SHA-256")
            source.inputStream().use{input->val bytes=ByteArray(8192);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it)};report.put("originalSha256",hash)
            val extractor=FeatureExtractor()
            val projection={w:Int,h:Int->LensProjection.estimated(w,h,ImageLoader.focalPixels(w,h,35.0))}
            fun extract()=extractor.extract("benchmark",hash,source,"upright-bitmap-1600-v2",projection)
            extract().use{report.put("warmupFeatures",it.count).put("decodedWidth",it.width).put("decodedHeight",it.height)}
            val rssBefore=rssBytes();val heapBefore=Debug.getNativeHeapAllocatedSize()
            report.put("rssBeforeBytes",rssBefore?:JSONObject.NULL).put("nativeHeapBeforeBytes",heapBefore)
            rssPeak.set(rssBefore?:0);heapPeak.set(heapBefore);sampling.set(true)
            sampler=Thread({
                try {
                    while(sampling.get()){
                        rssBytes()?.let{rss->rssPeak.updateAndGet{maxOf(it,rss)}}
                        val heap=Debug.getNativeHeapAllocatedSize();heapPeak.updateAndGet{maxOf(it,heap)}
                        samples.incrementAndGet();Thread.sleep(2)
                    }
                }catch(_:InterruptedException){/* expected sampler shutdown */}
            },"a032-memory-sampler").apply{isDaemon=true;start()}
            repeat(iterations){index->
                val run=JSONObject().put("index",index).put("kind","completed")
                val begin=System.nanoTime()
                try {extract().use{frame->run.put("features",frame.count).put("descriptorBytes",frame.descriptorBytes)}}
                catch(failure:Throwable){run.put("failure",failure.javaClass.name);throw failure}
                finally{run.put("elapsedMs",(System.nanoTime()-begin)/1e6);runs.put(run)}
            }
            val inNative=AtomicBoolean(false);val cancel=AtomicBoolean(false)
            val nativeStarted=CountDownLatch(1);val nativeStart=AtomicLong();val nativeEnd=AtomicLong();val requested=AtomicLong()
            val requester=Thread({
                if(nativeStarted.await(10,TimeUnit.SECONDS)){
                    Thread.sleep(1)
                    if(inNative.get()){requested.set(System.nanoTime());cancel.set(true)}
                }
            },"a032-cancel-request").apply{isDaemon=true;start()}
            val cancellation=JSONObject().put("kind","cancel-after-native-entry")
            var cancelled=false
            try {
                extractor.extractObserved("benchmark",hash,source,"upright-bitmap-1600-v2",projection,{cancel.get()}){event->
                    when(event){
                        NativeFeatureCallEvent.STARTED->{nativeStart.set(System.nanoTime());inNative.set(true);nativeStarted.countDown()}
                        NativeFeatureCallEvent.FINISHED->{nativeEnd.set(System.nanoTime());inNative.set(false)}
                    }
                }.close()
            }catch(_:CancellationException){cancelled=true}
            finally {
                val returned=System.nanoTime();requester.join(2000)
                cancellation.put("cancelled",cancelled).put("nativeIntervalMs",(nativeEnd.get()-nativeStart.get())/1e6)
                    .put("requestWithinInstrumentedNativeInterval",requested.get() in nativeStart.get()..nativeEnd.get() && requested.get()>0)
                    .put("observedCancellationLatencyMs",if(requested.get()>0)(returned-requested.get())/1e6 else JSONObject.NULL)
                    .put("nativeCallInterruptible",false)
                runs.put(cancellation)
            }
            assertTrue("Cancellation must be requested during the measured native interval",requested.get()>0)
            assertTrue("Cancellation returns only after native completion and cleanup",cancelled)
            assertTrue(nativeEnd.get()>=requested.get())
            extract().use{assertTrue(it.count>0)} // cancelled calls leave the extractor reusable
            val rssAfter=rssBytes()
            report.put("rssAfterBytes",rssAfter?:JSONObject.NULL).put("nativeHeapAfterBytes",Debug.getNativeHeapAllocatedSize())
            report.put("sampledPeakRssBytes",if(rssPeak.get()>0)rssPeak.get() else JSONObject.NULL)
                .put("sampledPeakNativeHeapBytes",heapPeak.get()).put("samples",samples.get())
            assertNotNull("Process RSS counter unavailable; no resource-readiness claim",rssBefore)
            assertTrue("Sampled RSS delta exceeded the explicitly configured probe budget",rssPeak.get()-requireNotNull(rssBefore)<=budget)
        }catch(failure:Throwable){failures.put(JSONObject().put("type",failure.javaClass.name).put("message",failure.message?:""));throw failure}
        finally {
            sampling.set(false);sampler?.interrupt();sampler?.join(2000)
            report.put("wallMs",(System.nanoTime()-started)/1e6).put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpuStarted)
            val output=File.createTempFile("a032-memory-evidence-",".json",instrumentation.targetContext.cacheDir)
            output.writeText(report.toString(2));println("A032_MEMORY_EVIDENCE ${output.name} ${report}")
            if(supplied==null)source.delete()
        }
    }
    private fun rssBytes():Long?=runCatching{
        File("/proc/self/status").useLines{lines->lines.firstOrNull{it.startsWith("VmRSS:")}?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)}
    }.getOrNull()
}
