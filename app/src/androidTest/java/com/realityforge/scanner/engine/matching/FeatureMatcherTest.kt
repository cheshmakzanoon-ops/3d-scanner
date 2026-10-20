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
    @Test fun boundedMatcherExactlyMatchesWholePairReferenceOnSyntheticPhotographs() {
        assertTrue(OpenCVLoader.initLocal())
        val instrument=InstrumentationRegistry.getInstrumentation()
        val files=(1..2).map{i->File(instrument.targetContext.cacheDir,"a035-$i.jpg").also{f->instrument.context.assets.open("relief/view-0$i.jpg").use{input->f.outputStream().use{input.copyTo(it)}}}}
        val extractor=FeatureExtractor()
        val frames=files.mapIndexed{i,f->extractor.extract("photo-$i",MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)},f,"synthetic-fixture-v1",{w,h->LensProjection.estimated(w,h,1000.0)})}
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
            assertThrows(CancellationException::class.java){matcher.match(frames[0],frames[1],pair){true}}
        }finally{frames.forEach{it.close()};files.forEach{it.delete()}}
    }
}
