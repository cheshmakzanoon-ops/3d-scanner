package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class CalibratedInitializerInputTest {
    @Test fun actualPixelsAndPairOrderRemainSeparateFromUndistortedRays() {
        val a = LensProjection(100.0,100.0,50.0,50.0,1.0)
        val b = LensProjection(200.0,200.0,100.0,100.0,-.1)
        val first = floatArrayOf(75.38f,55.3f,60.12f,55.06f)
        val second = floatArrayOf(145f,110f,125f,115f)
        val input = CalibratedInitializerInput.create(a,100,100,first,intArrayOf(1,0),b,200,200,second,intArrayOf(0,1))
        assertArrayEquals(doubleArrayOf(first[2].toDouble(),first[3].toDouble(),145.0,110.0,first[0].toDouble(),first[1].toDouble(),125.0,115.0),input.observed,0.0)
        assertEquals(22,input.domains.size);assertEquals(100.0,input.domains[9],0.0);assertEquals(200.0,input.domains[20],0.0)
        assertEquals(1.0,input.domains[4],0.0);assertEquals(-.1,input.domains[15],0.0)
        val ray=a.normalized(first[0].toDouble(),first[1].toDouble())
        assertNotEquals(first[0].toDouble(),ray.first*a.fx+a.cx,.01)
    }
    @Test fun rejectsMisalignedKeysAndOutOfBoundsObservedPixels() {
        val lens=LensProjection(100.0,100.0,50.0,50.0)
        fun invalid(first:FloatArray,keys:IntArray,secondKeys:IntArray=keys) {
            try {CalibratedInitializerInput.create(lens,100,100,first,keys,lens,100,100,floatArrayOf(50f,50f),secondKeys);fail("expected invalid observed input")}catch(_:IllegalArgumentException){}
        }
        invalid(floatArrayOf(50f,50f),intArrayOf(1));invalid(floatArrayOf(100f,50f),intArrayOf(0));invalid(floatArrayOf(Float.NaN,50f),intArrayOf(0));invalid(floatArrayOf(50f,50f),intArrayOf(0),intArrayOf())
    }
    @Test fun oldFrontierCannotBeLoadedOrRenamedIntoCurrentPolicy() {
        val old = snapshot("calibrated-h-observed-pixels-sticky-dominance-v1").identity(PipelineStage.SPARSE).digest
        val current = snapshot(CalibratedInitializerInput.POLICY).identity(PipelineStage.SPARSE).digest
        val directory = java.nio.file.Files.createTempDirectory("distinct-pairs-frontier").toFile()
        try {
            val store = com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore(directory)
            val payload = byteArrayOf(1, 2, 3)
            assertTrue(store.publish(old, payload) { false })
            assertArrayEquals(payload, store.load(old) { false })
            assertNull(store.load(current) { false })
            val cache = java.io.File(directory, com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore.DIRECTORY_NAME)
            java.io.File(cache, "$old.frontier").copyTo(java.io.File(cache, "$current.frontier"))
            assertNull("Embedded stage identity must reject renamed stale bytes", store.load(current) { false })
            assertArrayEquals(payload, store.load(old) { false })
        } finally { directory.deleteRecursively() }
    }
    private fun snapshot(policy: String) = PipelineInputSnapshot("project",1,listOf(
        PipelinePhotoInput("a","a".repeat(64),384,288,100,26.0),
        PipelinePhotoInput("b","b".repeat(64),384,288,100,26.0)),"calibration","engine",
        PipelineStageSettings("features","matches",policy,"dense","surface","texture","export"))
    @Test fun newSparsePolicyInvalidatesSparseAndDescendantsNotFeaturesOrMatches() {
        val old=PipelineInputSnapshot("project",1,listOf(PipelinePhotoInput("a","a".repeat(64),384,288,100,26.0),PipelinePhotoInput("b","b".repeat(64),384,288,100,26.0)),"calibration","engine",PipelineStageSettings("features","matches","incremental-frontier-v1|complete-source-window-typed-ba-frontier-admitted12-v4|calibrated-h-observed-pixels-sticky-dominance-v1|assumed-focal35=26.0","dense","surface","texture","export"))
        val next=old.copy(settings=old.settings.copy(sparse=old.settings.sparse.replace("calibrated-h-observed-pixels-sticky-dominance-v1",CalibratedInitializerInput.POLICY)))
        assertEquals("calibrated-h-observed-pixels-sticky-dominance-distinct-pairs-v2",CalibratedInitializerInput.POLICY)
        for(stage in PipelineStage.entries)if(stage.ordinal<PipelineStage.SPARSE.ordinal)assertEquals(old.identity(stage).digest,next.identity(stage).digest) else assertNotEquals(old.identity(stage).digest,next.identity(stage).digest)
    }
}
