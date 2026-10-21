package com.realityforge.scanner.engine.features

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import java.io.Closeable
import java.util.concurrent.CancellationException

enum class FeatureWorkPhase { CACHE_LOOKUP, CACHE_HIT, PREPARING_IMAGE, NATIVE_RUNNING, NATIVE_FINISHED, CACHE_WRITE, READY, CANCELLED, FAILED }

/** Schema 1 timings identify only a bounded photo ordinal, never a filename, path or content hash. */
data class FeatureWorkSnapshot(
    val photoNumber: Int,
    val photoCount: Int,
    val phase: FeatureWorkPhase,
    val cacheHit: Boolean?,
    val elapsedMillis: Long,
    val nativeStartedAfterMillis: Long?,
    val nativeFinishedAfterMillis: Long?,
    val nativeElapsedMillis: Long?,
    val cancellationObserved: Boolean,
    val cancellationObservedToCleanupMillis: Long?,
) {
    val schema: Int get() = 1
    val nativeCallInterruptible: Boolean get() = false
    val completedPhotos: Int get() = if (phase == FeatureWorkPhase.READY) photoNumber else photoNumber - 1
    val stageLabel: String get() = when {
        cancellationObserved && phase == FeatureWorkPhase.NATIVE_RUNNING -> "Stopping after the current OpenCV feature call"
        cancellationObserved && phase !in listOf(FeatureWorkPhase.READY, FeatureWorkPhase.CANCELLED, FeatureWorkPhase.FAILED) -> "Cleaning up cancelled feature extraction"
        else -> when (phase) {
            FeatureWorkPhase.CACHE_LOOKUP -> "Checking saved image features"
            FeatureWorkPhase.CACHE_HIT -> "Reusing verified image features"
            FeatureWorkPhase.PREPARING_IMAGE -> "Preparing photograph for feature extraction"
            FeatureWorkPhase.NATIVE_RUNNING -> "Extracting image features in OpenCV"
            FeatureWorkPhase.NATIVE_FINISHED -> "Finishing extracted image features"
            FeatureWorkPhase.CACHE_WRITE -> "Saving verified image features"
            FeatureWorkPhase.READY -> "Image features ready"
            FeatureWorkPhase.CANCELLED -> "Feature extraction cancelled"
            FeatureWorkPhase.FAILED -> "Feature extraction failed"
        }
    }
}

/** At most nine event callbacks per admitted photograph. Clock is monotonic, never wall time. */
internal class FeatureWorkTiming(
    private val photoNumber: Int,
    private val photoCount: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val changed: (FeatureWorkSnapshot) -> Unit = {},
) {
    private val started = nanoTime()
    private var phase = FeatureWorkPhase.CACHE_LOOKUP
    private var hit: Boolean? = null
    private var nativeStarted: Long? = null
    private var nativeFinished: Long? = null
    private var cancellation: Long? = null
    private var finished: Long? = null
    init { require(photoCount in 2..12 && photoNumber in 1..photoCount) }

    @Synchronized fun begin() { publish(snapshot()) }
    @Synchronized fun cacheResult(cacheHit: Boolean) {
        check(hit == null && finished == null)
        hit = cacheHit
        phase = if (cacheHit) FeatureWorkPhase.CACHE_HIT else FeatureWorkPhase.PREPARING_IMAGE
        publish(snapshot())
    }
    @Synchronized fun nativeStarted() {
        check(hit == false && nativeStarted == null && finished == null)
        nativeStarted = nanoTime(); phase = FeatureWorkPhase.NATIVE_RUNNING; publish(snapshot())
    }
    @Synchronized fun nativeFinished() {
        check(nativeStarted != null && nativeFinished == null && finished == null)
        nativeFinished = nanoTime(); phase = FeatureWorkPhase.NATIVE_FINISHED; publish(snapshot())
    }
    @Synchronized fun cacheWriteStarted() {
        check(nativeFinished != null && finished == null)
        phase = FeatureWorkPhase.CACHE_WRITE; publish(snapshot())
    }
    @Synchronized fun cancellationObserved() {
        if (finished != null || cancellation != null) return
        cancellation = nanoTime(); publish(snapshot())
    }
    @Synchronized fun finish(outcome: FeatureWorkPhase): FeatureWorkSnapshot {
        require(outcome in listOf(FeatureWorkPhase.READY, FeatureWorkPhase.CANCELLED, FeatureWorkPhase.FAILED))
        check(finished == null)
        finished = nanoTime(); phase = outcome
        return snapshot().also(::publish)
    }
    // Diagnostics must not replace cancellation/failure or strand a successfully returned native frame.
    private fun publish(value: FeatureWorkSnapshot) {
        try { changed(value) } catch (_: RuntimeException) { /* best-effort observer only */ }
    }
    @Synchronized fun snapshot(): FeatureWorkSnapshot {
        val now = finished ?: nanoTime()
        fun millis(start: Long, end: Long) = ((end - start).coerceAtLeast(0L) / 1_000_000L)
        return FeatureWorkSnapshot(photoNumber, photoCount, phase, hit, millis(started, now),
            nativeStarted?.let { millis(started, it) }, nativeFinished?.let { millis(started, it) },
            nativeStarted?.let { start -> nativeFinished?.let { millis(start, it) } }, cancellation != null,
            cancellation?.let { start -> finished?.let { millis(start, it) } })
    }
}

/** A body-less child completes immediately on parent cancellation, even while its caller is in JNI.
 * It observes cancellation only; it never interrupts OpenCV or outlives the photo operation.
 */
internal class FeatureCancellationSignal(parent: Job?, timing: FeatureWorkTiming) : Closeable {
    private val signal: CompletableJob = Job(parent)
    private val handler = signal.invokeOnCompletion { cause ->
        if (cause is CancellationException) timing.cancellationObserved()
    }
    override fun close() { handler.dispose(); signal.complete() }
}
