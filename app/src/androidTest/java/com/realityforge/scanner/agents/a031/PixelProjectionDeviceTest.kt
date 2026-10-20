package com.realityforge.scanner.agents.a031

import android.graphics.Bitmap
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.engine.geometry.PixelTransform
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*

/** Synthetic analytic projection/raster fixtures through actual Android/OpenCV APIs. */
class PixelProjectionDeviceTest {
    @Test fun brownConradyProjectionAgreesWithAndroidOpenCvAtAsymmetricResolutions() {
        assertTrue(OpenCVLoader.initLocal())
        val profile=LensProfile(1600,1200,1410.0,1398.0,804.0,603.0,-.23,.09,.0017,-.0021,-.02,.41,15,26.0)
        for((width,height) in listOf(1600 to 1200,803 to 602,2400 to 1800)) {
            val lens=profile.forScan(width,height,26.0)
            val points=MatOfPoint3f(*(-4..4).flatMap{x->(-3..3).map{y->Point3(x*.09,y*.09,1.0)}}.toTypedArray())
            val rvec=Mat.zeros(3,1,CvType.CV_64F);val tvec=Mat.zeros(3,1,CvType.CV_64F)
            val k=Mat(3,3,CvType.CV_64F).apply{put(0,0,doubleArrayOf(lens.fx,0.0,lens.cx,0.0,lens.fy,lens.cy,0.0,0.0,1.0))}
            val distortion=MatOfDouble(lens.k1,lens.k2,lens.p1,lens.p2,lens.k3);val pixels=MatOfPoint2f()
            try {
                Calib3d.projectPoints(points,rvec,tvec,k,distortion,pixels)
                for((point,pixel) in points.toArray().zip(pixels.toArray())) {
                    val ray=lens.normalized(pixel.x,pixel.y)
                    // OpenCV Point2f/Point3f fixtures use float32; allow .001 delivered pixel.
                    assertEquals(point.x,ray.first,.001/lens.fx)
                    assertEquals(point.y,ray.second,.001/lens.fy)
                }
            }finally{points.release();rvec.release();tvec.release();k.release();distortion.release();pixels.release()}
        }
    }
    @Test fun androidBitmapResizeSamplesTheSamePixelCentres() {
        val input=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until input.height)for(x in 0 until input.width)input.setPixel(x,y,0xff000000.toInt() or (x shl 16) or (y shl 8))
        val target=Bitmap.createScaledBitmap(input,61,47,true)
        val transform=PixelTransform.resize(128,96,61,47)
        try {
            for(y in 2 until target.height-2 step 7)for(x in 2 until target.width-2 step 7){
                val p=transform.inverse(x.toDouble(),y.toDouble());val value=target.getPixel(x,y)
                assertEquals(p.first,(value shr 16 and 255).toDouble(),1.1)
                assertEquals(p.second,(value shr 8 and 255).toDouble(),1.1)
            }
        }finally{target.recycle();input.recycle()}
    }
}
