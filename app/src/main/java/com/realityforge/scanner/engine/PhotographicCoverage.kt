package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.VisibilityEvidence
import org.json.JSONObject
import kotlin.math.abs

/** Counts from the compact baker, which retains unsupported geometry in its atlas mesh. */
internal object PhotographicCoverage {
    fun compactVisibility(texture: JSONObject): VisibilityEvidence {
        fun count(key: String): Int {
            val raw = texture.opt(key)
            require(raw is Int || raw is Long) { "Compact $key must be a JSON integer." }
            val value = (raw as Number).toLong()
            require(value in 0..Int.MAX_VALUE.toLong()) { "Compact $key is outside the supported range." }
            return value.toInt()
        }
        val input = count("inputTriangles")
        require(input > 0 && count("triangleCount") == input) {
            "Compact atlas must retain all input triangles."
        }
        val skipped = count("skippedTriangles")
        // Every successful compact bake has at least one supported interior texel/face.
        require(skipped < input) { "Compact skipped triangles must leave supported faces." }
        val supported = input - skipped
        val raw = texture.opt("faceCoverage")
        require(raw is Number) { "Compact face coverage must be numeric." }
        requireSerializedFraction(raw.toDouble(), supported.toDouble() / input, "Compact face coverage")
        return VisibilityEvidence(input, supported)
    }

    fun requireSerializedFraction(actual: Double, expected: Double, label: String) {
        // texturedSurfaceJson uses std::setprecision(10), not fixed decimal places.
        // In [0,1], its maximum decimal rounding error is 5e-11; allow 1e-15
        // additional binary floating-point error without hiding count mismatches.
        require(actual.isFinite() && actual in 0.0..1.0 && abs(actual - expected) <= 5e-11 + 1e-15) {
            "$label does not match its measured counts."
        }
    }
}
