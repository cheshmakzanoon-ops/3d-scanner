package com.realityforge.scanner.agents.a070

import com.realityforge.scanner.ui.studio.parseEditPoint
import com.realityforge.scanner.studio.editing.EditPoint
import org.junit.Assert.*
import org.junit.Test

class StudioEditingInputTest {
    @Test fun sourceCoordinatesAcceptWhitespaceAndSemicolonWithoutChangingAxes() {
        assertEquals(EditPoint(-1.5,2.0,3.25),parseEditPoint(" -1.5; 2  3.25 "))
    }
    @Test fun missingExtraNonfiniteAndAmbiguousLocaleCoordinatesAreRejected() {
        for (text in listOf("1 2","1 2 3 4","NaN 2 3","1,5 2 3","1 2 Infinity")) {
            try { parseEditPoint(text); fail("Unsafe coordinates accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
