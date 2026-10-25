package com.realityforge.scanner.viewer.filament

import org.junit.Assert.*
import org.junit.Test

class FrameDemandTest {
    @Test fun staticModelDrawsOnceThenRemainsIdle() {
        val demand = FrameDemand()
        var draws = 0
        repeat(600) { demand.draw { draws++; true } }
        assertEquals(1, draws)
        assertFalse(demand.pending)
    }

    @Test fun changesBeforeNextFrameCoalesceWithoutLosingNewestState() {
        val demand = FrameDemand()
        demand.draw { true }
        var pose = 0
        repeat(50) { pose++; demand.invalidate() }
        var displayed = -1
        demand.draw { displayed = pose; true }
        assertEquals(50, displayed)
        assertFalse(demand.pending)
    }

    @Test fun declinedBeginFrameRetriesUntilSubmitted() {
        val demand = FrameDemand()
        repeat(3) { demand.draw { false }; assertTrue(demand.pending) }
        demand.draw { true }
        assertFalse(demand.pending)
        demand.invalidate() // Resume, resize or replacement surface needs a new submission.
        assertTrue(demand.pending)
    }

    @Test fun invalidationDuringDrawRemainsPending() {
        val demand = FrameDemand()
        demand.draw { demand.invalidate(); true }
        assertTrue(demand.pending)
        demand.draw { true }
        assertFalse(demand.pending)
    }

    @Test fun failedSubmissionPreservesDemandAndPropagatesFailure() {
        val demand = FrameDemand()
        val failure = IllegalStateException("driver failure")
        try { demand.draw { throw failure }; fail("Failure was swallowed") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertTrue(demand.pending)
    }
}
