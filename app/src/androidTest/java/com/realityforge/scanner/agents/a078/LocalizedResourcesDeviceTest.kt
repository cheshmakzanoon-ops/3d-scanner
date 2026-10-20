package com.realityforge.scanner.agents.a078

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.localization.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizedResourcesDeviceTest {
    @Test fun photoPluralsAndStructuredErrorsResolveWithoutLeakingEvidence() {
        val resolver=UserMessageResolver(InstrumentationRegistry.getInstrumentation().targetContext)
        assertNotEquals(resolver.photoCount(1),resolver.photoCount(2))
        assertTrue(resolver.photoCount(0).contains("0"))
        val message=UserMessage(UserMessage.Code.StorageFull,"technical-private-marker")
        assertFalse(resolver.resolve(message).contains("technical-private-marker"))
        assertTrue(UserMessage.Code.entries.all {resolver.resolve(UserMessage(it)).isNotBlank()})
    }
}
