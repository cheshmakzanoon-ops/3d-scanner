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
            val bitmap=ImageLoader.upright(source)
            val rgba=org.opencv.core.Mat();val gray=org.opencv.core.Mat()
            val keys=org.opencv.core.MatOfKeyPoint();val descriptors=org.opencv.core.Mat();val mask=org.opencv.core.Mat()
            val sift=org.opencv.features2d.SIFT.create(3500)
            try {
                org.opencv.android.Utils.bitmapToMat(bitmap,rgba)
                org.opencv.imgproc.Imgproc.cvtColor(rgba,gray,org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                sift.detectAndCompute(gray,mask,keys,descriptors)
                val reference=keys.toArray();assertEquals(minOf(reference.size,3500),a.count)
                val coordinates=a.coordinates()
                for(i in 0 until a.count){assertEquals(reference[i].pt.x.toFloat(),coordinates[2*i],0f);assertEquals(reference[i].pt.y.toFloat(),coordinates[2*i+1],0f)}
                val bounded=descriptors.rowRange(0,a.count)
                try{a.withDescriptors{assertEquals(0.0,org.opencv.core.Core.norm(it,bounded),0.0)}}finally{bounded.release()}
            }finally{sift.clear();rgba.release();gray.release();keys.release();descriptors.release();mask.release();bitmap.recycle()}
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
