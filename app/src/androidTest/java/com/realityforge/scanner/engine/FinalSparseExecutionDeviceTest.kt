package com.realityforge.scanner.engine
import com.realityforge.scanner.engine.tracks.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

internal class FinalSparseJniFixture(val count:Int=12,val trackCount:Int=48) {
    val photoIds=List(count) { "photo-%03d".format(it) }
    val lens=LensProjection(500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002)
    val xyz=List(trackCount) { k -> doubleArrayOf(((k%48)%8-3.5)*.12,((k%48)/8-2.5)*.10,3.0+((k%48)%7)*.22) }
    val views=List(count) { camera ->
        val pixels=FloatArray(trackCount*2)
        for(k in 0 until trackCount) {
            val p=xyz[k];val ray=lens.distort((p[0]-camera*.03)/p[2],p[1]/p[2])
            pixels[k*2]=(ray.first*lens.fx+lens.cx).toFloat()
            pixels[k*2+1]=(ray.second*lens.fy+lens.cy).toFloat()
        }
        SparseView(640,480,500.0,pixels,IntArray(trackCount){0x825a3c},
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(-camera*.03,0.0,0.0),lens)
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

/** Actual admitted production executor and JNI on synthetic distorted observations. */
class FinalSparseExecutionDeviceTest {
    private fun nativeWindow(w:BoundedSparseCoordinator.Window,token:Long):List<BoundedSparseCoordinator.Candidate> {
        val result=JSONObject(NativeCore.reconstructMultiviewCancellable(w.cameraRows,w.normalizedRows,.003,0,token))
        require(result.getInt("iterations")==0)
        val points=result.getJSONArray("points")
        return List(points.length()) { i -> val p=points.getJSONArray(i)
            val track=p.getDouble(8);require(track%1.0==0.0 && track>=0 && track<w.globalTrackIds.size)
            BoundedSparseCoordinator.Candidate(w.globalTrackIds[track.toInt()],p.getDouble(0),p.getDouble(1),p.getDouble(2)) }
    }
    private fun nativeBundle(input:TypedBundleAdapter.Input,token:Long):JSONObject=JSONObject(
        NativeCore.bundleAdjustProblemCancellable(input.cameraRows,input.seedRows,input.pixelRows,input.intrinsicRows,
            input.groupIds,input.photoIds,input.sourceTrackIds,input.measured,false,.003,15,token))
    @Test fun admittedProductionExecutorPreservesSourcePixelsAndOriginalPoseOrderMapping() {
        val f=FinalSparseJniFixture();val order=listOf(3,8,11,1,7,2,10,4,0,9,6,5)
        val token=NativeCore.beginReconstruction()
        try {
            val result=FinalSparseExecution.execute(order.map { f.photoIds[it] },order.map { f.views[it] },f.snapshot,null,.003,40,
                triangulate={nativeWindow(it,token)},bundle={nativeBundle(it,token)})
            assertEquals(listOf(f.photoIds[3],f.photoIds[8]),result.input.photoIds.take(2))
            assertEquals(48,result.geometry.getInt("pointCount"));assertEquals(576,result.receipt.acceptedObservations)
            assertEquals(1,result.receipt.windowsExecuted)
            for(row in result.input.pixelRows.toList().chunked(7)) {
                val source=f.tracks.single { it.id==result.input.sourceTrackIds[row[0].toInt()] }
                val photo=result.input.photoIds[row[1].toInt()]
                val key=source.observations.single { it.photo==photo }.keypoint
                val v=f.views[f.photoIds.indexOf(photo)]
                assertEquals(v.xy[2*key].toDouble(),row[2],0.0);assertEquals(v.xy[2*key+1].toDouble(),row[3],0.0)
            }
            val points=result.geometry.getJSONArray("points");var maximum=0.0
            for(i in 0 until points.length()) { val p=points.getJSONArray(i);val truth=f.points.getValue(result.input.sourceTrackIds[p.getInt(8)])
                maximum=maxOf(maximum,sqrt((p.getDouble(0)-truth.x).let {it*it}+(p.getDouble(1)-truth.y).let {it*it}+(p.getDouble(2)-truth.z).let {it*it})) }
            assertTrue(maximum<.0001)
            println("FINAL_SPARSE_JNI cameras=12 tracks=48 observations=576 windows=1 maxXYZError=$maximum")
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun offsideAssemblyMapsActualPosesAndCheckpointsAndFaultLeavesAnchorUntouched() {
        val f=FinalSparseJniFixture();val order=listOf(3,8,11,1,7,2,10,4,0,9,6,5)
        val ids=order.map { f.photoIds[it] };val views=order.map { f.views[it] }
        val cameras=JSONArray(views.mapIndexed { index,v -> JSONObject().put("photoId",ids[index])
            .put("rotation",JSONArray(v.rotation.toList())).put("translation",JSONArray(v.translation.toList())) })
        val anchor=JSONObject().put("schema",1).put("pointCount",1).put("sentinel","verified_anchor")
            .put("points",JSONArray().put(JSONArray(listOf(0.0,0.0,3.0,130,90,60,0.0,2.0,123))))
        val anchorObservations=mapOf(123 to listOf(0 to 7,1 to 9))
        val originalJson=anchor.toString();val originalCameras=cameras.toString()
        val token=NativeCore.beginReconstruction()
        try {
            val result=FinalSparseExecution.execute(ids,views,f.snapshot,null,.003,24,
                triangulate={nativeWindow(it,token)},bundle={nativeBundle(it,token)})
            try {
                FinalSparsePublication.prepare(anchor,cameras,ids,views,f.snapshot,result,
                    beforeCommit={throw IllegalStateException("injected progress sink failure")})
                fail("Injected publication failure swallowed")
            } catch(expected:IllegalStateException) { assertEquals("injected progress sink failure",expected.message) }
            assertEquals(originalJson,anchor.toString());assertEquals(originalCameras,cameras.toString())
            assertEquals(mapOf(123 to listOf(0 to 7,1 to 9)),anchorObservations)
            assertFalse(anchor.has("sparseSourceTrackIds"))
            val staged=FinalSparsePublication.prepare(anchor,cameras,ids,views,f.snapshot,result)
            assertEquals(originalJson,anchor.toString());assertEquals(originalCameras,cameras.toString())
            assertEquals(48,staged.output.getInt("pointCount"));assertEquals(48,staged.observations.size)
            for(camera in ids.indices) {
                val actual=staged.cameras.getJSONObject(camera)
                assertEquals(ids[camera],actual.getString("photoId"))
                val native=result.geometry.getJSONArray("cameras").getJSONObject(result.input.photoIds.indexOf(ids[camera]))
                assertEquals(native.getJSONArray("rotation").toString(),actual.getJSONArray("rotation").toString())
                assertEquals(native.getJSONArray("translation").toString(),actual.getJSONArray("translation").toString())
            }
            val points=staged.output.getJSONArray("points")
            for(i in 0 until points.length()) {
                val point=points.getJSONArray(i);val track=point.getInt(8)
                val source=f.tracks.single { it.id==result.input.sourceTrackIds[track] }
                for(obs in staged.observations.getValue(track)) {
                    assertTrue(source.observations.contains(SourceObservation(ids[obs.camera],obs.key)))
                    val uv=views[obs.camera].normalized(obs.key)
                    assertEquals(uv.first,obs.nx,0.0);assertEquals(uv.second,obs.ny,0.0)
                    val c=staged.cameras.getJSONObject(obs.camera);val r=c.getJSONArray("rotation");val t=c.getJSONArray("translation")
                    val projected=DoubleArray(3) { axis -> (0..2).sumOf { k -> r.getDouble(axis*3+k)*point.getDouble(k) }+t.getDouble(axis) }
                    assertTrue(projected[2]>0)
                    assertEquals(obs.nx,projected[0]/projected[2],.00001);assertEquals(obs.ny,projected[1]/projected[2],.00001)
                }
            }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun realNativeModelMismatchCannotBePublishedAsOriginalCalibration() {
        val f=FinalSparseJniFixture();val token=NativeCore.beginReconstruction()
        try {
            try {
                FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
                    triangulate={nativeWindow(it,token)},bundle={ input ->
                        val wrong=input.intrinsicRows.copyOf();for(camera in input.photoIds.indices)wrong[camera*11+6]+=.01
                        nativeBundle(input.copy(intrinsicRows=wrong),token)
                    })
                fail("Changed native lens model was published")
            } catch(expected:IllegalArgumentException) { assertEquals("Fixed intrinsic changed",expected.message) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun originalDistortionMismatchRejectsOtherwiseCorrectCandidateSeeds() {
        val f=FinalSparseJniFixture();val wrong=f.views.map { it.copy(projection=f.lens.copy(fx=800.0,k1=.4)) }
        var bundled=false
        try {
            FinalSparseExecution.execute(f.photoIds,wrong,f.snapshot,null,.003,24,
                triangulate={f.candidates(it)},bundle={bundled=true;error("Should not reach native BA")})
            fail("Incorrect original lens accepted truthful XYZ")
        } catch(_:IllegalArgumentException) { assertFalse(bundled) }
    }
    @Test fun malformedGaugeAndNativeObservationArithmeticAreRejected() {
        val f=FinalSparseJniFixture();val token=NativeCore.beginReconstruction()
        try {
            for(gauge in listOf(true,false)) {
                try {
                    FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
                        triangulate={nativeWindow(it,token)},bundle={input -> nativeBundle(input,token).also {
                            if(gauge)it.put("gaugeMode","two_anchors") else it.put("usableObservations",0)
                        }})
                    fail("Malformed native receipt accepted")
                } catch(_:IllegalArgumentException) {}
            }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
}
