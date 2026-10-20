package com.realityforge.scanner.engine.dense
import org.junit.Assert.*
import org.junit.Test
class DenseViewSelectionTest {
    @Test fun evidenceAndPlansDefensivelySnapshotInputCollections(){
        val centre=mutableListOf(1.0,2.0,3.0);val tracks=mutableSetOf("a".repeat(64))
        val c=DenseViewSelection.Camera("photo",centre,tracks,.5);centre.clear();tracks.clear()
        assertEquals(3,c.centre.size);assertEquals(1,c.tracks.size)
        try{(c.tracks as MutableSet<String>).clear();fail("Mutable source IDs escaped")}catch(_:UnsupportedOperationException){}
    }
    @Test fun shuffledTracksProduceSameBoundedSelectionAndDisjointReason(){
        val c=(0..11).map{i->DenseViewSelection.Camera("photo-$i",listOf(i*.1,0.0,0.0),(0..39).map { "%064x".format(it+(i/4)*20) }.toSet(),.7)}
        val a=DenseViewSelection.select(c);assertEquals(a,DenseViewSelection.select(c.reversed()))
        assertEquals(8,a.referencePhotoIds.size);assertEquals(80,a.uniqueSparseTracks);assertTrue(a.sourcePhotoIds.values.all{it.size in 1..3})
        val d=DenseViewSelection.Camera("disjoint",listOf(10.0,0.0,0.0),setOf("%064x".format(1000),"%064x".format(1001)),.8)
        assertEquals("insufficient_verified_overlap_or_baseline",DenseViewSelection.select(c+d).exclusions[d.photoId])
    }
}
