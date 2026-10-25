package com.realityforge.scanner.capture.metadata

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Uncalibrated diagonal-equivalent prior from EXIF's processed-image focal plane.
 * CIPA DC-X008-2019 p60: these resolutions describe the recorded main image,
 * NOT the physical sensor. Never populate sensorSizeMm from this calculation.
 * The legacy scalar projection assumes square pixels, so anisotropic axes are
 * declined, not averaged. Tolerance permits numeric rounding only, not lens error.
 */
object ExifFocalPlaneEstimate {
    const val AXIS_ROUNDING_TOLERANCE = 1e-6

    fun equivalent35mm(
        focalMm: Double?, xResolution: Double?, yResolution: Double?, unit: Int?,
        exifWidth: Int?, exifHeight: Int?, encodedWidth: Int, encodedHeight: Int,
        orientation: Int? = null, digitalZoom: Double? = null,
    ): Double? {
        if (encodedWidth <= 0 || encodedHeight <= 0 ||
            exifWidth != encodedWidth || exifHeight != encodedHeight) return null
        if (orientation != null && orientation !in 1..8) return null
        // CIPA Exif p63: zero means digital zoom was not used.
        // Do not guess whether stale focal-plane tags predate a declared crop.
        if (digitalZoom != null && (!digitalZoom.isFinite() || digitalZoom != 0.0 && digitalZoom != 1.0)) return null
        // EXIF ResolutionUnit: 1 means no absolute unit, 2 inch, 3 centimeter.
        val unitMm = when (unit) { 2 -> 25.4; 3 -> 10.0; else -> return null }
        val f = focalMm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val x = xResolution?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val y = yResolution?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (abs(x - y) / max(x, y) > AXIS_ROUNDING_TOLERANCE) return null
        val extentX = encodedWidth.toDouble() * unitMm / x
        val extentY = encodedHeight.toDouble() * unitMm / y
        if (!extentX.isFinite() || !extentY.isFinite() || extentX <= 0.0 || extentY <= 0.0) return null
        // Diagonal convention matches ImageLoader.focalPixels. Rotation/mirroring
        // does not change the diagonal; use encoded axes before EXIF rotation.
        return (f * hypot(36.0, 24.0) / hypot(extentX, extentY))
            .takeIf { it.isFinite() && it in 10.0..600.0 }
    }
}
