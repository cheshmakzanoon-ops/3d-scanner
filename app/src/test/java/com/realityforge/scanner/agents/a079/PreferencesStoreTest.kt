package com.realityforge.scanner.agents.a079
import com.realityforge.scanner.preferences.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class PreferencesStoreTest {
    @Test fun persistenceSurvivesStoreRecreationAndPreservesActiveValues() {
        val dir=Files.createTempDirectory("prefs").toFile()
        try { val old=UserPreferences(); val next=old.copy(fallbackFocal35mm=35.0,theme=DisplayTheme.DARK,onboardingCompletedVersion=1)
            PreferencesStore(dir).save(next); assertEquals(next,PreferencesStore(dir).read().preferences); assertEquals(26.0,old.fallbackFocal35mm,0.0)
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptAndFutureFilesAreNotSilentlyOverwritten() {
        val dir=Files.createTempDirectory("prefs").toFile()
        try { val file=java.io.File(dir,"preferences.v1"); file.writeText("schema=99\n")
            val store=PreferencesStore(dir); assertEquals(PreferenceIssue.UNSUPPORTED_VERSION,store.read().issue)
            try {store.save(UserPreferences());fail()} catch(_:IllegalStateException) { }
            assertEquals("schema=99\n",file.readText());store.reset();assertNull(store.read().issue)
            assertTrue(dir.listFiles()!!.any {it.name.startsWith("preferences.rejected.")})
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidNumericAndDuplicateFieldsAreRejected() {
        for(text in listOf("schema=1\nfocal=NaN\ntheme=SYSTEM\nonboarding=0\n","schema=1\nfocal=26\ntheme=SYSTEM\nonboarding=0\nfocal=35\n")) {
            try { PreferenceCodec.decode(text); fail() } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun exactCodecRoundtripIsLocaleIndependent() {
        val value=UserPreferences(35.5,DisplayTheme.LIGHT,1)
        assertEquals(value,PreferenceCodec.decode(PreferenceCodec.encode(value)))
    }
}
