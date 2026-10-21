package com.realityforge.scanner.work.execution

import com.realityforge.scanner.engine.NativeCore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Executes the production cleanup helper with real host JNI tokens, not the Android controller. */
class ReconstructionAttemptCleanupHostTest {
    private fun assertFinished(token:Long) {
        try { NativeCore.pollNativeProgress(token);fail("Finished native lease still registered") }
        catch(expected:IllegalArgumentException) { /* actual JNI registry lookup */ }
    }

    @Test fun cancelledOwnerJoinsHeartbeatBeforeFinishingRealTokensAcrossCapacityCycles() = runBlocking {
        repeat(24) {
            val token=NativeCore.beginReconstruction()
            val entered=CompletableDeferred<Unit>();val stopped=AtomicBoolean();val finished=AtomicBoolean()
            val released=AtomicBoolean();val cleared=AtomicBoolean();val completion=AtomicReference<Throwable?>()
            val expected=CancellationException("cancelled-owner-$it")
            val owner=launch(Dispatchers.Default) {
                val heartbeat=launch(start=CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { delay(10);stopped.set(true) } }
                }
                withReconstructionAttemptCleanup(
                    heartbeat={heartbeat},
                    finishNative={check(stopped.get() && heartbeat.isCompleted);check(NativeCore.finishReconstruction(token));finished.set(true)},
                    releaseReservation={check(finished.get());released.set(true)},
                    clearProgress={cleared.set(true)},closeDiagnostics={},clearRunning={},
                ) { entered.complete(Unit);awaitCancellation() }
            }
            owner.invokeOnCompletion { completion.set(it) }
            try {
                withTimeout(5000) { entered.await() };owner.cancel(expected)
                withTimeout(5000) { owner.join() }
                assertSame(expected,completion.get());assertTrue(finished.get());assertTrue(released.get());assertTrue(cleared.get())
                assertFinished(token)
            } finally {
                withContext(NonCancellable) { owner.cancelAndJoin();NativeCore.finishReconstruction(token) }
            }
        }
    }

    @Test fun storageAndDiagnosticCleanupFailuresPreserveEscapingPrimaryAndRunTail() = runBlocking {
        val token=NativeCore.beginReconstruction();val expected=CancellationException("primary-body-cancel")
        val storage=IllegalStateException("storage-release");val diagnostic=IllegalArgumentException("diagnostic-close")
        val progress=AtomicBoolean();val running=AtomicBoolean()
        try {
            val actual=try {
                withReconstructionAttemptCleanup(heartbeat={null},finishNative={check(NativeCore.finishReconstruction(token))},
                    releaseReservation={throw storage},clearProgress={progress.set(true)},
                    closeDiagnostics={throw diagnostic},clearRunning={running.set(true)}) { throw expected }
                null
            } catch(t:Throwable) { t }
            assertSame(expected,actual);assertSame(storage,expected.suppressed.single())
            assertSame(diagnostic,storage.suppressed.single());assertTrue(progress.get());assertTrue(running.get());assertFinished(token)
        } finally { NativeCore.finishReconstruction(token) }
    }

    @Test fun handledBodyFailureStillReturnsNormallyAndAllCleanupRuns() = runBlocking {
        val token=NativeCore.beginReconstruction();val clear=AtomicBoolean()
        try {
            val result=withReconstructionAttemptCleanup(heartbeat={null},finishNative={check(NativeCore.finishReconstruction(token))},
                releaseReservation={},clearProgress={},closeDiagnostics={},clearRunning={clear.set(true)}) {
                try { throw IllegalArgumentException("already recorded as job failure") }
                catch(expected:IllegalArgumentException) { "handled" }
            }
            assertEquals("handled",result);assertTrue(clear.get());assertFinished(token)
        } finally { NativeCore.finishReconstruction(token) }
    }

    @Test fun escapingHandlerFailureNotEarlierHandledFailureRemainsPrimary() = runBlocking {
        val token=NativeCore.beginReconstruction();val original=IllegalArgumentException("body")
        val handler=IllegalStateException("failure-handler");val cleanup=IllegalArgumentException("cleanup")
        try {
            val actual=try {
                withReconstructionAttemptCleanup(heartbeat={null},finishNative={check(NativeCore.finishReconstruction(token))},
                    releaseReservation={throw cleanup},clearProgress={},closeDiagnostics={},clearRunning={}) {
                    try { throw original } catch(handled:IllegalArgumentException) { throw handler }
                }
                null
            } catch(t:Throwable) { t }
            assertSame(handler,actual);assertSame(cleanup,handler.suppressed.single());assertEquals(0,original.suppressed.size)
            assertFinished(token)
        } finally { NativeCore.finishReconstruction(token) }
    }

    @Test fun cleanupOnlyFailureEscapesAfterRemainingCleanupAndNativeFinish() = runBlocking {
        val token=NativeCore.beginReconstruction();val cleanup=IllegalStateException("release-only")
        val tail=AtomicBoolean()
        try {
            val actual=try {
                withReconstructionAttemptCleanup(heartbeat={null},finishNative={check(NativeCore.finishReconstruction(token))},
                    releaseReservation={throw cleanup},clearProgress={},closeDiagnostics={},clearRunning={tail.set(true)}) { "ok" }
                null
            } catch(t:Throwable) { t }
            assertSame(cleanup,actual);assertTrue(tail.get());assertFinished(token)
        } finally { NativeCore.finishReconstruction(token) }
    }

    @Test fun genuineJoinFailureRetainsLiveHeartbeatTokenAndReservationOwnership() = runBlocking {
        val token=NativeCore.beginReconstruction();val release=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val joinFailure=IllegalStateException("injected-join-failure")
        val touched=AtomicBoolean()
        val actual=launch(start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        }
        val failingJoin=object:Job by actual { override suspend fun join() { throw joinFailure } }
        try {
            val failure=try {
                withReconstructionAttemptCleanup(heartbeat={failingJoin},finishNative={touched.set(true);NativeCore.finishReconstruction(token)},
                    releaseReservation={touched.set(true)},clearProgress={touched.set(true)},
                    closeDiagnostics={touched.set(true)},clearRunning={touched.set(true)}) { "done" }
                null
            } catch(t:Throwable) { t }
            assertSame(joinFailure,failure);assertFalse(touched.get());assertFalse(actual.isCompleted)
            assertEquals(7,NativeCore.pollNativeProgress(token).size)
            assertTrue(joinFailure.suppressed.single().message!!.contains("Heartbeat still owns"))
        } finally {
            withContext(NonCancellable) { release.complete(Unit);actual.cancelAndJoin();NativeCore.finishReconstruction(token) }
        }
    }
}
