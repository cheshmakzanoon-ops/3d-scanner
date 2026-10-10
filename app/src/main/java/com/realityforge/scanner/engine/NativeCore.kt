package com.realityforge.scanner.engine

/** All geometry is evaluated by the C++ library also exercised in host tests. */
object NativeCore {
    init { System.loadLibrary("realityforge") }
    external fun reconstruct(rows: DoubleArray, threshold: Double): String
    external fun registerCamera(tracks: DoubleArray, threshold: Double): String
    external fun reconstructMultiview(poses: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int): String
    external fun bundleAdjust(poses: DoubleArray, points: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int): String
    external fun quality(gray: ByteArray, width: Int, height: Int): DoubleArray
    external fun exportPoints(rows: DoubleArray, destination: String, glb: Boolean)
}
