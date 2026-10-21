package com.realityforge.scanner.engine.matching

import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

enum class PairWorkPhase { LOAD_FIRST, LOAD_SECOND, MATCHING, GEOMETRY, READY, CANCELLED, FAILED }
/** Directions follow canonical matcher order; reverse blocks count selected query ordinals. */
enum class PairMatchDirection { REVERSE, FORWARD }

/** Bounded ordinals/counts and monotonic durations only; no photo identifiers or private paths.
 * Published values are sampled at the latest boundary, not a continuously ticking UI clock.
 * geometryEntered marks the caller boundary before its native wrapper; it does not prove JNI ran.
 * First/second descriptor rows follow candidate load order. FORWARD/REVERSE use canonical matcher
 * order, which may differ; blockTotal is authoritative for that direction, without exposing IDs.
 */
data class PairWorkSnapshot(
    val pairNumber: Int,
    val pairCount: Int,
    val phase: PairWorkPhase,
    val firstCacheHit: Boolean?,
    val secondCacheHit: Boolean?,
    val matchCacheHit: Boolean?,
    val firstDescriptorRows: Int?,
    val secondDescriptorRows: Int?,
    val elapsedMillis: Long,
    val phaseElapsedMillis: Long,
    val loadElapsedMillis: Long,
    val matchElapsedMillis: Long,
    val geometryElapsedMillis: Long,
    val direction: PairMatchDirection?,
    val blockStart: Int?,
    val blockEnd: Int?,
    val blockTotal: Int?,
    val nativeBlockRunning: Boolean,
    val geometryEntered: Boolean,
    val cancellationObserved: Boolean,
    val cancellationObservedToCleanupMillis: Long?,
) {
    val schema: Int get() = 1
    val nativeBlockInterruptible: Boolean get() = false
    val completedPairs: Int get() = if (phase == PairWorkPhase.READY) pairNumber else pairNumber - 1
    val stageLabel: String get() = when {
        cancellationObserved && nativeBlockRunning -> "Stopping after the current OpenCV matching block"
        cancellationObserved && phase !in terminalPairPhases -> "Cleaning up cancelled photo-pair verification"
        else -> when (phase) {
            PairWorkPhase.LOAD_FIRST -> "Loading first photo-pair features"
            PairWorkPhase.LOAD_SECOND -> "Loading second photo-pair features"
            PairWorkPhase.MATCHING -> "Matching reciprocal photo-pair descriptors"
            PairWorkPhase.GEOMETRY -> "Verifying photo-pair geometry"
            PairWorkPhase.READY -> "Photo-pair verification finished"
            PairWorkPhase.CANCELLED -> "Photo-pair verification cancelled"
            PairWorkPhase.FAILED -> "Photo-pair verification failed"
        }
    }
}

private val terminalPairPhases = setOf(PairWorkPhase.READY, PairWorkPhase.CANCELLED, PairWorkPhase.FAILED)

/** Events occur at phase and bounded matcher-block boundaries, never per descriptor comparison. */
internal class PairWorkTiming(
    private val pairNumber: Int,
    private val pairCount: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val changed: (PairWorkSnapshot) -> Unit = {},
) {
    private val started = nanoTime()
    private var currentPhase = PairWorkPhase.LOAD_FIRST
    private var phaseStarted = started
    private var loadNanos = 0L
    private var matchNanos = 0L
    private var geometryNanos = 0L
    private var firstHit: Boolean? = null
    private var secondHit: Boolean? = null
    private var matchesHit: Boolean? = null
    private var firstRows: Int? = null
    private var secondRows: Int? = null
    private var direction: PairMatchDirection? = null
    private var blockStart: Int? = null
    private var blockEnd: Int? = null
    private var blockTotal: Int? = null
    private var blockRunning = false
    private var enteredGeometry = false
    private var cancellation: Long? = null
    private var finished: Long? = null

    init { require(pairCount in 1..66 && pairNumber in 1..pairCount) }

    @Synchronized fun begin() { check(finished == null); publish(snapshot()) }

    @Synchronized fun phase(phase: PairWorkPhase) {
        require(phase !in terminalPairPhases)
        check(finished == null && !blockRunning)
        if (phase != currentPhase) {
            val now = nanoTime()
            settle(now)
            currentPhase = phase
            phaseStarted = now
        }
        if (phase == PairWorkPhase.GEOMETRY) enteredGeometry = true
        publish(snapshot())
    }

    @Synchronized fun frameLoaded(first: Boolean, cacheHit: Boolean?, rows: Int) {
        require(rows in 0..20000)
        check(finished == null)
        if (first) {
            check(firstRows == null)
            firstRows = rows; firstHit = cacheHit
        } else {
            check(secondRows == null)
            secondRows = rows; secondHit = cacheHit
        }
        publish(snapshot())
    }

    @Synchronized fun matchCache(hit: Boolean) {
        check(finished == null && matchesHit == null)
        matchesHit = hit
        publish(snapshot())
    }

    @Synchronized fun block(direction: PairMatchDirection, start: Int, end: Int, total: Int, running: Boolean) {
        require(total in 1..20000 && start in 0 until end && end <= total)
        check(finished == null && currentPhase == PairWorkPhase.MATCHING)
        if (running) {
            check(!blockRunning)
            this.direction = direction; blockStart = start; blockEnd = end; blockTotal = total
        } else {
            check(blockRunning && this.direction == direction && blockStart == start && blockEnd == end && blockTotal == total)
        }
        blockRunning = running
        publish(snapshot())
    }

    @Synchronized fun cancellationObserved() {
        if (finished != null || cancellation != null) return
        cancellation = nanoTime()
        publish(snapshot())
    }

    @Synchronized fun finish(outcome: PairWorkPhase): PairWorkSnapshot {
        require(outcome in terminalPairPhases)
        check(finished == null)
        val now = nanoTime()
        settle(now)
        finished = now; phaseStarted = now; currentPhase = outcome; blockRunning = false
        return snapshot().also(::publish)
    }

    private fun settle(now: Long) {
        val elapsed = (now - phaseStarted).coerceAtLeast(0L)
        when (currentPhase) {
            PairWorkPhase.LOAD_FIRST, PairWorkPhase.LOAD_SECOND -> loadNanos += elapsed
            PairWorkPhase.MATCHING -> matchNanos += elapsed
            PairWorkPhase.GEOMETRY -> geometryNanos += elapsed
            else -> Unit
        }
    }

    private fun publish(value: PairWorkSnapshot) {
        try { changed(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { /* best-effort observer cannot change pipeline outcome */ }
    }

    @Synchronized fun snapshot(): PairWorkSnapshot {
        val now = finished ?: nanoTime()
        val current = if (finished == null) (now - phaseStarted).coerceAtLeast(0L) else 0L
        fun millis(nanos: Long) = nanos.coerceAtLeast(0L) / 1_000_000L
        return PairWorkSnapshot(pairNumber, pairCount, currentPhase, firstHit, secondHit, matchesHit,
            firstRows, secondRows, millis(now - started), millis(current),
            millis(loadNanos + if (currentPhase == PairWorkPhase.LOAD_FIRST || currentPhase == PairWorkPhase.LOAD_SECOND) current else 0L),
            millis(matchNanos + if (currentPhase == PairWorkPhase.MATCHING) current else 0L),
            millis(geometryNanos + if (currentPhase == PairWorkPhase.GEOMETRY) current else 0L),
            direction, blockStart, blockEnd, blockTotal, blockRunning, enteredGeometry, cancellation != null,
            cancellation?.let { start -> finished?.let { millis(it - start) } })
    }
}

/** Observes parent cancellation even while synchronous OpenCV is active; does not interrupt JNI. */
internal class PairCancellationSignal(parent: Job?, timing: PairWorkTiming) : Closeable {
    private val signal: CompletableJob = Job(parent)
    private val handler = signal.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            try { timing.cancellationObserved() }
            catch (_: CancellationException) {
                // Parent cancellation already propagates. Do not wrap an observer's cancellation
                // in CompletionHandlerException and replace the operation's original cause.
            }
        }
    }
    override fun close() { handler.dispose(); signal.complete() }
}

/** Cleanup observations cannot replace an already failing operation; direct observers still throw. */
internal inline fun preservePairFailure(primary: Throwable?, observation: () -> Unit) {
    try { observation() }
    catch (diagnostic: RuntimeException) {
        if (primary == null) throw diagnostic
        if (diagnostic !== primary) primary.addSuppressed(diagnostic)
    }
}
