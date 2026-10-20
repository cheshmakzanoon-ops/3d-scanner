package com.realityforge.scanner.engine.features

import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class FeatureExtractorTest {
    @Test fun baselinePhotographIsDeterministicAndOwnedFrameCloses() {
        assertTrue(OpenCVLoader.initLocal())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"a032-photo.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("relief/view-01.jpg").use { input->source.outputStream().use { out -> input.copyTo(out) } }
        val sha=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        val extractor=FeatureExtractor()
        fun run()=extractor.extract("photo",sha,source,"baseline-upright-1600-v2",{w,h->LensProjection.estimated(w,h,ImageLoader.focalPixels(w,h,35.0))})
        val a=run();val b=run()
        try {
            assertTrue(a.count>100);assertTrue(a.count<=3500)
            assertArrayEquals(a.coordinates(),b.coordinates(),0f)
            a.withDescriptors { first-> b.withDescriptors { second->assertEquals(0.0,org.opencv.core.Core.norm(first,second),0.0) } }
            assertEquals(a.settingsIdentity,b.settingsIdentity)
        }finally{a.close();b.close();source.delete()}
        assertThrows(IllegalStateException::class.java){a.withDescriptors{it.rows()}}
    }
    @Test fun cancellationAndCorruptInputCannotReturnSuccess() {
        assertTrue(OpenCVLoader.initLocal())
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"a032-invalid.jpg")
        file.writeBytes(byteArrayOf(1,2,3))
        try {
            val extractor=FeatureExtractor()
            assertThrows(CancellationException::class.java){extractor.extract("p","0".repeat(64),file,"v2",{w,h->LensProjection.estimated(w,h,100.0)},{true})}
            assertThrows(IllegalArgumentException::class.java){extractor.extract("p","0".repeat(64),file,"v2",{w,h->LensProjection.estimated(w,h,100.0)})}
        }finally{file.delete()}
    }
}
