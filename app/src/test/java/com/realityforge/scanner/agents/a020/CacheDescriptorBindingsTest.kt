package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.*
import org.junit.Assert.*
import org.junit.Test

class CacheDescriptorBindingsTest {
    @Test fun reusedBytesPreferNewestReferenceRegardlessOfManifestEnumerationOrder() {
        val old = CacheArtifactDescriptor("features/reused.bin", CacheArtifactCategory.FEATURE_CACHE,
            CacheArtifactState.STALE, "identity-old", 4, 100, "a".repeat(64))
        val current = old.copy(state = CacheArtifactState.REUSABLE, identity = "identity-current", revision = 5)
        assertEquals(current, CacheDescriptorBindings.merge(old, current))
        assertEquals(current, CacheDescriptorBindings.merge(current, old))
        assertNull(CacheDescriptorBindings.merge(old, current.copy(expectedSha256 = "b".repeat(64))))
        assertNull(CacheDescriptorBindings.merge(current, current.copy(identity = "contradictory-current")))
    }
}
