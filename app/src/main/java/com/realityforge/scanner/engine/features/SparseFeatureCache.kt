package com.realityforge.scanner.engine.features

import java.io.File

/** Production adapter: calibration is applied after loading, not baked into these image features. */
internal class SparseFeatureCache(directory: File) {
    private val cache = FeatureCache(directory, BYTE_BUDGET)

    fun artifactFile(originalSha256: String): File = cache.artifactFile(identity(originalSha256))

    fun load(originalSha256: String): CachedFeatures? {
        val hit = cache.load(identity(originalSha256)) as? FeatureCacheRead.Hit ?: return null
        // The reusable codec supports larger future profiles; this pipeline does not yet do so.
        return hit.record.takeIf { it.width <= ANALYSIS_EDGE && it.height <= ANALYSIS_EDGE && it.count <= MAX_FEATURES }
    }

    /** Callers decide whether persistence is mandatory for their durable checkpoint contract. */
    fun store(originalSha256: String, width: Int, height: Int, xy: FloatArray,
              colors: IntArray, descriptors: FloatArray): Boolean {
        require(width <= ANALYSIS_EDGE && height <= ANALYSIS_EDGE && colors.size <= MAX_FEATURES)
        return cache.store(CachedFeatures(identity(originalSha256), width, height, xy, colors, descriptors))
    }

    companion object {
        const val ANALYSIS_EDGE = 1600
        const val MAX_FEATURES = 4000
        const val BYTE_BUDGET = 32L * 1024 * 1024
        val DECODER_IDENTITY = "${com.realityforge.scanner.imaging.AndroidImageDecoder.POLICY_IDENTITY}|analysisEdge=$ANALYSIS_EDGE"
        val STAGE_SETTINGS get() = "${ExtractorSettings().identity(DECODER_IDENTITY)}|cache-rffc-v2"
        // Bump decoder/extractor identities when their pixel/feature contracts change.
        fun identity(originalSha256: String) = FeatureCacheIdentity(
            originalSha256,
            DECODER_IDENTITY,
            ExtractorSettings().identity(DECODER_IDENTITY),
            org.opencv.core.Core.VERSION,
        )
    }
}
