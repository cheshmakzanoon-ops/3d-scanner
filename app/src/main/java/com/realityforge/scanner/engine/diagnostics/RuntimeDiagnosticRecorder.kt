package com.realityforge.scanner.engine.diagnostics

import java.util.ArrayDeque
import java.util.Collections

/** A measured, immutable view of one exact running job attempt. No display strings are parsed. */
internal class RuntimeDiagnosticSnapshot internal constructor(
    val scope: DiagnosticScope,
    progress: List<StageProgress>,
    events: List<DiagnosticEvent>,
    failures: List<ScanFailure>,
) {
    val progress: List<StageProgress> = immutable(progress)
    val events: List<DiagnosticEvent> = immutable(events)
    val failures: List<ScanFailure> = immutable(failures)

    init {
        require(progress.size + events.size <= MAX_DIAGNOSTIC_EVENTS)
        require(failures.size <= MAX_DIAGNOSTIC_FAILURES)
        val ledger = StageProgressLedger(scope)
        var previousProgress = -1L
        progress.forEach {
            require(it.sequence > previousProgress) { "Runtime progress was reordered." }
            previousProgress = it.sequence
            ledger.record(it)
        }
        var previousEvent = -1L
        events.forEach {
            require(it.scope == scope) { "Runtime events belong to another job attempt." }
            require(it.sequence > previousEvent) { "Runtime outcomes were reordered." }
            previousEvent = it.sequence
        }
        require((progress.map { it.sequence } + events.map { it.sequence }).distinct().size ==
            progress.size + events.size) { "Runtime event sequences must be unique." }
    }

    private companion object {
        fun <T> immutable(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

/**
 * The execution owner creates one recorder for its persisted (job, attempt, revision), attaches it
 * to that pipeline invocation, and closes it before retiring the invocation. A retry gets a new
 * recorder; a callback cannot reactivate an old attempt or reset another stage's counters.
 *
 * Progress and terminal outcomes are deliberately separate. Reaching a numeric total does not
 * invent successful publication, and accepting a reused checkpoint does not invent measured work.
 */
internal class RuntimeDiagnosticRecorder(
    val scope: DiagnosticScope,
    private val maxEvents: Int = MAX_DIAGNOSTIC_EVENTS,
) : AutoCloseable {
    private sealed interface Record {
        data class Progress(val value: StageProgress) : Record
        data class Outcome(val value: DiagnosticEvent) : Record
    }

    private val ledger = StageProgressLedger(scope, maxEvents)
    private val history = ArrayDeque<Record>()
    private val failures = ArrayDeque<ScanFailure>()
    private var nextSequence = 0L
    private var closed = false

    init { require(maxEvents in 1..MAX_DIAGNOSTIC_EVENTS) }

    @Synchronized
    fun progress(
        stage: ScanStage,
        completed: Long,
        total: Long?,
        unit: ProgressUnit,
        reusedCheckpoint: Boolean = false,
    ): StageProgress {
        checkOpen()
        val value = StageProgress(scope, stage, completed, total, unit, nextSequence, reusedCheckpoint)
        // Validation happens before either the sequence or history is changed.
        ledger.record(value)
        nextSequence = Math.addExact(nextSequence, 1)
        append(Record.Progress(value))
        return value
    }

    @Synchronized
    fun outcome(stage: ScanStage, outcome: StageOutcome, failure: ScanFailure? = null): DiagnosticEvent {
        checkOpen()
        require(failure == null || failure.outcome == outcome) { "Failure and stage outcomes differ." }
        val event = DiagnosticEvent(scope, stage, outcome, nextSequence, failure = failure)
        nextSequence = Math.addExact(nextSequence, 1)
        append(Record.Outcome(event))
        failure?.let {
            failures.addLast(it)
            while (failures.size > MAX_DIAGNOSTIC_FAILURES) failures.removeFirst()
        }
        return event
    }

    @Synchronized
    fun recordFailure(stage: ScanStage, throwable: Throwable): ScanFailure {
        val failure = OperationalFailureMapper.from(stage, throwable)
        outcome(failure.stage, failure.outcome, failure)
        return failure
    }

    @Synchronized
    fun snapshot(): RuntimeDiagnosticSnapshot = RuntimeDiagnosticSnapshot(
        scope,
        history.mapNotNull { (it as? Record.Progress)?.value },
        history.mapNotNull { (it as? Record.Outcome)?.value },
        failures.toList(),
    )

    /** Useful for typed live presentation; it never consults another job or the UI selection. */
    @Synchronized
    fun latest(stage: ScanStage): StageProgress? = ledger.latest(stage)

    @Synchronized
    override fun close() { closed = true }

    private fun append(record: Record) {
        history.addLast(record)
        while (history.size > maxEvents) history.removeFirst()
    }

    private fun checkOpen() {
        check(!closed) { "Diagnostic callback belongs to a retired job attempt." }
        check(nextSequence < Long.MAX_VALUE) { "Diagnostic sequence exhausted." }
    }
}
