package com.realityforge.scanner

import com.realityforge.scanner.engine.ImageLoader
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CalibrationTest {
    @Test fun equivalentFocalIsOrientationInvariant() {
        assertEquals(ImageLoader.focalPixels(1600, 1200, 26.0), ImageLoader.focalPixels(1200, 1600, 26.0), 1e-10)
    }
    @Test fun resizingScalesFocalExactly() {
        assertEquals(2 * ImageLoader.focalPixels(800, 600, 35.0), ImageLoader.focalPixels(1600, 1200, 35.0), 1e-10)
    }
    @Test fun fullFrameDiagonalConvention() {
        assertEquals(50.0 * 2000.0 / hypot(36.0, 24.0), ImageLoader.focalPixels(1600, 1200, 50.0), 1e-10)
    }
}
