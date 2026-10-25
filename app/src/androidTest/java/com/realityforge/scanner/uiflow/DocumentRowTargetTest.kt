package com.realityforge.scanner.uiflow

import org.junit.Assert.*
import org.junit.Test

class DocumentRowTargetTest {
    @Test fun observedApi35GridBodyAvoidsItsPreviewButton() {
        val row=DocumentRowBounds(24,232,109,318)
        val preview=DocumentRowBounds(61,232,109,280)
        val directory=DocumentRowBounds(0,24,320,640)
        val point=requireNotNull(documentRowTapPoint(row,directory,listOf(preview)))
        assertTrue(row.contains(point.first,point.second))
        assertFalse(preview.contains(point.first,point.second))
        assertEquals(300.8f,point.second,.01f)
    }
    @Test fun listRowAlsoAvoidsItsTrailingPreviewControl() {
        val point=requireNotNull(documentRowTapPoint(DocumentRowBounds(0,120,320,180),
            DocumentRowBounds(0,24,320,640),listOf(DocumentRowBounds(270,120,320,180))))
        assertTrue(point.first<270)
    }
    @Test fun partiallyClippedRowNeverTapsOutsideDirectory() {
        val directory=DocumentRowBounds(0,24,320,640)
        val point=requireNotNull(documentRowTapPoint(DocumentRowBounds(20,620,300,710),directory,emptyList()))
        assertTrue(directory.contains(point.first,point.second))
    }
    @Test fun fullyCoveredOrOffscreenOrDegenerateItemsCannotBeTapped() {
        val row=DocumentRowBounds(20,100,120,200)
        assertNull(documentRowTapPoint(row,row,listOf(row)))
        assertNull(documentRowTapPoint(row,DocumentRowBounds(0,300,320,600),emptyList()))
        assertNull(documentRowTapPoint(DocumentRowBounds(20,100,20,200),row,emptyList()))
        assertNull(documentRowTapPoint(DocumentRowBounds(20,100,21,200),row,emptyList()))
    }
    @Test fun alternateSafeBodyPointIsUsedWhenBottomCenterIsAChildControl() {
        val row=DocumentRowBounds(0,0,100,100)
        val control=DocumentRowBounds(40,0,100,100)
        val point=requireNotNull(documentRowTapPoint(row,row,listOf(control)))
        assertEquals(25f,point.first,.01f)
        assertFalse(control.contains(point.first,point.second))
    }
}
