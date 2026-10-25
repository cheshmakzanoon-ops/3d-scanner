package com.realityforge.scanner.engine.features

import kotlin.math.sqrt

/** Descriptor semantics, not a detector/ratio change. Caller owns both fixed-size rows. */
internal object RootSift {
    const val POLICY = "rf-rootsift-l1-sqrt-v1"
    const val DIMENSIONS = 128
    const val ROW_BYTES = DIMENSIONS * 4

    /** No allocations or aliasing. Invalid/zero rows refuse before touching output.
     * Double accumulation admits all finite nonnegative Float inputs without overflow.
     * A zero histogram has no L1 normalization: never invent a descriptor or drop its key.
     */
    fun normalizeRow(input: FloatArray, output: FloatArray) {
        require(input.size == DIMENSIONS && output.size == DIMENSIONS && input !== output)
        var sum = 0.0
        for (value in input) {
            require(value.isFinite() && value >= 0f) { "Invalid SIFT histogram" }
            sum += value.toDouble()
        }
        require(sum > 0.0 && sum.isFinite()) { "Zero SIFT histogram" }
        for (i in input.indices) output[i] = sqrt(input[i].toDouble() / sum).toFloat()
    }
}
