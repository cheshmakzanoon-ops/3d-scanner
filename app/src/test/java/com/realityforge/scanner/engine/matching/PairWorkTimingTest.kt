package com.realityforge.scanner.engine.matching

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class PairWorkTimingTest {
    @Test fun asyncObserverCancellationDoesNotReplaceParentCancellation() {
        val parent = Job()
        val timing = PairWorkTiming(1, 1, changed = { if (it.cancellationObserved) throw java.util.concurrent.CancellationException("observer") })
        val signal = PairCancellationSignal(parent, timing)
        parent.cancel()
        assertTrue(parent.isCancelled)
        assertTrue(timing.snapshot().cancellationObserved)
        signal.close()
        assertTrue(parent.children.none())
    }
    @Test fun cleanupObservationPreservesOriginalAndAvoidsSelfSuppression() {
        val original = IllegalStateException("original")
        val diagnostic = java.util.concurrent.CancellationException("observer")
        preservePairFailure(original) { throw diagnostic }
        assertSame(diagnostic, original.suppressed.single())
        preservePairFailure(original) { throw original }
        assertEquals(1, original.suppressed.size)
        assertSame(diagnostic, assertThrows(java.util.concurrent.CancellationException::class.java) {
            preservePairFailure(null) { throw diagnostic }
        })
    }

    @Test fun observerCancellationIsNotSwallowed() {
        val cancellation = java.util.concurrent.CancellationException("cancel observer")
        val timing = PairWorkTiming(1, 1, changed = { throw cancellation })
        val caught = assertThrows(java.util.concurrent.CancellationException::class.java) { timing.begin() }
        assertSame(cancellation, caught)
    }

    @Test fun separateLoadMatchAndGeometryDurationsUseMonotonicFakeClock() {
        var now = 0L
        val timing = PairWorkTiming(2, 3, { now })
        timing.begin()
        now = 3_000_000; timing.frameLoaded(true, true, 1536)
        timing.phase(PairWorkPhase.LOAD_SECOND)
        now = 8_000_000; timing.frameLoaded(false, false, 1530)
        timing.phase(PairWorkPhase.MATCHING); timing.matchCache(false)
        now = 18_000_000
        assertEquals(8L, timing.snapshot().loadElapsedMillis)
        assertEquals(10L, timing.snapshot().phaseElapsedMillis)
        assertFalse(timing.snapshot().geometryEntered)
        timing.phase(PairWorkPhase.GEOMETRY)
        now = 25_000_000
        val done = timing.finish(PairWorkPhase.READY)
        assertEquals(25L, done.elapsedMillis)
        assertEquals(8L, done.loadElapsedMillis)
        assertEquals(10L, done.matchElapsedMillis)
        assertEquals(7L, done.geometryElapsedMillis)
        assertEquals(0L, done.phaseElapsedMillis)
        assertEquals(true, done.firstCacheHit)
        assertEquals(false, done.secondCacheHit)
        assertEquals(false, done.matchCacheHit)
        assertEquals(1536, done.firstDescriptorRows)
        assertEquals(1530, done.secondDescriptorRows)
        assertTrue(done.geometryEntered)
        assertEquals(2, done.completedPairs)
    }

    @Test fun matchingBlocksTrackBothDirectionsWithoutInventingCompletedPair() {
        var now = 0L
        val events = mutableListOf<PairWorkSnapshot>()
        val timing = PairWorkTiming(1, 3, { now }, events::add)
        timing.phase(PairWorkPhase.MATCHING)
        timing.block(PairMatchDirection.REVERSE, 0, 256, 400, true)
        assertTrue(events.last().nativeBlockRunning)
        assertEquals(PairMatchDirection.REVERSE, events.last().direction)
        now = 12_000_000
        timing.block(PairMatchDirection.REVERSE, 0, 256, 400, false)
        assertFalse(events.last().nativeBlockRunning)
        timing.block(PairMatchDirection.FORWARD, 256, 400, 400, true)
        assertEquals(256, events.last().blockStart)
        assertEquals(400, events.last().blockEnd)
        assertEquals(400, events.last().blockTotal)
        assertEquals(0, events.last().completedPairs)
        assertFalse(events.last().nativeBlockInterruptible)
        timing.block(PairMatchDirection.FORWARD, 256, 400, 400, false)
    }

    @Test fun cacheHitCanSkipGeometryWithoutFabricatedNativeWork() {
        var now = 0L
        val timing = PairWorkTiming(1, 1, { now })
        timing.begin(); timing.frameLoaded(true, true, 0)
        timing.phase(PairWorkPhase.LOAD_SECOND); timing.frameLoaded(false, true, 1)
        timing.phase(PairWorkPhase.MATCHING); timing.matchCache(true)
        now = 4_000_000
        val done = timing.finish(PairWorkPhase.READY)
        assertFalse(done.geometryEntered)
        assertNull(done.direction)
        assertEquals(0L, done.geometryElapsedMillis)
        assertEquals(true, done.matchCacheHit)
    }

    @Test fun bodylessSignalObservesCancellationDuringBlockingNativeCall() {
        var now = 0L
        val parent = Job()
        val timing = PairWorkTiming(1, 3, { now })
        val signal = PairCancellationSignal(parent, timing)
        timing.phase(PairWorkPhase.MATCHING)
        timing.block(PairMatchDirection.REVERSE, 0, 256, 300, true)
        now = 20_000_000; parent.cancel()
        val pending = timing.snapshot()
        assertTrue(pending.cancellationObserved)
        assertTrue(pending.nativeBlockRunning)
        assertTrue(pending.stageLabel.startsWith("Stopping"))
        assertNull(pending.cancellationObservedToCleanupMillis)
        now = 35_000_000
        timing.block(PairMatchDirection.REVERSE, 0, 256, 300, false)
        assertTrue(timing.snapshot().stageLabel.startsWith("Cleaning"))
        now = 40_000_000
        val done = timing.finish(PairWorkPhase.CANCELLED)
        signal.close()
        assertEquals(20L, done.cancellationObservedToCleanupMillis)
        assertEquals(40L, done.matchElapsedMillis)
        assertFalse(done.nativeBlockRunning)
        assertEquals(0, done.completedPairs)
        assertTrue(parent.children.none())
    }

    @Test fun observerFailureDoesNotEscapeAndNormalCloseDoesNotLeak() {
        val parent = Job()
        val timing = PairWorkTiming(1, 1, changed = { throw IllegalStateException("observer only") })
        val signal = PairCancellationSignal(parent, timing)
        timing.begin(); timing.frameLoaded(true, null, 10)
        timing.phase(PairWorkPhase.MATCHING); timing.matchCache(false)
        timing.block(PairMatchDirection.FORWARD, 0, 10, 10, true)
        timing.block(PairMatchDirection.FORWARD, 0, 10, 10, false)
        val done = timing.finish(PairWorkPhase.FAILED)
        signal.close(); parent.cancel()
        assertEquals(done, timing.snapshot())
        assertFalse(done.cancellationObserved)
        assertTrue(parent.children.none())
    }

    @Test fun failureSettlesCurrentPhaseWithoutClaimingSuccess() {
        var now = 0L
        val timing = PairWorkTiming(3, 3, { now })
        timing.phase(PairWorkPhase.GEOMETRY)
        now = 9_000_000
        val failed = timing.finish(PairWorkPhase.FAILED)
        now = 99_000_000
        assertEquals(failed, timing.snapshot())
        assertEquals(9L, failed.geometryElapsedMillis)
        assertEquals(2, failed.completedPairs)
        assertTrue(failed.geometryEntered)
    }

    @Test fun repeatedPhaseDoesNotResetDurationAndCancellationIsFirstObservation() {
        var now = 0L
        val timing = PairWorkTiming(1, 1, { now })
        timing.phase(PairWorkPhase.MATCHING)
        now = 5_000_000; timing.phase(PairWorkPhase.MATCHING); timing.cancellationObserved()
        now = 8_000_000; timing.cancellationObserved()
        now = 10_000_000
        val done = timing.finish(PairWorkPhase.CANCELLED)
        assertEquals(10L, done.matchElapsedMillis)
        assertEquals(5L, done.cancellationObservedToCleanupMillis)
    }

    @Test fun boundedMetadataAndInvalidBlockBoundariesReject() {
        assertThrows(IllegalArgumentException::class.java) { PairWorkTiming(0, 3) }
        assertThrows(IllegalArgumentException::class.java) { PairWorkTiming(1, 67) }
        assertThrows(IllegalArgumentException::class.java) { PairWorkTiming(2, 1) }
        val timing = PairWorkTiming(66, 66)
        assertThrows(IllegalArgumentException::class.java) { timing.frameLoaded(true, true, 20001) }
        assertThrows(IllegalArgumentException::class.java) { timing.phase(PairWorkPhase.READY) }
        assertThrows(IllegalArgumentException::class.java) { timing.finish(PairWorkPhase.MATCHING) }
        timing.phase(PairWorkPhase.MATCHING)
        assertThrows(IllegalArgumentException::class.java) { timing.block(PairMatchDirection.REVERSE, 4, 4, 10, true) }
        assertThrows(IllegalArgumentException::class.java) { timing.block(PairMatchDirection.REVERSE, 0, 11, 10, true) }
        assertThrows(IllegalStateException::class.java) { timing.block(PairMatchDirection.REVERSE, 0, 2, 10, false) }
        timing.block(PairMatchDirection.REVERSE, 0, 2, 10, true)
        assertThrows(IllegalStateException::class.java) { timing.block(PairMatchDirection.FORWARD, 0, 2, 10, false) }
        assertThrows(IllegalStateException::class.java) { timing.phase(PairWorkPhase.GEOMETRY) }
    }

    @Test fun finishedTimingCannotBeMutatedOrReopened() {
        val timing = PairWorkTiming(1, 1)
        val done = timing.finish(PairWorkPhase.FAILED)
        timing.cancellationObserved()
        assertEquals(done, timing.snapshot())
        assertThrows(IllegalStateException::class.java) { timing.begin() }
        assertThrows(IllegalStateException::class.java) { timing.phase(PairWorkPhase.MATCHING) }
        assertThrows(IllegalStateException::class.java) { timing.matchCache(true) }
        assertThrows(IllegalStateException::class.java) { timing.frameLoaded(true, true, 10) }
        assertThrows(IllegalStateException::class.java) { timing.finish(PairWorkPhase.FAILED) }
    }
}
