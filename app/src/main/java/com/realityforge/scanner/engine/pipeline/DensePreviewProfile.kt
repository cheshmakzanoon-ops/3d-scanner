package com.realityforge.scanner.engine.pipeline

/** Refined compact export is explicit until equal-budget physical qualification is complete. */
enum class DensePreviewProfile {
    Compatibility,
    VerifiedRefinedCompact,
}

/** One canonical settings declaration is shared by stage and native-input identities. */
internal object DenseExecutionPolicy {
    const val SURFACE_ENGINE = "rf-observed-open-mesh-v3-normal-direction"
    const val ATLAS_ENGINE = "rf-atlas-v2-groups-zlib-radiometry3"
    fun depth(profile:DensePreviewProfile) = when(profile) {
        DensePreviewProfile.Compatibility -> "cpu-compatibility-v1|discontinuityAwareConsistency=false|robustSourceAggregation=false"
        DensePreviewProfile.VerifiedRefinedCompact -> "cpu-refined-v3|local6|normal3|strict1|normalConsistency1|angle35|robustSourceAggregation=false"
    }
    fun texture(profile:DensePreviewProfile) = when(profile) {
        DensePreviewProfile.Compatibility -> "legacy-camera-atlas|gutter3|facing.12|depthTolerance.075"
        DensePreviewProfile.VerifiedRefinedCompact -> "compact2048|maxUvStretch=.02|maxPixels4.5M|gutter3|texels128|facing.12|depthTolerance.075"
    }
}
