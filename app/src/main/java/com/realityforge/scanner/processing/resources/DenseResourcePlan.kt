package com.realityforge.scanner.processing.resources

import com.realityforge.scanner.engine.DenseWorkload

/** Conservative estimates, not observed RSS or GPU measurements. Version changes invalidate plans. */
internal fun DenseWorkload.resourcePlan(): WorkloadPlan {
    val atlasColumns = if (viewCount > 4) 3L else 2L
    val atlasRows = (viewCount.toLong() + atlasColumns - 1L) / atlasColumns
    val atlasBytes = (textureEdge.toLong() + 6L) * atlasColumns *
        (textureEdge.toLong() + 6L) * atlasRows * 4L
    // Buffer estimates include depth products, active luminance, temporary images and atlas.
    // Native buffers and transient storage must be validated on ARM64 before raising ceilings.
    // Accepted-surface replay keeps at most 12 MiB encoded + 10 MiB decoded until the input
    // buffer is released. Reserve 24 MiB before admission for both writing and replaying it.
    val native = packedDepthBytes * 3L + maximumCachedLuminanceBytes * 2L + atlasBytes * 2L +
        32L * 1024 * 1024 + AcceptedSurfaceCheckpointBudget.WORKING_BYTES
    return WorkloadPlan(
        depthEdge = depthEdge,
        // Account for simultaneously retained rectified input, texture input/output and one bounded decoder pair.
        estimatedManagedBytes = packedDepthBytes * 2L + maximumCachedLuminanceBytes + atlasBytes * 2L + 16L * 1024 * 1024,
        estimatedNativeBytes = native,
        // One immutable checkpoint and one owned staging file; retained earlier files are
        // already counted by measured free space and are never assumed reclaimable.
        estimatedTransientStorageBytes = packedDepthBytes * 2L + atlasBytes + AcceptedSurfaceCheckpointBudget.PEAK_DISK_BYTES,
    )
}

internal object AcceptedSurfaceCheckpointBudget {
    const val MAX_ENCODED_BYTES = 12L * 1024 * 1024
    const val WORKING_BYTES = 24L * 1024 * 1024
    const val PEAK_DISK_BYTES = 2L * MAX_ENCODED_BYTES
}
