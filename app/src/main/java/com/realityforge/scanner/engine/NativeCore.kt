package com.realityforge.scanner.engine

/** All geometry is evaluated by the C++ library also exercised in host tests. */
object NativeCore {
    init { System.loadLibrary("realityforge") }
    external fun reconstruct(rows: DoubleArray, threshold: Double): String
    /** Same packed geometry; token is bound to one active native call. Cancellation throws. */
    external fun reconstructCancellable(rows: DoubleArray, threshold: Double, token: Long): String
    external fun registerCameraCancellable(tracks: DoubleArray, threshold: Double, token: Long): String
    external fun reconstructMultiviewCancellable(poses: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int, token: Long): String
    external fun bundleAdjustCancellable(poses: DoubleArray, points: DoubleArray, tracks: DoubleArray, threshold: Double, iterations: Int, token: Long): String
    /** Versioned pixel observations, intrinsics and stable source IDs; same exclusive token lease. */
    external fun bundleAdjustProblemCancellable(cameraRows12: DoubleArray, seedRows7: DoubleArray,
        pixelObservationRows7: DoubleArray, intrinsicRows11: DoubleArray, groupIds: Array<String>,
        photoIds: Array<String>, sourceTrackIds: Array<String>, measured: Boolean, refineFocal: Boolean,
        thresholdNormalized: Double, iterations: Int, token: Long): String
    /** Schema1: schema, stage(0Sparse..6Checkpoint), reference, tile, completed, total(-1unknown), sequence. */
    external fun pollNativeProgress(token: Long): LongArray
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
    /** Opt-in actual coarse/prior depth;009 canonical identity binds all immutable inputs. */
    external fun reconstructDenseRefinedCancellable(rgba: ByteArray, width: Int, height: Int,
        cameras: DoubleArray, near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        priorRows: DoubleArray, coarseRgba: ByteArray, coarseWidth: Int, coarseHeight: Int,
        coarseCameras: DoubleArray, inputIdentity: String, token: Long): String
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
    /** Explicit admitted 2K compact atlas, same checkpoint/cancellation ownership. */
    external fun reconstructTexturedSurfaceCompactCheckpointed(depthRgba: ByteArray, width: Int, height: Int,
        photoRgba: ByteArray, photoWidth: Int, photoHeight: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        meshGlbPath: String, meshPlyPath: String, texturedGlbPath: String, atlasPngPath: String,
        token: Long, checkpointDirectory: String, checkpointIdentity: String): String
    /** One admitted refined depth pass continues to actual observed mesh/photo exports. */
    external fun reconstructTexturedSurfaceRefinedCheckpointed(depthRgba: ByteArray, width: Int, height: Int,
        photoRgba: ByteArray, photoWidth: Int, photoHeight: Int, cameras: DoubleArray,
        near: Double, far: Double, depthPlanes: Int, pixelStride: Int,
        meshGlbPath: String, meshPlyPath: String, texturedGlbPath: String, atlasPngPath: String,
        priorRows: DoubleArray, coarseRgba: ByteArray, coarseWidth: Int, coarseHeight: Int,
        coarseCameras: DoubleArray, refinementIdentity: String, sourceAdjacency: IntArray, compactAtlas: Boolean,
        token: Long, checkpointDirectory: String, checkpointIdentity: String): String
    external fun quality(gray: ByteArray, width: Int, height: Int): DoubleArray
    external fun exportPointsTyped(rows: DoubleArray, destination: String, glb: Boolean, dense: Boolean)
    external fun exportPoints(rows: DoubleArray, destination: String, glb: Boolean)
}
