package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.pipeline.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RootSiftTest {
    @Test fun exactHistogramFormulaAndUnitNorm() {
        val input=FloatArray(128);input[0]=1f;input[1]=4f
        val original=input.copyOf();val result=FloatArray(128)
        RootSift.normalizeRow(input,result)
        assertArrayEquals(original,input,0f)
        assertEquals(sqrt(.2).toFloat(),result[0],0f);assertEquals(sqrt(.8).toFloat(),result[1],0f)
        assertTrue(result.drop(2).all{it==0f})
        assertEquals(1.0,result.sumOf{it.toDouble()*it},1e-7)
    }
    @Test fun positiveScalingAndPermutationEquivariance() {
        val a=FloatArray(128){(it+1).toFloat()};val b=FloatArray(128);val c=FloatArray(128)
        RootSift.normalizeRow(a,b);RootSift.normalizeRow(FloatArray(128){a[it]*8},c)
        assertArrayEquals(b,c,0f)
        RootSift.normalizeRow(a.reversedArray(),c);assertArrayEquals(b.reversedArray(),c,0f)
    }
    @Test fun finiteExtremeValuesDoNotOverflowOrUnderflowSum() {
        for(value in listOf(Float.MAX_VALUE,Float.MIN_VALUE)) {
            val output=FloatArray(128);RootSift.normalizeRow(FloatArray(128){value},output)
            assertTrue(output.all{it.isFinite() && it>0f})
            assertEquals(1.0,output.sumOf{it.toDouble()*it},1e-6)
        }
    }
    @Test fun malformedRowsLeaveDestinationUntouched() {
        for(value in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val input=FloatArray(128){1f};input[127]=value
            val output=FloatArray(128){7f}
            assertThrows(IllegalArgumentException::class.java){RootSift.normalizeRow(input,output)}
            assertTrue(output.all{it==7f})
        }
        for(zero in listOf(0f,-0f)) {
            val out=FloatArray(128){7f}
            assertThrows(IllegalArgumentException::class.java){RootSift.normalizeRow(FloatArray(128){zero},out)}
            assertTrue(out.all{it==7f})
        }
    }
    @Test fun exactDimensionsAndNonAliasingRequired() {
        for(size in listOf(0,1,127,129,256)) {
            assertThrows(IllegalArgumentException::class.java){RootSift.normalizeRow(FloatArray(size){1f},FloatArray(128))}
            assertThrows(IllegalArgumentException::class.java){RootSift.normalizeRow(FloatArray(128){1f},FloatArray(size))}
        }
        val row=FloatArray(128){1f};assertThrows(IllegalArgumentException::class.java){RootSift.normalizeRow(row,row)}
        assertTrue(row.all{it==1f})
    }
    @Test fun fixedScratchCanBeReusedForMaximumAdmittedFeatureCount() {
        val input=FloatArray(128);val output=FloatArray(128)
        assertEquals(512,RootSift.ROW_BYTES)
        repeat(20000){ row ->
            input.fill(0f);input[row%128]=(row+1).toFloat()
            RootSift.normalizeRow(input,output)
            assertEquals(1f,output[row%128],0f);assertEquals(1.0,output.sumOf{it.toDouble()},0.0)
        }
    }
    @Test fun oldRawCacheRefusesNewPolicyAndRootRowsRoundTripWithoutAliasing() {
        val old=FeatureCacheIdentity("a".repeat(64),"same-decoder","rf-sift-v2","4.12.0")
        val next=old.copy(extractorSettings=RootSift.POLICY)
        val raw=FloatArray(128){(it+1).toFloat()};val rooted=FloatArray(128)
        RootSift.normalizeRow(raw,rooted)
        val oldBytes=FeatureCacheFormat.encode(CachedFeatures(old,32,32,floatArrayOf(1f,2f),intArrayOf(123),raw))
        assertThrows(IllegalArgumentException::class.java){FeatureCacheFormat.decode(oldBytes,next)}
        val record=CachedFeatures(next,32,32,floatArrayOf(1f,2f),intArrayOf(123),rooted)
        val expected=rooted.copyOf();rooted.fill(0f)
        val restored=FeatureCacheFormat.decode(FeatureCacheFormat.encode(record),next)
        assertArrayEquals(expected,restored.descriptors(),0f)
        assertArrayEquals(floatArrayOf(1f,2f),restored.coordinates(),0f)
        assertArrayEquals(intArrayOf(123),restored.colors())
        assertEquals(old.originalSha256,restored.identity.originalSha256)
        restored.descriptors().fill(0f);assertArrayEquals(expected,restored.descriptors(),0f)
    }
    @Test fun descriptorPolicyChangesEveryStageIdentityButNotOriginals() {
        val old=PipelineInputSnapshot("p",1,listOf(PipelinePhotoInput("a","a".repeat(64),128,128,100,26.0),
            PipelinePhotoInput("b","b".repeat(64),128,128,100,26.0)),"calibration","engine",
            PipelineStageSettings("raw-extractor-identity","same-l2-matcher","same-sparse","same-dense","same-surface","same-texture","same-export"))
        val next=old.copy(settings=old.settings.copy(features=RootSift.POLICY))
        assertEquals(old.photos,next.photos)
        for(stage in PipelineStage.entries)assertNotEquals(old.identity(stage),next.identity(stage))
        assertEquals(old.settings.matching,next.settings.matching)
    }
}
