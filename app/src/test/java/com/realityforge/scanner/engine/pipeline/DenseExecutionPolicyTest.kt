package com.realityforge.scanner.engine.pipeline

import org.junit.Assert.*
import org.junit.Test

class DenseExecutionPolicyTest {
    @Test fun refinedMustReachCurrentDepthIdentityBeforeAnyReuse() {
        val profile = DensePreviewProfile.VerifiedRefinedCompact
        assertFalse(DenseExecutionPolicy.supportsCompleteTextureReuse(profile))
        assertEquals("cpu-refined-v5|local6|normal3|strict1|normalConsistency1|angle35|robustSourceAggregation=false", DenseExecutionPolicy.depth(profile))
    }
    @Test fun compatibilityRetainsItsExactIdentityAndCompleteReusePolicy() {
        val profile = DensePreviewProfile.Compatibility
        assertTrue(DenseExecutionPolicy.supportsCompleteTextureReuse(profile))
        assertEquals("cpu-compatibility-v1|discontinuityAwareConsistency=false|robustSourceAggregation=false", DenseExecutionPolicy.depth(profile))
    }
}
