package com.realityforge.scanner.engine.matching

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.features.FeatureFrame
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.features2d.BFMatcher
import java.util.Random
import java.util.concurrent.CancellationException

/** Exact differential contract, not a physical-device performance claim. */
class SparseReverseMatcherDifferentialTest {
    private fun frame(id:String, values:FloatArray):FeatureFrame {
        val count=values.size/128
        val mat=if(count==0) Mat() else Mat(count,128,CvType.CV_32F).apply{put(0,0,values)}
        return FeatureFrame(id,"0".repeat(64),"differential","differential",32,32,
            FloatArray(count*2){1f},IntArray(count),mat,LensProjection.estimated(32,32,32.0))
    }
    private fun reference(a:FeatureFrame,b:FeatureFrame,ratio:Double):List<Pair<Int,Int>> {
        if(minOf(a.count,b.count)<2)return emptyList()
        return a.withDescriptors{am->b.withDescriptors{bm->
            val matcher=BFMatcher.create(Core.NORM_L2,false)
            val f=ArrayList<MatOfDMatch>();val r=ArrayList<MatOfDMatch>()
            try {
                matcher.knnMatch(am,bm,f,2);matcher.knnMatch(bm,am,r,2)
                fun accepted(rows:List<MatOfDMatch>)=rows.mapNotNull{row->
                    val p=row.toArray()
                    if(p.size==2 && p[0].distance<ratio*p[1].distance) p[0].queryIdx to p[0].trainIdx else null
                }
                val reverse=accepted(r).toMap()
                accepted(f).filter{(i,j)->reverse[j]==i}
            }finally{f.forEach{it.release()};r.forEach{it.release()};matcher.clear()}
        }}
    }
    private fun compare(av:FloatArray,bv:FloatArray,ratio:Double=.67) {
        frame("a",av).use{a->frame("b",bv).use{b->
            val expected=reference(a,b,ratio)
            for(block in listOf(1,2,17,128,256)) {
                val actual=FeatureMatcher(ratio,block).match(a,b,PairCandidate("a","b",0.0,"differential"))
                assertEquals("block=$block ratio=$ratio",expected,actual.matches.map{it.firstKey to it.secondKey})
            }
        }}
    }
    @Test fun exactReciprocalIdentityAndOrderForRandomUnequalEmptyDuplicatesTiesAndRatioBoundary() {
        assertTrue(OpenCVLoader.initLocal())
        val random=Random(738291L)
        for((n,m) in listOf(0 to 4,1 to 8,2 to 2,2 to 19,19 to 2,17 to 33,257 to 311,400 to 90)) {
            repeat(4) {
                val a=FloatArray(n*128){random.nextFloat()*512f}
                val b=FloatArray(m*128){random.nextFloat()*512f}
                for(i in 0 until minOf(n,m)/2)for(k in 0 until 128)b[i*128+k]=a[i*128+k]
                compare(a,b)
            }
        }
        for(value in listOf(0f,1f,512f))compare(FloatArray(33*128){value},FloatArray(65*128){value})
        fun rows(values:List<Float>)=FloatArray(values.size*128){if(it%128==0)values[it/128] else 0f}
        val a=rows(listOf(0f,8f,16f,32f,64f));val b=rows(listOf(1f,2f,9f,17f,33f,65f,80f))
        for(ratio in listOf(.5,Math.nextDown(.5),Math.nextUp(.5),.67,.1,.95))compare(a,b,ratio)
        compare(rows(listOf(0f,0f,4f,8f,12f,16f,20f,24f,28f)),
            rows(listOf(1f,1f,3f,5f,7f,9f,13f,17f,21f,25f,29f)))
    }
    @Test fun bothDirectionsObserveCancellationAfterNativeBlockAndReleaseBorrowedFrames() {
        assertTrue(OpenCVLoader.initLocal())
        val random=Random(19L);val values=FloatArray(80*128){random.nextFloat()*512f}
        for(target in PairMatchDirection.values()) {
            frame("a",values).use{a->frame("b",values).use{b->
                var cancel=false;var reached=false
                assertThrows(CancellationException::class.java) {
                    FeatureMatcher(blockSize=17).match(a,b,PairCandidate("a","b",0.0,"differential"),
                        work={direction,_,_,_,running->if(direction==target && !running){reached=true;cancel=true}},
                        cancelled={cancel})
                }
                assertTrue("Requested direction must execute",reached)
                // Borrowed frames remain owned and valid after cancellation.
                a.withDescriptors{assertEquals(80,it.rows())};b.withDescriptors{assertEquals(80,it.rows())}
            }}
        }
    }
}
