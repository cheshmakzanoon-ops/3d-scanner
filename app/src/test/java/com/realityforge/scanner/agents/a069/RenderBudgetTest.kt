package com.realityforge.scanner.agents.a069

import com.realityforge.scanner.viewer.RenderBudget
import org.junit.Assert.*
import org.junit.Test

class RenderBudgetTest {
    @Test fun admissionIncludesImageAndGeometryCpuCopies() {
        val estimate=RenderBudget().admit(1024,4,6,8,8)
        assertEquals(4*48+6*4+8*8*6L,estimate.estimatedGpuBytes)
        assertEquals(1024*4+(4*48+6*4)*2+8*8*12L,estimate.estimatedCpuBytes)
    }
    @Test fun allocationBombsAreRejectedBeforeUpload() {
        for (call in listOf<() -> Unit>(
            { RenderBudget().admit(1024,Long.MAX_VALUE,6,8,8) },
            { RenderBudget().admit(1024,4,6,Int.MAX_VALUE,Int.MAX_VALUE) },
            { RenderBudget(maximumGpuBytes=20).admit(1024,4,6,8,8) },
            { RenderBudget(maximumCpuBytes=20).admit(1024,4,6,8,8) },
            { RenderBudget().admit(1024,4,5,8,8) },
        )) {
            try { call(); fail("Unsafe model admitted") } catch (_: IllegalArgumentException) { }
        }
    }
}
