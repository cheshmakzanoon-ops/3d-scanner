package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

internal class CoordinatorJniFixture(val count:Int=30,val trackCount:Int=48,val cameraStep:Double=.03) {
    val photoIds=List(count) { "photo-%03d".format(it) }
    val lens=LensProjection(500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002)
    val xyz=List(trackCount) { k -> doubleArrayOf((k%8-3.5)*.12,(k/8-2.5)*.10,3.0+(k%7)*.22) }
    val views=List(count) { camera ->
        val pixels=FloatArray(trackCount*2)
        for(k in 0 until trackCount) {
            val p=xyz[k];val ray=lens.distort((p[0]-camera*cameraStep)/p[2],p[1]/p[2])
            pixels[k*2]=(ray.first*lens.fx+lens.cx).toFloat()
            pixels[k*2+1]=(ray.second*lens.fy+lens.cy).toFloat()
        }
        SparseView(640,480,500.0,pixels,IntArray(trackCount){0x825a3c},
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(-camera*cameraStep,0.0,0.0),lens)
    }
    val tracks=List(trackCount) { key ->
        val obs=photoIds.map { SourceObservation(it,key) }.sorted()
        StableTrack(obs.minOf { it.identity() },obs)
    }
    val snapshot=TrackSnapshot(tracks)
    val points=tracks.withIndex().associate { (key,t) -> t.id to BoundedSparseCoordinator.Candidate(t.id,xyz[key][0],xyz[key][1],xyz[key][2]) }
    fun plan(budget:BoundedSparseCoordinator.Budget=BoundedSparseCoordinator.Budget()) =
        BoundedSparseCoordinator.prepare(photoIds,views,snapshot,budget)
    fun candidates(window:BoundedSparseCoordinator.Window)=window.normalizedRows.toList().chunked(7)
        .map { it[0].toInt() }.distinct().map { points.getValue(window.globalTrackIds[it]) }
}

/** Synthetic geometry through actual JNI, not a physical capture quality claim. */
class BoundedSparseCoordinatorDeviceTest {
    private fun triangulate(window:BoundedSparseCoordinator.Window,token:Long):List<BoundedSparseCoordinator.Candidate> {
        val result=JSONObject(NativeCore.reconstructMultiviewCancellable(window.cameraRows,window.normalizedRows,.003,0,token))
        assertEquals(0,result.getInt("iterations"))
        val cameras=result.getJSONArray("cameras");assertEquals(window.photoIds.size,cameras.length())
        for(i in 0 until cameras.length()) {
            val c=cameras.getJSONObject(i)
            for(k in 0..8)assertEquals(window.cameraRows[i*12+k],c.getJSONArray("rotation").getDouble(k),1e-10)
            for(k in 0..2)assertEquals(window.cameraRows[i*12+9+k],c.getJSONArray("translation").getDouble(k),1e-10)
        }
        val points=result.getJSONArray("points")
        return List(points.length()) { i ->
            val point=points.getJSONArray(i);val index=point.getDouble(8)
            require(index.isFinite() && index%1.0==0.0 && index>=0 && index<window.globalTrackIds.size)
            BoundedSparseCoordinator.Candidate(window.globalTrackIds[index.toInt()],point.getDouble(0),point.getDouble(1),point.getDouble(2))
        }
    }
    @Test fun thirtyCameraWindowsFeedActualGlobalTypedBundleWithExactProvenance() {
        val f=CoordinatorJniFixture();val plan=f.plan();val token=NativeCore.beginReconstruction()
        try {
            val receipt=plan.execute { triangulate(it,token) }
            assertEquals(48,receipt.seeds.size);assertEquals(1440,receipt.acceptedObservations)
            assertEquals(3,receipt.windowsExecuted);assertEquals(0,receipt.rejectedObservations)
            val input=plan.bundleInput(receipt,"c".repeat(64))
            val result=JSONObject(NativeCore.bundleAdjustProblemCancellable(input.cameraRows,input.seedRows,input.pixelRows,
                input.intrinsicRows,input.groupIds,input.photoIds,input.sourceTrackIds,input.measured,false,.003,3,token))
            TypedBundleAdapter.validateResult(input,result)
            assertEquals(30,result.getInt("registeredCameras"));assertEquals(48,result.getInt("pointCount"))
            assertEquals("pose_and_baseline",result.getString("gaugeMode"))
            assertTrue(result.getDouble("robustCostAfter")<=result.getDouble("robustCostBefore")+1e-12)
            var maxError=0.0
            val points=result.getJSONArray("points")
            for(i in 0 until points.length()) {
                val point=points.getJSONArray(i);val source=input.sourceTrackIds[point.getInt(8)]
                val truth=f.points.getValue(source)
                val error=sqrt((point.getDouble(0)-truth.x).let { it*it }+
                    (point.getDouble(1)-truth.y).let { it*it }+(point.getDouble(2)-truth.z).let { it*it })
                maxError=maxOf(maxError,error)
            }
            assertTrue("Synthetic maximum XYZ error $maxError",maxError<.0001)
            println("COORDINATOR_JNI cameras=30 tracks=48 observations=1440 windows=3 packed=1632 maxXYZError=$maxError costBefore=${result.getDouble("robustCostBefore")} costAfter=${result.getDouble("robustCostAfter")}")
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }

    @Test fun connectedThirtyCameraLimitedOverlapChainUsesAllOriginalObservations() {
        val f=CoordinatorJniFixture(30,112,cameraStep=.05)
        // Four tracks per consecutive camera triple. No track spans >3 cameras;
        // distant cameras have no common observations, unlike the all-view fixture.
        val tracks=f.tracks.mapIndexed { key,t -> val start=key/4
            val obs=t.observations.filter { it.photo in f.photoIds.subList(start,start+3) }
            StableTrack(obs.minOf { it.identity() },obs) }
        val truth=tracks.withIndex().associate { (key,t) -> t.id to f.xyz[key] }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks))
        val token=NativeCore.beginReconstruction()
        try {
            val receipt=plan.execute { triangulate(it,token) }
            assertEquals(28,receipt.windowsExecuted);assertEquals(112,receipt.seeds.size)
            assertEquals(336,receipt.inputObservations);assertEquals(336,receipt.acceptedObservations)
            assertEquals(336,receipt.packedObservations);assertTrue(receipt.unsupportedPhotoIds.isEmpty())
            val input=plan.bundleInput(receipt)
            val result=JSONObject(NativeCore.bundleAdjustProblemCancellable(input.cameraRows,input.seedRows,input.pixelRows,
                input.intrinsicRows,input.groupIds,input.photoIds,input.sourceTrackIds,input.measured,false,.003,3,token))
            TypedBundleAdapter.validateResult(input,result)
            assertEquals(30,result.getInt("registeredCameras"));assertEquals(112,result.getInt("pointCount"))
            var maxError=0.0
            val points=result.getJSONArray("points")
            for(i in 0 until points.length()) {
                val point=points.getJSONArray(i);val expected=truth.getValue(input.sourceTrackIds[point.getInt(8)])
                maxError=maxOf(maxError,sqrt((0..2).sumOf { k -> (point.getDouble(k)-expected[k]).let { it*it } }))
            }
            assertTrue("Limited-overlap synthetic XYZ error $maxError",maxError<.001)
            println("COORDINATOR_CHAIN_JNI cameras=30 tracks=112 observations=336 windows=28 maxTrackViews=3 maxXYZError=$maxError costBefore=${result.getDouble("robustCostBefore")} costAfter=${result.getDouble("robustCostAfter")}")
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }

    @Test fun lowParallaxChainReportsNativeMissingTracksWithoutInventingFullSupport() {
        val f=CoordinatorJniFixture(30,112,cameraStep=.03)
        val tracks=f.tracks.mapIndexed { key,t -> val start=key/4
            val obs=t.observations.filter { it.photo in f.photoIds.subList(start,start+3) }
            StableTrack(obs.minOf { it.identity() },obs) }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks))
        val token=NativeCore.beginReconstruction()
        try {
            val receipt=plan.execute { triangulate(it,token) }
            // Native 0.8-degree parallax admission rejects20 tracks in this fixed fixture.
            assertEquals(92,receipt.seeds.size);assertEquals(20,receipt.missingTrackIds.size)
            assertEquals(336,receipt.inputObservations);assertEquals(276,receipt.acceptedObservations)
            assertEquals(60,receipt.rejectedObservations);assertTrue(receipt.invalidTrackIds.isEmpty())
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun cancellationBetweenWindowsPropagatesWithoutPublishingPartialGeometry() {
        val f=CoordinatorJniFixture();val token=NativeCore.beginReconstruction();var calls=0
        try {
            try {
                f.plan().execute { window ->
                    if(++calls==2)assertTrue(NativeCore.cancelReconstruction(token))
                    triangulate(window,token)
                }
                fail("Cancelled finalization published a receipt")
            } catch(expected:CancellationException) { assertEquals(2,calls) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
}
