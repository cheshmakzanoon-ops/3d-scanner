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
}
