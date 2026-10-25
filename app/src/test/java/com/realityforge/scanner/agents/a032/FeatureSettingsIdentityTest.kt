package com.realityforge.scanner.agents.a032

import com.realityforge.scanner.engine.features.ExtractorSettings
import com.realityforge.scanner.engine.features.validatedFeatureText
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.Core
import java.security.MessageDigest

class FeatureSettingsIdentityTest {
    @Test fun unicodeIdentityUsesEncodedByteLengthAndChangesWithSettings() {
        val settings=ExtractorSettings()
        val decoder="upright-\u00e9-\uD83D\uDE00"
        val canonical="rf-sift-v3|rf-rootsift-l1-sqrt-v1\n3500\n1600\n${Core.VERSION}\n${decoder.toByteArray(Charsets.UTF_8).size}:$decoder"
        val expected=MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        assertEquals(expected,settings.identity(decoder))
        val oldCanonical=canonical.replace("rf-sift-v3|rf-rootsift-l1-sqrt-v1","rf-sift-v2")
        val old=MessageDigest.getInstance("SHA-256").digest(oldCanonical.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        assertNotEquals(old,settings.identity(decoder))
        assertNotEquals(settings.identity(decoder),settings.copy(maxFeatures=3499).identity(decoder))
        assertNotEquals(settings.identity(decoder),settings.copy(analysisEdge=1599).identity(decoder))
    }
    @Test fun malformedOrUnboundedMetadataCannotCreateAliasedIdentity() {
        val settings=ExtractorSettings()
        for(text in listOf("","   ","x".repeat(4097),"\u00e9".repeat(2049),"\uD800","\uD801"))
            assertThrows(IllegalArgumentException::class.java){settings.identity(text)}
        assertTrue(settings.identity("\u00e9".repeat(2048)).matches(Regex("[a-f0-9]{64}")))
        assertThrows(IllegalArgumentException::class.java){validatedFeatureText("\uD800",512)}
        assertThrows(IllegalArgumentException::class.java){validatedFeatureText("x".repeat(513),512)}
        assertEquals(512,validatedFeatureText("\u00e9".repeat(256),512).size)
    }
}
