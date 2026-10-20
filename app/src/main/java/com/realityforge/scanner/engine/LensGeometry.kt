package com.realityforge.scanner.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Brown-Conrady calibrated lens profile in EXIF-upright IMAGE pixel coordinates.
 * The profile is tied to an image aspect/crop/orientation and optical lens setting.
 * A valid profile is not a measurement of real-world object scale.
 */
data class LensProfile(
    val width: Int, val height: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double, val k2: Double, val p1: Double, val p2: Double, val k3: Double,
    val reprojectionRmsPixels: Double, val acceptedViews: Int,
    val focal35mm: Double?,
) {
    init {
        require(width in 32..8192 && height in 32..8192) { "Invalid calibrated image dimensions" }
        require(listOf(fx, fy, cx, cy, k1, k2, p1, p2, k3, reprojectionRmsPixels).all(Double::isFinite))
        require(fx in (width * .25)..(width * 12.0) && fy in (height * .25)..(height * 12.0)) { "Implausible focal calibration" }
        require(cx in 0.1 * width..0.9 * width && cy in 0.1 * height..0.9 * height) { "Implausible principal point" }
        require(abs(k1) <= 1.5 && abs(k2) <= 1.5 && abs(k3) <= 1.5 && abs(p1) <= .2 && abs(p2) <= .2) { "Implausible lens distortion" }
        require(reprojectionRmsPixels in 0.0..2.5) { "Checkerboard calibration residual is too high" }
        require(acceptedViews in 8..60) { "Insufficient calibration viewpoints" }
        require(focal35mm == null || (focal35mm.isFinite() && focal35mm in 10.0..600.0))
    }

    /** Preserve exact aspect/crop and (if present) EXIF focal identity. Never silently reuse across lenses. */
    fun forScan(width: Int, height: Int, exifFocal35: Double?): LensProjection {
        require(width >= 32 && height >= 32)
        val ratioDelta = abs(this.width.toDouble() / this.height - width.toDouble() / height)
        require(ratioDelta < .005) { "Calibration is for a different image aspect/crop; recalibrate this lens." }
        if (focal35mm != null && exifFocal35 != null) {
            require(abs(exifFocal35 / focal35mm - 1.0) <= .08) {
                "EXIF indicates a different camera lens/zoom than the calibration. Recalibrate."
            }
        }
        return LensProjection(
            fx * width / this.width, fy * height / this.height,
            (cx + .5) * width / this.width - .5, (cy + .5) * height / this.height - .5,
            k1, k2, p1, p2, k3,
        ).also { it.validateDomain(width, height) }
    }
}

/** Coordinate mapping from keypoint pixels to physically undistorted normalized rays. */
data class LensProjection(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double = 0.0, val k2: Double = 0.0,
    val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
) {
    init {
        require(fx.isFinite() && fy.isFinite() && fx > 0 && fy > 0)
        require(listOf(cx, cy, k1, k2, p1, p2, k3).all(Double::isFinite))
    }

    fun distort(x: Double, y: Double): Pair<Double, Double> {
        require(x.isFinite() && y.isFinite() && max(abs(x), abs(y)) <= 5)
        val r2 = x*x + y*y
        val radial = 1 + r2*(k1 + r2*(k2 + r2*k3))
        return (x*radial + 2*p1*x*y + p2*(r2 + 2*x*x)) to
            (y*radial + p1*(r2 + 2*y*y) + 2*p2*x*y)
    }

    fun normalized(px: Double, py: Double): Pair<Double, Double> {
        require(px.isFinite() && py.isFinite())
        val targetX = (px - cx) / fx
        val targetY = (py - cy) / fy
        require(max(abs(targetX), abs(targetY)) < 4) { "Feature far outside calibrated view" }
        if (k1 == 0.0 && k2 == 0.0 && k3 == 0.0 && p1 == 0.0 && p2 == 0.0) return targetX to targetY
        var x = targetX; var y = targetY
        // Analytic 2x2 Jacobian with damped Newton; reject roots outside the physical image domain.
        for (iteration in 0 until 25) {
            val r2 = x*x + y*y
            val radial = 1 + r2*(k1 + r2*(k2 + r2*k3))
            val slope = k1 + 2*k2*r2 + 3*k3*r2*r2
            val dx = x*radial + 2*p1*x*y + p2*(r2 + 2*x*x) - targetX
            val dy = y*radial + p1*(r2 + 2*y*y) + 2*p2*x*y - targetY
            
            val a = radial + 2*x*x*slope + 2*p1*y + 6*p2*x
            val b = 2*x*y*slope + 2*p1*x + 2*p2*y
            val c = b
            val d = radial + 2*y*y*slope + 6*p1*y + 2*p2*x
            val det = a*d - b*c
            require(det.isFinite() && det > 1e-9 && a > 0 && d > 0) { "Unstable lens inverse; recalibrate this lens" }
            if (hypot(dx, dy) < 1e-12) return x to y
            val stepX = (d*dx-b*dy)/det
            val stepY = (-c*dx+a*dy)/det
            val step = hypot(stepX,stepY)
            val scale = if (step > .25) .25/step else 1.0
            x -= stepX*scale; y -= stepY*scale
            require(x.isFinite() && y.isFinite() && max(abs(x), abs(y)) < 5) { "Divergent lens inverse" }
        }
        val (checkX,checkY) = distort(x,y)
        require(hypot(checkX-targetX,checkY-targetY) < 1e-8) { "Lens inversion did not converge" }
        return x to y
    }

    /** Verify inverse existence and local orientation throughout the delivered image domain.
     * Samples include every boundary; callers still validate each individual ray at use time.
     */
    fun validateDomain(width: Int, height: Int) {
        require(width > 0 && height > 0)
        for (row in 0..16) for (column in 0..16) {
            val px = column * (width - 1.0) / 16
            val py = row * (height - 1.0) / 16
            val (x,y) = normalized(px,py)
            val (u,v) = distort(x,y)
            require(hypot(u*fx+cx-px,v*fy+cy-py) < 1e-5) { "Lens inverse misses image domain" }
        }
    }

    companion object {
        const val COORDINATE_CONVENTION = "rf-xright-ydown-zforward-w2c-pixel-centres-v2"

        fun estimated(width: Int, height: Int, focalPixels: Double) =
            LensProjection(focalPixels, focalPixels, (width-1)/2.0, (height-1)/2.0)
    }
}
