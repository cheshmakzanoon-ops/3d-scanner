package com.realityforge.scanner.engine.features

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class FeatureWorkTimingTest {
    @Test fun observerFailureCannotBreakNativeOwnershipOrCancellationCleanup() {
        val parent = Job()
        val timing = FeatureWorkTiming(1, 2, changed = { throw IllegalStateException("observer failed") })
        val signal = FeatureCancellationSignal(parent, timing)
        timing.begin(); timing.cacheResult(false); timing.nativeStarted()
        parent.cancel()
        assertTrue(timing.snapshot().cancellationObserved)
        timing.nativeFinished()
        assertEquals(FeatureWorkPhase.CANCELLED, timing.finish(FeatureWorkPhase.CANCELLED).phase)
        signal.close()
        assertTrue(parent.children.none())
    }

    @Test fun hitDoesNotInventNativeDurationAndOnlyReadyCompletesPhoto() {
        var now = 0L
        val events = mutableListOf<FeatureWorkSnapshot>()
        val timing = FeatureWorkTiming(2, 3, { now }, events::add)
        timing.begin(); now = 4_000_000; timing.cacheResult(true)
        assertEquals(1, events.last().completedPhotos)
        now = 7_000_000
        val done = timing.finish(FeatureWorkPhase.READY)
        assertEquals(7L, done.elapsedMillis)
        assertNull(done.nativeElapsedMillis)
        assertNull(done.cancellationObservedToCleanupMillis)
        assertEquals(2, done.completedPhotos)
        assertEquals(3, events.size)
    }

    @Test fun nativeIntervalAndCleanupLatencyAreMeasuredSeparately() {
        var now = 0L
        val timing = FeatureWorkTiming(1, 3, { now })
        timing.begin(); timing.cacheResult(false)
        now = 10_000_000; timing.nativeStarted()
        now = 25_000_000; timing.cancellationObserved()
        assertEquals(FeatureWorkPhase.NATIVE_RUNNING, timing.snapshot().phase)
        assertTrue(timing.snapshot().stageLabel.contains("Stopping"))
        assertNull(timing.snapshot().cancellationObservedToCleanupMillis)
        now = 60_000_000; timing.nativeFinished()
        now = 70_000_000
        val done = timing.finish(FeatureWorkPhase.CANCELLED)
        assertEquals(50L, done.nativeElapsedMillis)
        assertEquals(45L, done.cancellationObservedToCleanupMillis)
        assertEquals(70L, done.elapsedMillis)
        assertFalse(done.nativeCallInterruptible)
        assertEquals(0, done.completedPhotos)
    }

    @Test fun cancellationIsObservedDuringSynchronousWorkAndDoesNotLeakChild() {
        var now = 0L
        val parent = Job()
        val timing = FeatureWorkTiming(1, 2, { now })
        val signal = FeatureCancellationSignal(parent, timing)
        timing.begin(); timing.cacheResult(false); timing.nativeStarted()
        now = 30_000_000; parent.cancel()
        assertTrue(timing.snapshot().cancellationObserved)
        assertEquals(FeatureWorkPhase.NATIVE_RUNNING, timing.snapshot().phase)
        now = 50_000_000; timing.nativeFinished()
        val done = timing.finish(FeatureWorkPhase.CANCELLED)
        signal.close()
        assertEquals(20L, done.cancellationObservedToCleanupMillis)
        assertTrue(parent.children.none())
    }

    @Test fun normalCloseRemovesSignalAndNoLateCancellationRewritesEvidence() {
        val parent = Job()
        val timing = FeatureWorkTiming(1, 2)
        val signal = FeatureCancellationSignal(parent, timing)
        timing.begin(); timing.cacheResult(true)
        val done = timing.finish(FeatureWorkPhase.READY)
        signal.close(); parent.cancel()
        assertEquals(done, timing.snapshot())
        assertTrue(parent.children.none())
    }

    @Test fun metadataIsBoundedAndImpossibleTransitionsFail() {
        assertThrows(IllegalArgumentException::class.java) { FeatureWorkTiming(1, 13) }
        assertThrows(IllegalArgumentException::class.java) { FeatureWorkTiming(0, 2) }
        val timing = FeatureWorkTiming(1, 2)
        timing.cacheResult(true)
        assertThrows(IllegalStateException::class.java) { timing.nativeStarted() }
        assertThrows(IllegalArgumentException::class.java) { timing.finish(FeatureWorkPhase.CACHE_LOOKUP) }
    }

    @Test fun failureAfterNativeReturnStillClosesObservedIntervalWithoutSuccess() {
        var now = 0L
        val timing = FeatureWorkTiming(1, 2, { now })
        timing.cacheResult(false); timing.nativeStarted()
        now = 8_000_000; timing.nativeFinished()
        now = 9_000_000; timing.cacheWriteStarted()
        now = 12_000_000
        val done = timing.finish(FeatureWorkPhase.FAILED)
        assertEquals(8L, done.nativeElapsedMillis)
        assertEquals(12L, done.elapsedMillis)
        assertEquals(0, done.completedPhotos)
        assertFalse(done.cancellationObserved)
    }
}
