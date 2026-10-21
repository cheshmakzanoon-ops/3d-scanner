package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.NativeTrackBatch
import com.realityforge.scanner.engine.tracks.SourceObservation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Actual production JNI + native solver with SYNTHETIC distorted photographic
 * observations. Also runnable on host JDK with the actual JNI library loaded.
 * This does not establish physical camera or handset quality. */
class TypedBundleAdapterDeviceTest {
    internal class Fixture(val count: Int=5, val tracks: Int=48) {
        val photoIds=List(count) { "photo-${count-it}" }
        val lens=LensProjection(500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002)
        val xyz=List(tracks) { k -> doubleArrayOf((k%8-3.5)*.17,(k/8-2.5)*.13,3.0+(k%7)*.22) }
        val views=List(count) { camera ->
            val pixels=FloatArray(tracks*2)
            for(k in 0 until tracks) {
                val p=xyz[k];val distorted=lens.distort((p[0]-camera*.12)/p[2],p[1]/p[2])
                pixels[k*2]=(distorted.first*lens.fx+lens.cx).toFloat()
                pixels[k*2+1]=(distorted.second*lens.fy+lens.cy).toFloat()
            }
            SparseView(640,480,500.0,pixels,IntArray(tracks){0x825a3c},
                doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(-camera*.12,0.0,0.0),lens)
        }
        val groups=List(tracks) { key -> (0 until count).associateWith { key } }
        val ids=groups.map { group -> group.entries.minOf { SourceObservation(photoIds[it.key],it.value).identity() } }
        val rows=groups.flatMapIndexed { track, group -> group.map { (camera,key) ->
            val uv=views[camera].normalized(key)
            listOf(track.toDouble(),camera.toDouble(),uv.first,uv.second,130.0,90.0,60.0)
        }.flatten() }.toDoubleArray()
        val batch=NativeTrackBatch(photoIds,ids,rows)
        val cameras=views.flatMap { it.rotation.toList()+it.translation.toList() }.toDoubleArray()
        val seeds=xyz.flatMapIndexed { index,p -> listOf(index.toDouble(),p[0],p[1],p[2],130.0,90.0,60.0) }.toDoubleArray()
        fun input(measured:Boolean=true)=TypedBundleAdapter.prepare(views,batch,groups,cameras,seeds,if(measured)"c".repeat(64) else null)
    }

    private fun solve(input: TypedBundleAdapter.Input, token: Long): JSONObject = JSONObject(
        NativeCore.bundleAdjustProblemCancellable(input.cameraRows,input.seedRows,input.pixelRows,
            input.intrinsicRows,input.groupIds,input.photoIds,input.sourceTrackIds,input.measured,
            false,.003,3,token))
    @Test fun exactProductionAdapterCrossesRealJniWithPoseAndBaselineGauge() {
        val input=Fixture().input();val token=NativeCore.beginReconstruction()
        try {
            val result=solve(input,token)
            TypedBundleAdapter.validateResult(input,result)
            assertTrue(result.getInt("pointCount")>=40)
            assertEquals("pose_and_baseline",result.getString("gaugeMode"))
            assertEquals(1,result.getInt("anchorCamerasFixed"))
            assertEquals(0,result.getInt("acceptedIntrinsicGroups"))
            assertTrue(result.getDouble("robustCostAfter")<=result.getDouble("robustCostBefore")+1e-12)
            assertTrue(result.getDouble("medianErrorAfterNormalized")<.00001)
            // No required positive loop count: eligibility is determined by real support.
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun returnedProvenanceAndFixedModelsAreValidatedNotFabricated() {
        val input=Fixture().input();val token=NativeCore.beginReconstruction()
        try {
            val accepted=solve(input,token)
            TypedBundleAdapter.validateResult(input,accepted)
            fun reject(mutator:(JSONObject)->Unit) {
                val changed=JSONObject(accepted.toString());mutator(changed)
                try { TypedBundleAdapter.validateResult(input,changed);fail("Changed native receipt was accepted") }
                catch(expected:IllegalArgumentException) { assertNotNull(expected.message) }
            }
            reject { it.getJSONArray("stableTrackMapping").getJSONArray(0).put(1,"f".repeat(64)) }
            reject { it.getJSONArray("cameraIntrinsics").getJSONArray(0).put(2,501.0) }
            reject { it.getJSONArray("cameras").getJSONObject(0).getJSONArray("translation").put(0,.1) }
            reject { it.getJSONArray("cameras").getJSONObject(1).getJSONArray("translation").put(0,-.5) }
            reject { it.put("gaugeMode","two_anchors") }
            reject { it.getJSONArray("points").getJSONArray(0).put(0,"NaN") }
            reject { it.getJSONArray("points").getJSONArray(0).put(3,256) }
            reject { it.getJSONArray("points").getJSONArray(0).put(6,-1.0) }
            reject { it.getJSONArray("points").getJSONArray(0).put(7,181.0) }
            reject { it.put("inputObservations",1) }
            reject { it.put("usableObservations",0) }
            reject { it.put("rejectedObservations",-1) }
            reject { it.put("robustCostAfter","Infinity") }
            reject { it.getJSONArray("intrinsicGroups").getJSONObject(0).put("id","invented-lens") }
            reject { it.getJSONArray("cameras").getJSONObject(2).getJSONArray("rotation").put(0,2.0) }
            reject { it.getJSONArray("trackEvidence").getJSONObject(0).getJSONArray("supportingCameras").put(1,99) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun sparseGlobalTrackIndicesRemainExactAcrossActualJni() {
        val f=Fixture(3)
        val input=TypedBundleAdapter.prepare(f.views,f.batch,f.groups,f.cameras,
            f.seeds.copyOfRange(24*7,f.seeds.size),null)
        val token=NativeCore.beginReconstruction()
        try {
            val result=solve(input,token)
            TypedBundleAdapter.validateResult(input,result)
            assertEquals(24,result.getInt("pointCount"))
            assertEquals(0,result.getInt("loopProposalCount"))
            val points=result.getJSONArray("points")
            for(i in 0 until points.length())assertTrue(points.getJSONArray(i).getInt(8) in 24..47)
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
    @Test fun realTypedCallHonorsOperationCancellation() {
        val input=Fixture().input(false);val token=NativeCore.beginReconstruction()
        try {
            assertTrue(NativeCore.cancelReconstruction(token))
            try { solve(input,token);fail("Cancelled operation returned geometry") }
            catch(expected:CancellationException) { assertNotNull(expected.message) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
}
