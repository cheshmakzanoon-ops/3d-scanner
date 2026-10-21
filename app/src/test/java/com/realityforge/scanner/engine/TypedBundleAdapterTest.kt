package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.NativeTrackBatch
import com.realityforge.scanner.engine.tracks.SourceObservation
import org.junit.Assert.*
import org.junit.Test

/** Synthetic packing/provenance tests; these do not claim native convergence. */
class TypedBundleAdapterTest {
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
    private fun rejected(body:()->Unit) {
        try { body();fail("Malformed provenance was accepted") } catch(expected:IllegalArgumentException) { assertNotNull(expected.message) }
    }
    @Test fun exactDistortedPixelsIntrinsicOrderAndStableIdsArePreserved() {
        val f=Fixture();val input=f.input()
        assertArrayEquals(f.photoIds.toTypedArray(),input.photoIds)
        assertArrayEquals(f.ids.toTypedArray(),input.sourceTrackIds)
        assertTrue(input.measured)
        assertEquals(1,input.groupIds.toSet().size)
        assertArrayEquals(doubleArrayOf(640.0,480.0,500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002),input.intrinsicRows.copyOfRange(0,11),0.0)
        for(base in input.pixelRows.indices step 7) {
            val track=input.pixelRows[base].toInt();val camera=input.pixelRows[base+1].toInt()
            assertEquals(f.views[camera].xy[track*2].toDouble(),input.pixelRows[base+2],0.0)
            assertEquals(f.views[camera].xy[track*2+1].toDouble(),input.pixelRows[base+3],0.0)
        }
        f.cameras[0]=99.0;f.seeds[0]=99.0
        assertEquals(1.0,input.cameraRows[0],0.0);assertEquals(0.0,input.seedRows[0],0.0)
    }
    @Test fun unknownEstimatedLensesNeverShareInventedCalibrationEvidence() {
        val f=Fixture();val input=f.input(false)
        assertFalse(input.measured);assertEquals(f.count,input.groupIds.toSet().size)
    }
    @Test fun sourceSnapshotPermutationAndFeatureRemappingAreRejected() {
        val f=Fixture()
        val swapped=f.ids.toMutableList().also { val first=it[0];it[0]=it[1];it[1]=first }
        rejected { TypedBundleAdapter.prepare(f.views,NativeTrackBatch(f.photoIds,swapped,f.rows),f.groups,f.cameras,f.seeds,null) }
        val corrupt=f.rows.copyOf().also { it[2]+=.0001 }
        rejected { TypedBundleAdapter.prepare(f.views,NativeTrackBatch(f.photoIds,f.ids,corrupt),f.groups,f.cameras,f.seeds,null) }
        val wrongPixels=f.groups.toMutableList().also { it[0]=it[0].toMutableMap().apply { put(0,1) } }
        rejected { TypedBundleAdapter.prepare(f.views,f.batch,wrongPixels,f.cameras,f.seeds,null) }
    }
    @Test fun seedFilteringRetainsGlobalTrackIndicesRatherThanRenumbering() {
        val f=Fixture();val subset=f.seeds.copyOfRange(24*7,f.seeds.size)
        val input=TypedBundleAdapter.prepare(f.views,f.batch,f.groups,f.cameras,subset,null)
        assertEquals(f.tracks,input.sourceTrackIds.size)
        assertEquals(24.0,input.seedRows[0],0.0)
        assertEquals(24.0,input.pixelRows[0],0.0)
        assertEquals(24*f.count*7,input.pixelRows.size)
        val duplicate=subset.copyOf().also { it[7]=it[0] }
        rejected { TypedBundleAdapter.prepare(f.views,f.batch,f.groups,f.cameras,duplicate,null) }
    }
    @Test fun productionCameraCapAndExactModelGroupsAreEnforced() {
        rejected { Fixture(13).input() }
        val ok=Fixture();val different=ok.views.toMutableList()
        different[1]=different[1].copy(width=641)
        val input=TypedBundleAdapter.prepare(different,ok.batch,ok.groups,ok.cameras,ok.seeds,"c".repeat(64))
        assertEquals(2,input.groupIds.toSet().size)
    }
}
