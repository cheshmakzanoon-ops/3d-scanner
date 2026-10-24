package com.realityforge.scanner.capture.guidance

import com.realityforge.scanner.engine.LensProjection
import org.opencv.core.DMatch

/** Native seven-column matches use calibrated rays, not image-edge-centred coordinates.
 * Keep match order and reference-key RGB aligned with the native returned match indices.
 */
internal fun packGuidancePoseRows(
    referenceXy: FloatArray,
    candidateXy: FloatArray,
    referenceColors: IntArray,
    matches: List<DMatch>,
    referenceProjection: LensProjection,
    candidateProjection: LensProjection,
): DoubleArray {
    val rows = DoubleArray(matches.size * 7)
    matches.forEachIndexed { index, match ->
        val offset = index * 7
        val a = referenceProjection.normalized(referenceXy[match.queryIdx * 2].toDouble(),
            referenceXy[match.queryIdx * 2 + 1].toDouble())
        val b = candidateProjection.normalized(candidateXy[match.trainIdx * 2].toDouble(),
            candidateXy[match.trainIdx * 2 + 1].toDouble())
        rows[offset] = a.first
        rows[offset + 1] = a.second
        rows[offset + 2] = b.first
        rows[offset + 3] = b.second
        val color = referenceColors[match.queryIdx]
        rows[offset + 4] = (color ushr 16 and 255).toDouble()
        rows[offset + 5] = (color ushr 8 and 255).toDouble()
        rows[offset + 6] = (color and 255).toDouble()
    }
    return rows
}
