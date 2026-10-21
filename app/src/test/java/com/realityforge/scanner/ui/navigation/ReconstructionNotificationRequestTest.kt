package com.realityforge.scanner.ui.navigation

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReconstructionNotificationRequestTest {
    @Test fun submissionPrecedesNavigationAndOptionalPrompt() {
        val events = mutableListOf<String>()
        ReconstructionNotificationRequest.submit(
            { events += "submit" }, { events += "inspect" },
            { events += "prompt" }, { events += "notice" },
        )
        assertEquals(listOf("submit", "inspect", "prompt"), events)
    }

    @Test fun grantDenialAndRepeatedRecreatedResultsNeverResubmit() {
        var submissions = 0
        ReconstructionNotificationRequest.submit({ submissions++ }, {}, {}, {})
        repeat(3) {
            assertNull(ReconstructionNotificationRequest.permissionMessage(true))
            assertNotNull(ReconstructionNotificationRequest.permissionMessage(false))
        }
        assertEquals(1, submissions)
    }

    @Test fun dismissalOrMissingResultDoesNotLoseSubmission() {
        var submissions = 0
        ReconstructionNotificationRequest.submit({ submissions++ }, {}, {}, {})
        // No result callback at all (e.g. dialog left open during recreation).
        assertEquals(1, submissions)
    }

    @Test fun optionalLauncherFailureDoesNotRetryOrLoseSubmission() {
        val events = mutableListOf<String>()
        ReconstructionNotificationRequest.submit(
            { events += "submit" }, { events += "inspect" },
            { events += "prompt"; throw IllegalStateException("launcher unavailable") },
            { events += "notice" },
        )
        assertEquals(listOf("submit", "inspect", "prompt", "notice"), events)
    }

    @Test fun launcherCancellationPreservesExactCauseWithoutRetryingSubmission() {
        val cancellation = CancellationException("host cancelled")
        var submissions = 0
        var inspections = 0
        try {
            ReconstructionNotificationRequest.submit(
                { submissions++ }, { inspections++ }, { throw cancellation },
                { fail("cancellation is not a notification failure") },
            )
            fail("must propagate structured cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, submissions)
        assertEquals(1, inspections)
    }

    @Test fun noPromptNeededStillSubmitsOnce() {
        var submissions = 0
        ReconstructionNotificationRequest.submit({ submissions++ }, {}, {}, {})
        assertEquals(1, submissions)
    }

    @Test fun unavailableServiceDoesNotNavigateOrRequestPermission() {
        val notices = mutableListOf<String>()
        ReconstructionNotificationRequest.submit(null,
            { fail("must stay in review") }, { fail("must not prompt") }, { notices += it })
        assertEquals(listOf("Processing is unavailable in this configuration."), notices)
    }

    @Test fun unitReturningServiceDoesNotInventAcceptance() {
        val notices = mutableListOf<String>()
        // Models a service returning without admission (e.g. operation/stale fence).
        ReconstructionNotificationRequest.submit({}, {}, {}, { notices += it })
        assertTrue(notices.isEmpty())
        assertFalse(ReconstructionNotificationRequest.permissionMessage(false)!!.contains("running"))
    }

    @Test fun admissionFailureIsNotMisclassifiedOrRetried() {
        val failure = IllegalStateException("stale selection")
        var attempts = 0
        try {
            ReconstructionNotificationRequest.submit(
                { attempts++; throw failure }, { fail("must not navigate") },
                { fail("must not prompt") }, { fail("must not claim notification failure") },
            )
            fail("must preserve admission failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, attempts)
    }

    @Test fun immediateRouteChangeDropsQueuedSecondTap() {
        // Host route gate, not a replacement for ViewModel/Room concurrency guards.
        var route = "review"
        var submissions = 0
        fun onTap() {
            if (route == "review") ReconstructionNotificationRequest.submit(
                { submissions++ }, { route = "processing" }, {}, {},
            )
        }
        onTap()
        onTap()
        assertEquals(1, submissions)
    }
}
