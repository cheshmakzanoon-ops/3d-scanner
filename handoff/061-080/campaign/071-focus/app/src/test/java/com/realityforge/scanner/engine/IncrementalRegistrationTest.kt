package com.realityforge.scanner.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalRegistrationTest {
    private fun view(keys: Int = 4, tx: Double = 0.0): SparseView {
        val f=100.0
        val pixels=FloatArray(keys*2) { i ->
            if(i%2==0)(63.5+((i/2)*.1+tx)/5*f).toFloat() else 63.5f
        }
        return SparseView(128,128,f,pixels,IntArray(keys) { -1 },
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(tx,0.0,0.0))
    }
    @Test fun laterRegisteredViewsCanSupportANewCandidateWithoutOriginalAnchor() {
        val rows=IncrementalRegistration.candidateRows(listOf(doubleArrayOf(.2,0.0,5.0)),
            listOf(mapOf(3 to 0,4 to 1)),mapOf(3 to mapOf(0 to 2),4 to mapOf(1 to 2)),view())
        assertEquals(5,rows.size)
        assertEquals(.2,rows[0],0.0)
        assertEquals(.04,rows[3],1e-6)
    }
    @Test fun singleViewSupportAndConflictingCandidateKeysAreRejected() {
        val xyz=listOf(doubleArrayOf(.2,0.0,5.0));val group=listOf(mapOf(0 to 0,1 to 0))
        assertEquals(0,IncrementalRegistration.candidateRows(xyz,group,mapOf(0 to mapOf(0 to 2)),view()).size)
        assertEquals(0,IncrementalRegistration.candidateRows(xyz,group,
            mapOf(0 to mapOf(0 to 2),1 to mapOf(0 to 3)),view()).size)
    }
    @Test fun duplicateCandidateFeatureRejectsBothCompetingLandmarks() {
        val rows=IncrementalRegistration.candidateRows(
            listOf(doubleArrayOf(.2,0.0,5.0),doubleArrayOf(.4,0.0,7.0)),
            listOf(mapOf(0 to 0,1 to 0),mapOf(0 to 1,1 to 1)),
            mapOf(0 to mapOf(0 to 2,1 to 2),1 to mapOf(0 to 2,1 to 2)),view())
        assertEquals(0,rows.size)
    }
    @Test fun graphSnapshotKeepsTheNativeTrackIdAndDoesNotAliasMutableGraph() {
        val graph=MultiViewTrackGraph(listOf(view(),view(tx=-.5),view(tx=-1.0)))
        assertTrue(graph.add(0,2,1,2))
        assertTrue(graph.add(0,0,2,0))
        val snapshot=graph.snapshot()
        val packed=graph.packedRows()
        assertEquals(2,snapshot.size)
        assertEquals(0,snapshot[0][0])
        assertEquals(2,snapshot[1][0])
        assertEquals(0.0,packed[0],0.0)
        assertEquals(1.0,packed[14],0.0)
        @Suppress("UNCHECKED_CAST")
        (snapshot[0] as MutableMap<Int,Int>)[0]=3
        assertEquals(0,graph.snapshot()[0][0])
    }
    @Test fun reciprocalMatchContractRejectsDuplicateIdsAndNegativeIndices() {
        for (pair in listOf(
            intArrayOf(0,0) to intArrayOf(1,2),intArrayOf(0,1) to intArrayOf(2,2),
            intArrayOf(-1) to intArrayOf(0),intArrayOf(0) to intArrayOf())) {
            try { FeaturePairs(pair.first,pair.second);fail("Invalid matches accepted") }
            catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun disconnectedCandidateIsNotCountedAsRegistered() = runBlocking {
        val views=listOf(view(),view(tx=-.5),view())
        val result=IncrementalRegistration.grow(views,
            listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            { _,_ -> FeaturePairs(intArrayOf(),intArrayOf()) },
            { _,_,_ -> error("No triangulation without tracks") },{ _,_ -> error("No fictitious camera") })
        assertEquals(listOf(2),result.unregistered)
        assertEquals(2,result.cameras.size)
        assertTrue(result.attempts.isEmpty())
    }
    @Test fun cancellationIsPropagatedWithoutReturningFakeSuccess() = runBlocking {
        val views=listOf(view(),view(tx=-.5),view())
        try {
            IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
                { _,_ -> FeaturePairs(intArrayOf(),intArrayOf()) },
                { _,_,_ -> emptyList() },{ _,_ -> error("Unexpected solver") },
                { _,_,_ -> throw CancellationException("test stop") })
            fail("Cancellation swallowed")
        } catch(_: CancellationException) { }
    }

    @Test fun inaccurateNativePoseCannotBePublishedEvenWithClaimedInliers() = runBlocking {
        val views=listOf(view(20),view(20,tx=-.5),view(20))
        val result=IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            { _,_ -> FeaturePairs(IntArray(20) { it },IntArray(20) { it }) },
            { _,_,_ -> List(20) { FrontierPoint(it,doubleArrayOf(it*.1,0.0,5.0)) } },
            { _,_ -> FrontierPose(views[2].rotation,doubleArrayOf(100.0,0.0,0.0),IntArray(20) { it }) })
        assertEquals(2,result.cameras.size)
        assertEquals(listOf(2),result.unregistered)
        assertTrue(result.attempts.single().reason.contains("reprojection"))
    }
}
