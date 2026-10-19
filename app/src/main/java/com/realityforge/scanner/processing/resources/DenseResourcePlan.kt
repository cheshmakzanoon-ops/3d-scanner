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
    val native = packedDepthBytes * 3L + maximumCachedLuminanceBytes * 2L + atlasBytes
    return WorkloadPlan(
        depthEdge = depthEdge,
        estimatedManagedBytes = maximumCachedLuminanceBytes + atlasBytes,
        estimatedNativeBytes = native,
        estimatedTransientStorageBytes = packedDepthBytes * 2L + atlasBytes,
    )
}
