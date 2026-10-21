package com.realityforge.scanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Pure Kotlin tests also executable with host JUnit; no device or app dependency. */
class DiagnosticDeadlineContinuationTest {
    private fun DiagnosticDeadlineContinuation.runForTest(initialWait: () -> Unit,
        continuedWait: (Long) -> Unit, functionalChecks: () -> Unit, event: (String) -> Unit = {}) = runBlocking {
        run({ initialWait() }, { continuedWait(it) }, { functionalChecks() }, event)
    }

    @Test fun suspendingWaitsAndFunctionalChecksStillPreserveOriginal() = runBlocking {
        val original = deadline(); var checked = false
        try {
            DiagnosticDeadlineContinuation.parse("true").run(
                { kotlinx.coroutines.yield(); throw original },
                { kotlinx.coroutines.yield() },
                { kotlinx.coroutines.yield(); checked = true })
            fail("Original deadline must still fail")
        } catch (failure: OperationDeadlineExceeded) { assertSame(original, failure) }
        assertTrue(checked)
    }
    private fun deadline(millis: Long = 180_000L) = OperationDeadlineExceeded(millis, "original deadline")

    @Test fun disabledByDefaultAndFalseNeverContinues() {
        for (argument in listOf(null, "false")) {
            val original = deadline()
            assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
                DiagnosticDeadlineContinuation.parse(argument).runForTest({ throw original },
                    { fail("must not continue") }, { fail("must not check") }, { fail("must not emit") })
            })
        }
    }
    @Test fun parserRejectsAmbiguousOrMalformedOptIn() {
        for (value in listOf("", "TRUE", "False", "1", " true", "true "))
            assertThrows(IllegalArgumentException::class.java) { DiagnosticDeadlineContinuation.parse(value) }
    }
    @Test fun ordinarySuccessRunsChecksWithoutDiagnosticWaitOrMarkers() {
        for (argument in listOf(null, "false", "true")) {
            var checked = false
            DiagnosticDeadlineContinuation.parse(argument).runForTest({}, { fail("no diagnostic wait") },
                { checked = true }, { fail("no marker") })
            assertTrue(checked)
        }
    }
    @Test fun genericAssertionCannotEnableContinuation() {
        val original = AssertionError("not a deadline")
        assertSame(original, assertThrows(AssertionError::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original },
                { fail("must not continue") }, { fail("must not check") })
        })
    }
    @Test fun onlyInitial180SecondTypedDeadlineIsEligible() {
        val original = deadline(30_000L)
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original },
                { fail("must not continue") }, { fail("must not check") })
        })
    }
    @Test fun completedDiagnosticStillReturnsExactOriginalFailure() {
        val original = deadline(); val events = mutableListOf<String>(); var checked = false
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original },
                { assertEquals(600_000L, it) }, { checked = true }, events::add)
        })
        assertTrue(checked)
        assertEquals(listOf("DIAGNOSTIC_DEADLINE_MISSED", "DIAGNOSTIC_FUNCTIONAL_COMPLETION"), events)
        assertTrue(original.suppressed.isEmpty())
    }
    @Test fun laterFailureOrCancellationIsSuppressedAndNeverMarksCompletion() {
        for (later in listOf(AssertionError("geometry failed"), CancellationException("cancelled"), deadline(600_000L))) {
            val original = deadline(); val events = mutableListOf<String>()
            assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
                DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original }, {}, { throw later }, events::add)
            })
            assertSame(later, original.suppressed.single())
            assertEquals(listOf("DIAGNOSTIC_DEADLINE_MISSED"), events)
        }
    }
    @Test fun continuationTimeoutDoesNotRunChecksOrReplaceOriginal() {
        val original = deadline(); val later = deadline(600_000L)
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original }, { throw later }, { fail("not complete") })
        })
        assertSame(later, original.suppressed.single())
    }
    @Test fun diagnosticObserverFailureCannotTurnDeadlineIntoSuccess() {
        val original = deadline(); val later = IllegalStateException("observer")
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original }, {}, {}, { throw later })
        })
        assertSame(later, original.suppressed.single())
    }
    @Test fun surroundingResourceCloseCannotReplaceOriginalDeadline() {
        val original = deadline(); val closeFailure = IllegalStateException("close failed")
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            AutoCloseable { throw closeFailure }.use {
                DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original }, {}, {})
            }
        })
        assertSame(closeFailure, original.suppressed.single())
    }
    @Test fun originalIsNeverSelfSuppressed() {
        val original = deadline()
        assertSame(original, assertThrows(OperationDeadlineExceeded::class.java) {
            DiagnosticDeadlineContinuation.parse("true").runForTest({ throw original }, {}, { throw original })
        })
        assertTrue(original.suppressed.isEmpty())
    }
}
