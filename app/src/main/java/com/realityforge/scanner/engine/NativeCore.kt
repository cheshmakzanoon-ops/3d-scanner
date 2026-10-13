package com.realityforge.scanner.engine

/** All geometry is evaluated by the C++ library also exercised in host tests. */
object NativeCore {
    init { System.loadLibrary("realityforge") }
    external fun reconstruct(rows: DoubleArray, threshold: Double): String
    external fun registerCamera(tracks: DoubleArray, threshold: Double): String
    external fun reconstructMultiview(poses: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int): String
    external fun bundleAdjust(poses: DoubleArray, points: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int): String
    external fun reconstructDense(rgba: ByteArray, width: Int, height: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int): String
    external fun reconstructSurface(rgba: ByteArray, width: Int, height: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        glbPartialPath: String, plyPartialPath: String): String
    external fun reconstructTexturedSurface(depthRgba: ByteArray, width: Int, height: Int,
        photoRgba: ByteArray, photoWidth: Int, photoHeight: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        meshGlbPath: String, meshPlyPath: String, texturedGlbPath: String, atlasPngPath: String): String
    /** A unique lease; cancel only the native worker that owns this token. */
    external fun beginReconstruction(): Long
    external fun cancelReconstruction(token: Long): Boolean
    external fun finishReconstruction(token: Long): Boolean
    external fun reconstructSurfaceCancellable(rgba: ByteArray, width: Int, height: Int,
        cameras: DoubleArray, near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        glbPartialPath: String, plyPartialPath: String, token: Long): String
    external fun reconstructTexturedSurfaceCancellable(depthRgba: ByteArray, width: Int, height: Int,
        photoRgba: ByteArray, photoWidth: Int, photoHeight: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        meshGlbPath: String, meshPlyPath: String, texturedGlbPath: String, atlasPngPath: String,
        token: Long): String
    /** M18: same native cancellation lease, with atomic camera-depth checkpoints. */
    external fun reconstructSurfaceCheckpointed(rgba: ByteArray, width: Int, height: Int,
        cameras: DoubleArray, near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        glbPartialPath: String, plyPartialPath: String, token: Long,
        checkpointDirectory: String, checkpointIdentity: String): String
    external fun reconstructTexturedSurfaceCheckpointed(depthRgba: ByteArray, width: Int, height: Int,
        photoRgba: ByteArray, photoWidth: Int, photoHeight: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        meshGlbPath: String, meshPlyPath: String, texturedGlbPath: String, atlasPngPath: String,
        token: Long, checkpointDirectory: String, checkpointIdentity: String): String
    external fun quality(gray: ByteArray, width: Int, height: Int): DoubleArray
    external fun exportPoints(rows: DoubleArray, destination: String, glb: Boolean)
}
