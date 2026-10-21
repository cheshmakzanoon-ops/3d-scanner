package com.realityforge.scanner.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PresentationReadObserverTest {
    private val a = PresentationReadIdentity("A", 1, "result-A", 1, "SPARSE_READY")

    @Test fun revisionBoundaryClearsBeforeOldReadFinishesCancelling() = runBlocking {
        withTimeout(5_000) {
            val identities = MutableStateFlow<PresentationReadIdentity?>(a)
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val bStarted = CompletableDeferred<Unit>()
            var compatibilityPreview: String? = "old-A"
            var typedPreview: String? = "Ready-A"
            var calibration: String? = "Calibrated-A"
            val b = a.copy(revision = 2, resultKey = null, resultRevision = null)
            val job = launch {
                identities.observePresentationReads(onBoundary = {
                    compatibilityPreview = null; typedPreview = null; calibration = null
                    if (it == b) changed.complete(Unit)
                }) { identity ->
                    if (identity == a) {
                        entered.complete(Unit)
                        try { CompletableDeferred<Unit>().await() }
                        finally { withContext(NonCancellable) { cancelling.complete(Unit); releaseCleanup.await() } }
                    } else {
                        bStarted.complete(Unit)
                        CompletableDeferred<Unit>().await() // New revision IO is still pending.
                    }
                }
            }
            try {
                entered.await()
                // Represent the previous successfully published presentation before invalidation.
                compatibilityPreview = "old-A"; typedPreview = "Ready-A"; calibration = "Calibrated-A"
                identities.value = b
                changed.await(); cancelling.await()
                assertNull(compatibilityPreview); assertNull(typedPreview); assertNull(calibration)
                assertFalse(bStarted.isCompleted)
                releaseCleanup.complete(Unit); bStarted.await()
                assertNull(compatibilityPreview)
            } finally { releaseCleanup.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun missingProjectIsObservedClearedAndCancelsPendingRead() = runBlocking {
        withTimeout(5_000) {
            val identities = MutableStateFlow<PresentationReadIdentity?>(a)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val missing = CompletableDeferred<Unit>()
            var visible: String? = "old-A"
            var calibration: String? = "Calibrated-A"
            val job = launch {
                identities.observePresentationReads(onBoundary = {
                    visible = null; calibration = null
                    if (it == null) missing.complete(Unit)
                }) {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await() } finally { cancelled.complete(Unit) }
                }
            }
            try {
                entered.await(); visible = "old-A"; calibration = "Calibrated-A"
                identities.value = null
                missing.await(); cancelled.await()
                assertNull(visible); assertNull(calibration)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun initiallyMissingRestoredProjectClearsWithoutStartingRead() = runBlocking {
        withTimeout(5_000) {
            val identities = MutableStateFlow<PresentationReadIdentity?>(null)
            val missing = CompletableDeferred<Unit>()
            var reads = 0
            val job = launch {
                identities.observePresentationReads(onBoundary = { assertNull(it); missing.complete(Unit) }) { reads++ }
            }
            try { missing.await(); assertEquals(0, reads) } finally { job.cancelAndJoin() }
        }
    }
    @Test fun completionBeforeOperationFinishStillRestartsUnchangedIdentityRead() = runBlocking {
        withTimeout(5_000) {
            val identities = MutableStateFlow<PresentationReadIdentity?>(a)
            var operationActive = true
            var observer: kotlinx.coroutines.Job? = null
            var reads = 0
            val read = CompletableDeferred<Unit>()
            fun refresh(id: String) {
                if (operationActive) return // Same admission guard as the ViewModel.
                assertEquals("A", id)
                observer?.cancel()
                observer = launch {
                    identities.observePresentationReads(onBoundary = {}) { reads++; read.complete(Unit) }
                }
            }
            // Completed-job event arrives before admission finally releases its direct operation.
            refresh("A")
            assertEquals(0, reads)
            operationActive = false
            refreshPresentationAfterOperation({ "A" }, ::refresh).join()
            try { read.await(); assertEquals(1, reads) } finally { observer?.cancelAndJoin() }
        }
    }

    @Test fun finishHandoffUsesCurrentSelectionNotOperationOriginal() = runBlocking {
        var selected: String? = "A"
        val refreshed = mutableListOf<String>()
        val handoff = refreshPresentationAfterOperation({ selected }, refreshed::add)
        selected = "B" // Navigation wins before the owner dispatcher executes the handoff.
        handoff.join()
        assertEquals(listOf("B"), refreshed)
    }

    @Test fun finishAfterSelectionClearedDoesNotRestartOldProject() = runBlocking {
        var selected: String? = "A"
        val refreshed = mutableListOf<String>()
        val handoff = refreshPresentationAfterOperation({ selected }, refreshed::add)
        selected = null
        handoff.join()
        assertTrue(refreshed.isEmpty())
    }

}
