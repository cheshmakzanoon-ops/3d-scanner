package com.realityforge.scanner.work.execution

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** Owns attempt teardown, not job-state policy. Already handled body failures remain handled.
 * A heartbeat must stop before its native token or storage reservation can be released.
 */
internal suspend fun <T> withReconstructionAttemptCleanup(
    heartbeat: () -> Job?,
    finishNative: () -> Unit,
    releaseReservation: suspend () -> Unit,
    clearProgress: () -> Unit,
    closeDiagnostics: () -> Unit,
    clearRunning: () -> Unit,
    block: suspend () -> T,
): T {
    var primary: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        // This surrounds the caller's handlers too: preserve the actual escaping failure.
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            fun record(failure: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = failure
                else if (previous !== failure) previous.addSuppressed(failure)
            }
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { record(failure) }
            }
            val child = heartbeat()
            attempt { child?.cancelAndJoin() }
            if (child?.isCompleted != false) {
                attempt { finishNative() }
                attempt { releaseReservation() }
                attempt { clearProgress() }
                attempt { closeDiagnostics() }
                attempt { clearRunning() }
            } else {
                // A genuine join failure is different from owner cancellation. Retain all
                // ownership handles: the heartbeat may still be using the token/reservation.
                record(IllegalStateException("Heartbeat still owns reconstruction attempt cleanup"))
            }
            cleanupFailure?.let { failure ->
                val original = primary
                if (original == null) throw failure
                if (original !== failure) original.addSuppressed(failure)
            }
        }
    }
}
