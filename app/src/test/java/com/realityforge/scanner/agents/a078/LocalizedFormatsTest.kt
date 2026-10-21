package com.realityforge.scanner.agents.a078
import com.realityforge.scanner.localization.LocalizedFormats
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
class LocalizedFormatsTest {
    @Test fun localeChangesDecimalAndGroupingSeparators() {
        assertEquals("1,234.5", LocalizedFormats.number(1234.5, Locale.US))
        assertEquals("1.234,5", LocalizedFormats.number(1234.5, Locale.GERMANY))
    }
    @Test fun percentagesUseFractionAndLocale() {
        assertEquals("12.5%", LocalizedFormats.percent(.125, Locale.US))
        assertTrue(LocalizedFormats.percent(.125, Locale.GERMANY).startsWith("12,5"))
    }
    @Test(expected = IllegalArgumentException::class) fun noFabricatedPercentageFromInvalidEvidence() { LocalizedFormats.percent(1.2, Locale.US) }
    @Test(expected = IllegalArgumentException::class) fun nonfiniteMeasurementsRejected() { LocalizedFormats.number(Double.NaN, Locale.US) }
}
