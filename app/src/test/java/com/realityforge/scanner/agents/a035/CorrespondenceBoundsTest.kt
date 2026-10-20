package com.realityforge.scanner.agents.a035

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.matching.FeatureCorrespondence
import org.junit.Assert.*
import org.junit.Test

class CorrespondenceBoundsTest {
    @Test fun validWideAngleUndistortedRayIsNotConfusedWithDistortedPixelBound() {
        val lens=LensProjection(25.0,100.0,10.0,50.0,k1=-.01)
        val distorted=lens.distort(4.2,0.0)
        val px=lens.fx*distorted.first+lens.cx
        assertTrue(px in 0.0..99.0)
        val ray=lens.normalized(px,50.0)
        assertEquals(4.2,ray.first,1e-10)
        val correspondence=FeatureCorrespondence(0,0,ray.first,ray.second,0.0,0.0,0)
        assertEquals(ray.first,correspondence.ax,0.0)
        assertThrows(IllegalArgumentException::class.java){correspondence.copy(ax=Double.NaN)}
        assertThrows(IllegalArgumentException::class.java){correspondence.copy(ax=5.01)}
    }
}
