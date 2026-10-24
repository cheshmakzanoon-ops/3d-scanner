package com.realityforge.scanner.capture.guidance

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.geometry.PixelTransform
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.DMatch

/** Synthetic pixel observations exercise actual production packing; no JNI/pose-quality claim. */
class GuidancePoseRowsTest {
    private fun one(x:Float,y:Float,u:Float,v:Float,a:LensProjection,b:LensProjection) =
        packGuidancePoseRows(floatArrayOf(x,y),floatArrayOf(u,v),intArrayOf(0xff123456.toInt()),
            listOf(DMatch(0,0,0f)),a,b)

    @Test fun evenAndOddImageCentresAreZeroRays() {
        for((width,height) in listOf(320 to 240,321 to 241)) {
            val x=(width-1)/2f;val y=(height-1)/2f
            val lens=LensProjection.estimated(width,height,320.0)
            assertArrayEquals(doubleArrayOf(0.0,0.0,0.0,0.0,18.0,52.0,86.0),
                one(x,y,x,y,lens,lens),0.0)
        }
    }

    @Test fun offCentreRaysMatchReconstructionProjectionWithUnequalFocals() {
        val a=LensProjection.estimated(320,240,310.0)
        val b=LensProjection.estimated(640,480,710.0)
        val rows=one(44.25f,201.75f,500.5f,19.25f,a,b)
        val first=a.normalized(44.25,201.75);val second=b.normalized(500.5,19.25)
        assertArrayEquals(doubleArrayOf(first.first,first.second,second.first,second.second),rows.copyOfRange(0,4),0.0)
        // The shared packing also respects distinct horizontal/vertical focal parameters.
        val anisotropic=LensProjection(310.0,370.0,159.5,119.5)
        val ray=anisotropic.normalized(44.25,201.75)
        val packed=one(44.25f,201.75f,500.5f,19.25f,anisotropic,b)
        assertEquals(ray.first,packed[0],0.0);assertEquals(ray.second,packed[1],0.0)
    }

    @Test fun pixelCentreResizePreservesNormalizedRays() {
        val resize=PixelTransform.resize(320,240,640,480)
        val point=resize.map(101.25,80.75)
        val rows=one(101.25f,80.75f,point.first.toFloat(),point.second.toFloat(),
            LensProjection.estimated(320,240,320.0),LensProjection.estimated(640,480,640.0))
        assertEquals(rows[0],rows[2],0.0);assertEquals(rows[1],rows[3],0.0)
    }

    @Test fun nonidentityMatchOrderAndReferenceRgbRemainAligned() {
        val a=floatArrayOf(20f,30f,40f,50f,60f,70f)
        val b=floatArrayOf(101f,111f,121f,131f,141f,151f)
        val colors=intArrayOf(0x00112233,0xff445566.toInt(),0x7f778899)
        val matches=listOf(DMatch(2,0,1f),DMatch(0,2,2f),DMatch(1,1,3f))
        val lens=LensProjection.estimated(320,240,320.0)
        val rows=packGuidancePoseRows(a,b,colors,matches,lens,lens)
        assertEquals(21,rows.size)
        for((i,match) in matches.withIndex()) {
            val x=lens.normalized(a[match.queryIdx*2].toDouble(),a[match.queryIdx*2+1].toDouble())
            val y=lens.normalized(b[match.trainIdx*2].toDouble(),b[match.trainIdx*2+1].toDouble())
            val color=colors[match.queryIdx]
            assertArrayEquals(doubleArrayOf(x.first,x.second,y.first,y.second,
                (color ushr 16 and 255).toDouble(),(color ushr 8 and 255).toDouble(),(color and 255).toDouble()),
                rows.copyOfRange(i*7,i*7+7),0.0)
        }
        assertArrayEquals(floatArrayOf(20f,30f,40f,50f,60f,70f),a,0f)
        assertArrayEquals(floatArrayOf(101f,111f,121f,131f,141f,151f),b,0f)
        assertArrayEquals(intArrayOf(0x00112233,0xff445566.toInt(),0x7f778899),colors)
    }
}
