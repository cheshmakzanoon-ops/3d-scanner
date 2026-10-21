package com.realityforge.scanner.engine.matching

import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.features.FeatureExtractor
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.MatOfDMatch
import org.opencv.features2d.BFMatcher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class FeatureMatcherTest {
    @Test fun descriptorRangeIncludes512ButRejectsItsFloatSuccessorAndNonfiniteValues() {
        assertTrue(OpenCVLoader.initLocal())
        val projection=LensProjection.estimated(32,32,32.0)
        fun frame(id:String,value:Float):com.realityforge.scanner.engine.features.FeatureFrame {
            val mat=org.opencv.core.Mat(4,128,org.opencv.core.CvType.CV_32F).apply{put(0,0,FloatArray(4*128){value})}
            return com.realityforge.scanner.engine.features.FeatureFrame(id,"0".repeat(64),"range-test","range-test",32,32,
                FloatArray(8){1f},IntArray(4),mat,projection)
        }
        val matcher=FeatureMatcher();val candidate=PairCandidate("a","b",0.0,"range-test")
        frame("a",0f).use { a ->
            for(value in listOf(0f,Math.nextDown(512f),512f)) frame("b",value).use { b ->
                assertEquals("Inclusive valid endpoint $value",0,matcher.match(a,b,candidate).count)
            }
            for(value in listOf(Math.nextUp(512f),-Float.MIN_VALUE,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
                frame("b",value).use { b ->
                    assertThrows("Reject invalid descriptor $value",IllegalArgumentException::class.java) { matcher.match(a,b,candidate) }
                }
        }
    }

    @Test fun repeatedInvalidAndEmptyDescriptorInputsAreExplicit() {
        assertTrue(OpenCVLoader.initLocal())
        val projection=LensProjection.estimated(32,32,32.0)
        fun frame(id:String,values:FloatArray,count:Int):com.realityforge.scanner.engine.features.FeatureFrame {
            val mat=if(count==0)org.opencv.core.Mat() else org.opencv.core.Mat(count,128,org.opencv.core.CvType.CV_32F).apply{put(0,0,values)}
            return com.realityforge.scanner.engine.features.FeatureFrame(id,"0".repeat(64),"test-only","test-only",32,32,FloatArray(count*2){1f},IntArray(count),mat,projection)
        }
        val a=frame("a",FloatArray(4*128),4);val b=frame("b",FloatArray(4*128),4)
        val bad=frame("b",FloatArray(4*128){Float.NaN},4);val empty=frame("b",FloatArray(0),0)
        try {
            val matcher=FeatureMatcher();val candidate=PairCandidate("a","b",1.0,"test-only")
            assertEquals(0,matcher.match(a,b,candidate).count)
            // Both original cancellation call forms remain source-compatible.
            assertThrows(CancellationException::class.java) { matcher.match(a,b,candidate,{true}) }
            assertThrows(CancellationException::class.java) { matcher.match(a,b,candidate){true} }
            for (cancelAtEntry in listOf(true,false)) {
                val cancellation=CancellationException("observer cancellation")
                val caught=assertThrows(CancellationException::class.java) {
                    matcher.match(a,b,candidate,work={_,_,_,_,running->
                        if(running==cancelAtEntry)throw cancellation
                    })
                }
                assertSame(cancellation,caught)
                // Borrowed descriptors and native temporary owners survive either observer boundary.
                assertEquals(0,matcher.match(a,b,candidate).count)
            }
            assertEquals(0,matcher.match(a,b,candidate,work={_,_,_,_,_->throw IllegalStateException("diagnostic only")}).count)
            assertEquals(0,matcher.match(a,empty,candidate).count)
            assertThrows(IllegalArgumentException::class.java){matcher.match(a,bad,candidate)}
            assertTrue(matcher.verify(matcher.match(a,b,candidate),.001) is GeometricVerification.Rejected)
        }finally{a.close();b.close();bad.close();empty.close()}
    }

    @Test fun boundedMatcherExactlyMatchesWholePairReferenceOnSyntheticPhotographs() {
        assertTrue(OpenCVLoader.initLocal())
        val instrument=InstrumentationRegistry.getInstrumentation()
        val files=(1..2).map{i->File(instrument.targetContext.cacheDir,"a035-$i.jpg").also{f->instrument.context.assets.open("relief/view-0$i.jpg").use{input->f.outputStream().use{input.copyTo(it)}}}}
        val extractor=FeatureExtractor()
        val frames=files.mapIndexed{i,f->extractor.extract("photo-$i",MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)},f,"synthetic-fixture-v1",{w,h->LensProjection.estimated(w,h,760.0)})}
        try {
            val matcher=FeatureMatcher(blockSize=17)
            val pair=PairCandidate("photo-0","photo-1",.9,"synthetic-test-only")
            val matches=matcher.match(frames[0],frames[1],pair)
            val expected=frames[0].withDescriptors{a->frames[1].withDescriptors{b->
                val bf=BFMatcher.create(Core.NORM_L2,false);val f=ArrayList<MatOfDMatch>();val r=ArrayList<MatOfDMatch>()
                try {
                    bf.knnMatch(a,b,f,2);bf.knnMatch(b,a,r,2)
                    val reverse=r.mapNotNull{row->val p=row.toArray();if(p.size>=2 && p[0].distance<.67*p[1].distance)p[0].queryIdx to p[0].trainIdx else null}.toMap()
                    f.mapNotNull{row->val p=row.toArray();if(p.size>=2 && p[0].distance<.67*p[1].distance && reverse[p[0].trainIdx]==p[0].queryIdx)p[0].queryIdx to p[0].trainIdx else null}
                }finally{f.forEach{it.release()};r.forEach{it.release()};bf.clear()}
            }}
            assertTrue(matches.count>20)
            assertEquals(expected,matches.matches.map{it.firstKey to it.secondKey})
            assertEquals(matches.count,matches.matches.map{it.firstKey}.distinct().size)
            val verified=matcher.verify(matches,1.5/760.0)
            assertTrue("Actual native initializer must accept the synthetic calibrated pair",verified is GeometricVerification.Accepted)
            val accepted=(verified as GeometricVerification.Accepted).matches
            assertTrue(accepted.correspondences().size>=20)
            assertTrue(accepted.sourceMatchIndices().all{it in matches.matches.indices})
            assertThrows(CancellationException::class.java){matcher.match(frames[0],frames[1],pair){true}}
        }finally{frames.forEach{it.close()};files.forEach{it.delete()}}
    }
}
