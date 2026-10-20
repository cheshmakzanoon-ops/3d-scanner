package com.realityforge.scanner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiViewTracksTest {
    private fun frame(tx: Double, keyCount: Int = 3): SparseView {
        val f = 1000.0
        val depth = 5.0
        val xy = FloatArray(keyCount * 2)
        for (i in 0 until keyCount) {
            val X = i * 0.2 - 0.2
            xy[2 * i] = ((X + tx) / depth * f + 499.5).toFloat()
            xy[2 * i+1] = (499.5 + (i % 2) * 20).toFloat()
        }
        return SparseView(1000,1000,f,xy,IntArray(keyCount){0xffaaccee.toInt()},
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
            doubleArrayOf(tx,0.0,0.0))
    }
    @Test fun sharedThreeViewTrackIsPacked() {
        val graph=MultiViewTrackGraph(listOf(frame(0.0),frame(-1.0),frame(-1.7)))
        assertTrue(graph.add(0,0,1,0))
        assertTrue(graph.add(1,0,2,0))
        assertEquals(1,graph.matchedTrackCount())
        assertEquals(21,graph.packedRows().size)
        assertEquals(2,graph.acceptedEdges)
    }
    @Test fun conflictingFeaturesFromSameCameraAreNeverMerged() {
        val graph=MultiViewTrackGraph(listOf(frame(0.0),frame(-1.0),frame(-1.7)))
        assertTrue(graph.add(0,0,1,0))
        assertTrue(graph.add(1,0,2,0))
        assertFalse(graph.add(0,2,2,0))
        assertEquals(1,graph.matchedTrackCount())
    }
    @Test fun epipolarOutliersAreRejected() {
        val bad=frame(-1.0)
        val xy=bad.xy.copyOf();xy[1]+=150
        val other=bad.copy(xy=xy)
        val graph=MultiViewTrackGraph(listOf(frame(0.0),other))
        assertFalse(graph.add(0,0,1,0))
        assertEquals(0,graph.matchedTrackCount())
    }

    @Test fun insertionOrderAndCameraPermutationPreserveSourceIdentities() {
        val views=listOf(frame(0.0),frame(-1.0),frame(-1.7))
        val a=MultiViewTrackGraph(views);a.add(0,0,1,0);a.add(1,0,2,0)
        val b=MultiViewTrackGraph(listOf(views[2],views[0],views[1]));b.add(2,0,0,0);b.add(1,0,2,0)
        assertEquals(a.stableSnapshot(listOf("a","b","c")),b.stableSnapshot(listOf("c","a","b")))
    }
    @Test fun twoHundredFiftyViewsArePackedInCompleteBoundedTracks() {
        val g=MultiViewTrackGraph(List(250){frame(-it*.02)})
        for(i in 0 until 250 step 2)assertTrue(g.add(i,0,i+1,0))
        val batches=g.nativeBatches(List(250){"photo-%03d".format(it)},maxCameras=12,maxObservations=12)
        assertEquals(125,batches.sumOf {it.trackIds.size})
        assertTrue(batches.all {it.photoIds.size<=12 && it.rows.size<=84})
        assertEquals(125,batches.flatMap {it.trackIds}.distinct().size)
    }
    @Test fun rejectsNonRigidPoseAndNonFiniteFeatures() {
        assertThrows(IllegalArgumentException::class.java){frame(0.0).copy(rotation=DoubleArray(9){1.0})}
        assertThrows(IllegalArgumentException::class.java){frame(0.0).copy(xy=floatArrayOf(Float.NaN,0f,0f,0f,0f,0f))}
    }
    @Test fun zeroNativeBudgetCannotProduceFakeEmptySuccess(){val graph=MultiViewTrackGraph(listOf(frame(0.0),frame(-1.0)));graph.add(0,0,1,0)
        assertThrows(IllegalArgumentException::class.java){graph.nativeBatches(listOf("a","b"),maxObservations=0)}
    }
    @Test fun graphOwnsValidatedPoseAndFeatureInputs(){val a=frame(0.0);val b=frame(-1.0);val inputs=mutableListOf(a,b);val graph=MultiViewTrackGraph(inputs)
        a.xy[0]=Float.NaN;a.rotation[0]=100.0;b.translation[0]=Double.NaN;inputs.clear();assertTrue(graph.add(0,0,1,0));assertEquals(1,graph.matchedTrackCount())
    }
    @Test fun nativeSnapshotPreservesLegacyRowOrderAndOriginalCameras(){
        val graph=MultiViewTrackGraph(listOf(frame(0.0),frame(-1.0),frame(-1.7)));graph.add(0,2,1,2);graph.add(0,0,2,0)
        val photos=mutableListOf("z-source","a-source","m-source");val groups=graph.snapshot();val batch=graph.nativeSnapshot(photos)
        val expected=groups.map {group->group.entries.minOf {com.realityforge.scanner.engine.tracks.SourceObservation(photos[it.key],it.value).identity()}}
        assertEquals(expected,batch.trackIds);assertEquals(photos,batch.photoIds);assertArrayEquals(graph.packedRows(),batch.rows,0.0)
        photos.reverse();assertEquals(listOf("z-source","a-source","m-source"),batch.photoIds)
        assertEquals(0.0,batch.rows[0],0.0);assertEquals(1.0,batch.rows[14],0.0)
    }
}
