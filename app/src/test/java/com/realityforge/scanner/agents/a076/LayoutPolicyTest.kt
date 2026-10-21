package com.realityforge.scanner.agents.a076
import com.realityforge.scanner.ui.designsystem.Contrast
import com.realityforge.scanner.ui.designsystem.LayoutPolicy
import org.junit.Assert.*
import org.junit.Test
class LayoutPolicyTest {
    @Test fun largeTextReducesColumnsInsteadOfSqueezingControls() {
        assertEquals(1, LayoutPolicy.columns(360f, 1f))
        assertEquals(2, LayoutPolicy.columns(900f, 1f))
        assertEquals(1, LayoutPolicy.columns(900f, 2f))
        assertEquals(2, LayoutPolicy.columns(1440f, 2f))
    }
    @Test fun primaryAndBodyTextMeetNormalTextContrast() {
        for ((text, background) in listOf(0xFFFFFF to 0x006B5E, 0x17211D to 0xF6FBF8,
            0x00382F to 0x73D8C7, 0xE0EAE3 to 0x101A16)) assertTrue(Contrast.ratio(text, background) >= 4.5)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidLayoutInput() { LayoutPolicy.columns(Float.NaN, 1f) }
}
