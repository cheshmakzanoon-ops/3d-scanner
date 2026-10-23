package com.realityforge.scanner.engine

/** Actual observed pixels (not K times undistorted rays), preserving pair row/key order. */
internal data class CalibratedInitializerInput(val observed: DoubleArray, val domains: DoubleArray) {
    companion object {
        const val POLICY = "calibrated-h-observed-pixels-sticky-dominance-v1"
        fun create(first: LensProjection, firstWidth: Int, firstHeight: Int,
                   firstXY: FloatArray, firstKeys: IntArray,
                   second: LensProjection, secondWidth: Int, secondHeight: Int,
                   secondXY: FloatArray, secondKeys: IntArray): CalibratedInitializerInput {
            require(firstKeys.size == secondKeys.size && firstKeys.size <= 20000)
            require(firstXY.size % 2 == 0 && secondXY.size % 2 == 0)
            require(firstWidth in 3..32768 && firstHeight in 3..32768 && secondWidth in 3..32768 && secondHeight in 3..32768)
            val observed = DoubleArray(firstKeys.size * 4)
            fun copy(xy: FloatArray, keys: IntArray, width: Int, height: Int, offset: Int) {
                keys.forEachIndexed { index, key ->
                    require(key >= 0 && key < xy.size / 2) { "Mismatched calibrated feature identity" }
                    val x = xy[key * 2].toDouble(); val y = xy[key * 2 + 1].toDouble()
                    require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height)
                    observed[index * 4 + offset] = x; observed[index * 4 + offset + 1] = y
                }
            }
            copy(firstXY, firstKeys, firstWidth, firstHeight, 0)
            copy(secondXY, secondKeys, secondWidth, secondHeight, 2)
            fun domain(k: LensProjection, width: Int, height: Int) = doubleArrayOf(
                k.fx, k.fy, k.cx, k.cy, k.k1, k.k2, k.p1, k.p2, k.k3, width.toDouble(), height.toDouble())
            return CalibratedInitializerInput(observed, domain(first, firstWidth, firstHeight) + domain(second, secondWidth, secondHeight))
        }
    }
}
