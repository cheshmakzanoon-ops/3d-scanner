package com.realityforge.scanner.agents.a075
import com.realityforge.scanner.ui.onboarding.CaptureGuide
import org.junit.Assert.*
import org.junit.Test
class CaptureGuideTest {
    @Test fun boardMatchesApprovedNineBySixInteriorCornerPattern() {
        assertEquals(9,CaptureGuide.INNER_COLUMNS); assertEquals(6,CaptureGuide.INNER_ROWS)
        var black=0
        for(y in 0..6) for(x in 0..9) { if(CaptureGuide.boardIsBlack(x,y)) black++
            if(x<9) assertNotEquals(CaptureGuide.boardIsBlack(x,y),CaptureGuide.boardIsBlack(x+1,y)) }
        assertEquals(35,black)
    }
    @Test fun savedPageIsClampedAndGuideAlwaysTerminates() {
        assertEquals(0,CaptureGuide.clampPage(-100)); assertEquals(6,CaptureGuide.clampPage(500)); assertEquals(7,CaptureGuide.steps.distinct().size)
        assertFalse(CaptureGuide.BOARD_ASSET.contains("://"))
    }
}
