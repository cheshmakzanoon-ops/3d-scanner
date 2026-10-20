package com.realityforge.scanner.agents.a035

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.features.FeatureExtractor
import com.realityforge.scanner.engine.features.FeatureFrame
import com.realityforge.scanner.engine.matching.FeatureMatcher
import com.realityforge.scanner.engine.matching.PairCandidate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.MatOfDMatch
import org.opencv.features2d.BFMatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runnable exact-reference/resource evidence. Bundled input is synthetic, never a phone claim.
 * Optional a035_pairs_manifest: schema1 JSON, at most32 pairs, each with firstPath/secondPath,
 * firstSha256/secondSha256, label and optional firstFocalPixels/secondFocalPixels.
 * A caller supplies authorized device-readable originals; this test never deletes those inputs.
 */
class MatchingEvidenceDeviceTest {
    private data class Input(val first:File,val second:File,val firstHash:String,val secondHash:String,
                             val label:String,val firstFocal:Double?,val secondFocal:Double?)

    @Test fun exactReferenceRecallScopedGeometryAndSampledWorkingSet() {
        assertTrue(OpenCVLoader.initLocal())
        val instrument=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val iterations=arguments.getString("a035_iterations")?.let{requireNotNull(it.toIntOrNull())}?:2
        val budget=arguments.getString("a035_rss_delta_budget_bytes")?.let{requireNotNull(it.toLongOrNull())}?:96L*1024*1024
        require(iterations in 1..10 && budget in 8L*1024*1024..1024L*1024*1024)
        val manifest=arguments.getString("a035_pairs_manifest")
        val temporary=ArrayList<File>()
        val report=JSONObject().put("schema",1).put("syntheticFixture",manifest==null)
            .put("physicalDeviceClaim",false).put("sourceSha",arguments.getString("a035_source_sha")?:JSONObject.NULL)
            .put("androidApi",android.os.Build.VERSION.SDK_INT).put("abis",JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("buildFingerprint",android.os.Build.FINGERPRINT)
            .put("scope","exact reciprocal BF reference versus blocked exact BF; sampled process RSS/native heap, not device maxima")
            .put("geometricThresholdPixels",1.5).put("ratio",.67).put("iterations",iterations)
            .put("groundTruthFalsePositiveRate",JSONObject.NULL).put("groundTruthRecall",JSONObject.NULL)
        val rows=JSONArray();val failures=JSONArray();report.put("pairs",rows).put("failures",failures)
        val started=System.nanoTime()
        try {
            val inputs=if(manifest==null){
                val files=(1..2).map{i->File.createTempFile("a035-input-",".jpg",instrument.targetContext.cacheDir).also{file->
                    temporary.add(file);instrument.context.assets.open("relief/view-0$i.jpg").use{input->file.outputStream().use{input.copyTo(it)}}
                }}
                listOf(Input(files[0],files[1],hash(files[0]),hash(files[1]),"synthetic-relief-stereo",760.0,760.0))
            }else readManifest(File(manifest)).let{(inputs,sha)->report.put("manifestSha256",sha);inputs}
            for((pairIndex,input) in inputs.withIndex()){
                val row=JSONObject().put("index",pairIndex).put("label",input.label)
                    .put("firstSha256",input.firstHash).put("secondSha256",input.secondHash)
                rows.put(row)
                try {
                    require(hash(input.first)==input.firstHash && hash(input.second)==input.secondHash){"Authorized input content changed"}
                    val extractor=FeatureExtractor()
                    val firstProjection={w:Int,h:Int->LensProjection.estimated(w,h,input.firstFocal?:ImageLoader.focalPixels(w,h,35.0))}
                    val secondProjection={w:Int,h:Int->LensProjection.estimated(w,h,input.secondFocal?:ImageLoader.focalPixels(w,h,35.0))}
                    extractor.extract("a",input.firstHash,input.first,"a035-upright-1600-v2",firstProjection).use{first->
                        extractor.extract("b",input.secondHash,input.second,"a035-upright-1600-v2",secondProjection).use{second->
                            val matcher=FeatureMatcher(blockSize=128)
                            val candidate=PairCandidate("a","b",0.0,"benchmark-retrieval-only")
                            matcher.match(first,second,candidate) // warm native matcher before measuring working-set delta
                            row.put("firstFeatures",first.count).put("secondFeatures",second.count)
                                .put("retainedDescriptorBytes",first.descriptorBytes+second.descriptorBytes)
                                .put("firstIntrinsics",JSONArray(listOf(first.projection.fx,first.projection.fy,first.projection.cx,first.projection.cy)))
                                .put("secondIntrinsics",JSONArray(listOf(second.projection.fx,second.projection.fy,second.projection.cx,second.projection.cy)))
                            val rssBefore=rssBytes();val rssPeak=AtomicLong(rssBefore?:0)
                            val heapPeak=AtomicLong(Debug.getNativeHeapAllocatedSize());val sampling=AtomicBoolean(true)
                            val sampler=Thread({try{while(sampling.get()){
                                rssBytes()?.let{n->rssPeak.updateAndGet{maxOf(it,n)}}
                                val n=Debug.getNativeHeapAllocatedSize();heapPeak.updateAndGet{maxOf(it,n)};Thread.sleep(2)
                            }}catch(_:InterruptedException){ }},"a035-memory-sampler").apply{isDaemon=true;start()}
                            try {
                                val referenceStart=System.nanoTime();val reference=reference(first,second)
                                val referenceSet=reference.toSet()
                                row.put("referenceMs",(System.nanoTime()-referenceStart)/1e6).put("referenceMatches",reference.size)
                                val runs=JSONArray();row.put("runs",runs)
                                repeat(iterations){iteration->
                                    val begin=System.nanoTime();val actual=matcher.match(first,second,candidate)
                                    val keys=actual.matches.map{it.firstKey to it.secondKey}
                                    val agreement=if(reference.isEmpty())JSONObject.NULL else keys.count{it in referenceSet}.toDouble()/reference.size
                                    runs.put(JSONObject().put("iteration",iteration).put("elapsedMs",(System.nanoTime()-begin)/1e6)
                                        .put("matches",actual.count).put("exactReferenceRecall",agreement))
                                    assertEquals("Exact blocked matcher changed reciprocal reference identities",reference,keys)
                                    if(iteration==0){
                                        val focal=minOf(first.projection.fx,first.projection.fy,second.projection.fx,second.projection.fy)
                                        require(focal>=30.0)
                                        val token=NativeCore.beginReconstruction();require(token>0)
                                        try {
                                            val result=JSONObject(NativeCore.reconstructCancellable(actual.packedRows(),1.5/focal,token))
                                            row.put("nativeAccepted",!result.has("error"))
                                            if(result.has("error"))row.put("nativeRejection",result.getString("error"))
                                            else {
                                                val points=result.getJSONArray("points")
                                                val indices=List(points.length()){i->val value=points.getJSONArray(i).getDouble(8)
                                                    require(value.isFinite() && value%1.0==0.0 && value>=0 && value<actual.count);value.toInt()}
                                                require(indices.distinct().size==indices.size && points.length()==result.getInt("pointCount"))
                                                row.put("nativeAcceptedPoints",points.length()).put("nativeAcceptedSourceIndices",JSONArray(indices))
                                                if(manifest==null)assertTrue("Synthetic calibrated baseline must initialize",points.length()>=20)
                                            }
                                            if(manifest==null)assertFalse("Synthetic calibrated baseline rejected",result.has("error"))
                                        }finally{assertTrue("Scoped native operation must close",NativeCore.finishReconstruction(token))}
                                    }
                                }
                                if(first.count>=2 && second.count>=2){
                                    var checks=0;val cancellationStart=System.nanoTime()
                                    assertThrows(CancellationException::class.java){matcher.match(first,second,candidate){++checks>=4}}
                                    row.put("cancellationMs",(System.nanoTime()-cancellationStart)/1e6)
                                        .put("cancellationBoundary","after completed native block; native kNN itself is not interrupted")
                                    assertEquals(reference,matcher.match(first,second,candidate).matches.map{it.firstKey to it.secondKey})
                                }else row.put("cancellationBoundary","not exercised: fewer than two descriptors")
                            }finally{
                                sampling.set(false);sampler.interrupt();sampler.join(2000)
                                row.put("rssBeforeBytes",rssBefore?:JSONObject.NULL).put("sampledPeakRssBytes",rssPeak.get())
                                    .put("sampledPeakNativeHeapBytes",heapPeak.get()).put("rssDeltaBudgetBytes",budget)
                            }
                            assertNotNull("RSS unavailable; no resource-readiness claim",rssBefore)
                            assertTrue("Sampled matcher/reference RSS delta exceeded explicit budget",rssPeak.get()-requireNotNull(rssBefore)<=budget)
                        }
                    }
                }catch(failure:Throwable){
                    val error=JSONObject().put("pairIndex",pairIndex).put("type",failure.javaClass.name).put("message",failure.message?:"")
                    row.put("failure",error);failures.put(error)
                }
            }
            assertEquals("Pair failures are retained in the evidence report",0,failures.length())
        }finally{
            report.put("wallMs",(System.nanoTime()-started)/1e6)
            val output=File.createTempFile("a035-matching-evidence-",".json",instrument.targetContext.cacheDir)
            output.writeText(report.toString(2));println("A035_MATCHING_EVIDENCE ${output.name} ${report}")
            temporary.forEach{it.delete()}
        }
    }

    private fun reference(first:FeatureFrame,second:FeatureFrame):List<Pair<Int,Int>> {
        if(first.count<2 || second.count<2)return emptyList()
        return first.withDescriptors{a->second.withDescriptors{b->
            var matcher:BFMatcher?=null
            val forward=ArrayList<MatOfDMatch>();val reverse=ArrayList<MatOfDMatch>()
            try {
                matcher=BFMatcher.create(Core.NORM_L2,false)
                matcher.knnMatch(a,b,forward,2);matcher.knnMatch(b,a,reverse,2)
                val backwards=reverse.mapNotNull{row->val p=row.toArray()
                    if(p.size>=2 && p[0].distance<.67*p[1].distance)p[0].queryIdx to p[0].trainIdx else null}.toMap()
                forward.mapNotNull{row->val p=row.toArray()
                    if(p.size>=2 && p[0].distance<.67*p[1].distance && backwards[p[0].trainIdx]==p[0].queryIdx)p[0].queryIdx to p[0].trainIdx else null}
            }finally{forward.forEach{it.release()};reverse.forEach{it.release()};matcher?.clear()}
        }}
    }
    private fun readManifest(file:File):Pair<List<Input>,String> {
        val bytes=ByteArrayOutputStream()
        file.inputStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break
            require(bytes.size()+n<=1024*1024){"Oversized matching manifest"};bytes.write(buffer,0,n)}}
        val data=bytes.toByteArray();val text=data.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(data)){"Malformed UTF-8 manifest"}
        val sha=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)}
        val manifest=JSONObject(text);require(manifest.getInt("schema")==1)
        val pairs=manifest.getJSONArray("pairs");require(pairs.length() in 1..32)
        return List(pairs.length()){index->val p=pairs.getJSONObject(index)
            val a=p.getString("firstSha256");val b=p.getString("secondSha256")
            require(a.matches(Regex("[0-9a-f]{64}")) && b.matches(Regex("[0-9a-f]{64}")))
            val label=p.getString("label");require(label.length in 1..256)
            fun focal(name:String)=if(p.has(name))p.getDouble(name).also{require(it.isFinite() && it in 30.0..10000.0)}else null
            Input(File(p.getString("firstPath")),File(p.getString("secondPath")),a,b,label,focal("firstFocalPixels"),focal("secondFocalPixels"))
        } to sha
    }
    private fun hash(file:File):String {
        require(file.isFile && file.length() in 1..150L*1024*1024)
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        file.inputStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break
            size+=n;require(size<=150L*1024*1024);digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun rssBytes():Long?=runCatching{File("/proc/self/status").useLines{lines->
        lines.firstOrNull{it.startsWith("VmRSS:")}?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
    }}.getOrNull()
}
