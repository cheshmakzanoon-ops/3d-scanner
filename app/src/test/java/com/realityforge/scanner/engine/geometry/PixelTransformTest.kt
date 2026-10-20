package com.realityforge.scanner.engine.geometry
import org.junit.Assert.*
import org.junit.Test
class PixelTransformTest {
    @Test fun allExifCornersMatchIndependentTableAndRoundTrip() {
        val expected=listOf(0.0 to 0.0,10.0 to 0.0,10.0 to 6.0,0.0 to 6.0,0.0 to 0.0,6.0 to 0.0,6.0 to 10.0,0.0 to 10.0)
        for(o in 1..8) {
            val t=PixelTransform.exif(11,7,o)
            assertEquals(expected[o-1],t.map(0.0,0.0))
            for(x in listOf(0.0,2.7,10.0)) for(y in listOf(0.0,3.2,6.0)) {
                val p=t.map(x,y); val q=t.inverse(p.first,p.second)
                assertEquals(x,q.first,1e-12); assertEquals(y,q.second,1e-12)
            }
        }
    }
    @Test fun composedCropResizeUsesPixelCentresWithoutDrift() {
        val t=PixelTransform.exif(1600,1200,6)
            .then(PixelTransform.crop(1200,1600,100,200,900,1200))
            .then(PixelTransform.resize(900,1200,301,407))
        val p=t.map(700.2,500.3); val q=t.inverse(p.first,p.second)
        assertEquals(700.2,q.first,1e-10); assertEquals(500.3,q.second,1e-10)
        assertEquals(((1199-500.3-100+.5)*301/900-.5),p.first,1e-10)
    }
    @Test fun transformedIntrinsicsAgreeWithFiniteDifferenceProjection() {
        for(o in 1..8) {
            val t=PixelTransform.exif(1600,1200,o).then(PixelTransform.resize(if(o<5)1600 else 1200,if(o<5)1200 else 1600,707,513))
            val k=t.intrinsics(1234.0,1199.0,803.7,591.2)
            val p=t.map(1234*.13+803.7,1199*-.23+591.2)
            assertEquals(k[0]*.13+k[1]*-.23+k[2],p.first,1e-10)
            assertEquals(k[3]*.13+k[4]*-.23+k[5],p.second,1e-10)
            val h=1e-5; val q=t.map(1234*(.13+h)+803.7,1199*-.23+591.2)
            assertEquals(k[0],(q.first-p.first)/h,1e-5)
            assertEquals(k[3],(q.second-p.second)/h,1e-5)
        }
    }
    @Test fun compositionRejectsMismatchedDomainsAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelTransform.identity(11,7).then(PixelTransform.resize(12,7,5,4))
        }
        val t=PixelTransform.resize(1,1,100,100)
        assertThrows(IllegalArgumentException::class.java){t.map(Double.MAX_VALUE,0.0)}
        assertThrows(IllegalArgumentException::class.java){t.intrinsics(Double.MAX_VALUE,100.0,0.0,0.0)}
    }
    @Test fun invalidCropAndOrientationFail() {
        assertThrows(IllegalArgumentException::class.java){PixelTransform.exif(5,4,9)}
        assertThrows(IllegalArgumentException::class.java){PixelTransform.crop(5,4,4,0,2,1)}
    }
}
