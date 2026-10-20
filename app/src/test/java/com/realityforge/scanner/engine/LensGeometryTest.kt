package com.realityforge.scanner.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LensGeometryTest {
    private val profile = LensProfile(
        1600, 1200, 1410.0, 1398.0, 804.0, 603.0,
        -.23, .09, .0017, -.0021, -.02, .41, 15, 26.0,
    )

    @Test fun distortedPointsRoundTrip() {
        val lens = profile.forScan(1600, 1200, 26.0)
        for (x in -8..8) for (y in -6..6) {
            val rx=x*.045;val ry=y*.045
            val (u,v)=lens.distort(rx,ry)
            val (x2,y2)=lens.normalized(lens.fx*u+lens.cx,lens.fy*v+lens.cy)
            assertEquals(rx,x2,2e-9);assertEquals(ry,y2,2e-9)
        }
    }
    @Test fun scalesIntrinsicsWithPixelCentreMapping() {
        val a=profile.forScan(1600,1200,26.0)
        val b=profile.forScan(800,600,26.0)
        assertEquals(a.fx/2,b.fx,1e-12)
        assertEquals((a.cy+.5)/2-.5,b.cy,1e-12)
        for (p in listOf(100.0 to 200.0, 777.0 to 534.0)) {
            val x=a.normalized(p.first,p.second)
            val y=b.normalized((p.first+.5)/2-.5,(p.second+.5)/2-.5)
            assertEquals(x.first,y.first,1e-10); assertEquals(x.second,y.second,1e-10)
        }
    }
    @Test fun foldedProfilesAreRejectedAcrossImageDomain() {
        val folded = profile.copy(k1=-1.5,k2=0.0,k3=0.0, p1=0.0,p2=0.0)
        assertThrows(IllegalArgumentException::class.java) { folded.forScan(1600,1200,26.0) }
    }
    @Test fun unequalScalePreservesAsymmetricFeatureRays() {
        val a=profile.forScan(1600,1200,26.0)
        val b=profile.forScan(803,602,26.0)
        for (u in listOf(0.0,213.3,1599.0)) for(v in listOf(0.0,777.4,1199.0)) {
            val ray=a.normalized(u,v)
            val next=b.normalized((u+.5)*803/1600-.5,(v+.5)*602/1200-.5)
            assertEquals(ray.first,next.first,1e-10); assertEquals(ray.second,next.second,1e-10)
        }
    }
    @Test fun refusesWrongAspectAndLens() {
        assertThrows(IllegalArgumentException::class.java) { profile.forScan(1200,1600,26.0) }
        assertThrows(IllegalArgumentException::class.java) { profile.forScan(1600,1200,55.0) }
    }
    @Test fun noDistortionMatchesOldPinhole() {
        val p=LensProjection.estimated(1600,1200,1400.0)
        assertEquals((399.0-799.5)/1400.0,p.normalized(399.0,55.0).first,1e-12)
    }
    @Test fun rejectsUnphysicalProfilesAndBadObservations() {
        assertThrows(IllegalArgumentException::class.java) { profile.copy(reprojectionRmsPixels=20.0) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(k1=5.0) }
        assertThrows(IllegalArgumentException::class.java) { profile.forScan(1, 1, null) }
        assertThrows(IllegalArgumentException::class.java) { profile.forScan(1600,1200,26.0).normalized(Double.NaN,20.0) }
    }
}
