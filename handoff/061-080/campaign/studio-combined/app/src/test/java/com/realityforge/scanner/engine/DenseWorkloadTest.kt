package com.realityforge.scanner.engine

import org.junit.Assert.*
import org.junit.Test

class DenseWorkloadTest {
    private val heap = 512L*1024*1024
    @Test fun nativeSampleLoopsAndPlannerAgreeForAllSupportedImageSizes() {
        for (w in 32..384) for (stride in 3..8) {
            val h=widthToHeight(w)
            var n=0L
            var y=3
            while (y<h-4) { var x=3; while (x<w-4) { ++n; x+=stride }; y+=stride }
            assertEquals("w=$w stride=$stride",n*8,DenseWorkload.gridSamples(w,h,8,stride))
        }
    }
    private fun widthToHeight(w:Int) = maxOf(32,w*2/3)
    @Test fun eightSquareViewsAutomaticallyIncreaseStrideNotDiscardCameras() {
        val plan=DenseWorkload.plan(384,384,8,heap)
        assertEquals(4,plan.pixelStride)
        assertEquals(8,plan.viewCount)
        assertTrue(plan.sampledGridPixels <= 100000)
        assertEquals(4L*384*384*4,plan.maximumCachedLuminanceBytes)
        assertEquals(8L*384*384*4,plan.packedDepthBytes)
    }
    @Test fun landscapeEightViewJobRetainsHigherDensityWithinTheCap() {
        val plan=DenseWorkload.plan(384,256,8,heap)
        assertEquals(3,plan.pixelStride)
        assertTrue(plan.sampledGridPixels < 100000)
        assertEquals(640,plan.textureEdge)
        assertTrue(DenseWorkload.atlasPixels(640,640,8)<4500000)
    }
    @Test fun compactHeapUsesSmallerInputWithoutPretendingToBeMeasuredRss() {
        val plan=DenseWorkload.plan(256,256,8,192L*1024*1024)
        assertEquals("compact",plan.memoryTier)
        assertEquals(256,plan.depthEdge)
        assertEquals(512,plan.textureEdge)
    }
    @Test fun selectionIncludesAnchorsAndTheLastRegisteredView() {
        for (n in 2..12) {
            val selected=DenseWorkload.selectCameraIndices(n)
            assertEquals(minOf(n,8),selected.size)
            assertEquals(selected.size,selected.toSet().size)
            assertTrue(selected.all { it in 0 until n })
            assertEquals(0,selected[0]);assertEquals(1,selected[1])
            assertEquals(n-1,selected.last())
        }
    }
    @Test fun impossibleDimensionsCameraCountsAndOversizedAtlasAreRejected() {
        for (operation in listOf<() -> Any>(
            { DenseWorkload.plan(640,640,8,heap) },
            { DenseWorkload.plan(384,384,9,heap) },
            { DenseWorkload.plan(256,256,8,64L*1024*1024) },
            { DenseWorkload.selectCameraIndices(13) },
            { DenseWorkload.atlasPixels(768,768,8) })) {
            try { operation(); fail("Unbounded workload accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    private fun cameras():DoubleArray = DoubleArray(32).also { rows ->
        for (base in listOf(0,16)) {
            rows[base]=1.0;rows[base+4]=1.0;rows[base+8]=1.0
            rows[base+12]=100.0;rows[base+13]=100.0
            rows[base+14]=63.5;rows[base+15]=63.5
        }
        rows[25]=-.5
    }
    @Test fun offscreenAndBehindCameraPointsDoNotInflateTheDepthInterval() {
        val good=DoubleArray(60*3) { i -> when (i%3) {0 -> (i/3%10-5)*.05;1 -> (i/30-3)*.05;else -> 5.0} }
        val junk=DoubleArray(60*3) { i -> when(i%3){0 -> 100000.0;1 -> 100000.0;else -> 9000.0} }
        val a=DenseWorkload.depthBounds(cameras(),good,128,128)
        val b=DenseWorkload.depthBounds(cameras(),good+junk,128,128)
        assertEquals(a,b);assertEquals(3.75,a.first,1e-10);assertEquals(6.5,a.second,1e-10)
    }
    @Test fun noVisibleDepthPriorsAreAnExplicitFailure() {
        val points=DoubleArray(90) { if(it%3==2)-5.0 else 0.0 }
        try { DenseWorkload.depthBounds(cameras(),points,128,128); fail("Behind-camera depths accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
