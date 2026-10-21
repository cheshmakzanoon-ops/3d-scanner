package com.realityforge.scanner.engine.diagnostics

import java.util.ArrayDeque

/** Progress is meaningful only inside one exact job/attempt/stage scope. */
internal data class StageProgress(
    val scope: DiagnosticScope,
    val stage: ScanStage,
    val completed: Long,
    val total: Long?,
    val unit: ProgressUnit,
    val sequence: Long,
    val reusedCheckpoint: Boolean = false,
) {
    init {
        require(completed >= 0) { "Completed progress must be nonnegative." }
        total?.let {
            require(it >= 0) { "Progress total must be nonnegative." }
            require(completed <= it) { "Completed progress cannot exceed its total." }
        }
        require(sequence >= 0) { "Progress sequence must be nonnegative." }
    }

    /** Never combine this fraction across stages or jobs. */
    fun stageFractionOrNull(): Double? = total?.takeIf { it > 0 }?.let { completed.toDouble() / it }
}

/**
 * Rejects stale/cross-job updates and preserves only a bounded recent history.
 * A retry or checkpoint recomputation must activate a new attempt before counters reset.
 */
internal class StageProgressLedger(
    initialScope: DiagnosticScope,
    private val maxEvents: Int = MAX_DIAGNOSTIC_EVENTS,
) {
    private var activeScope = initialScope
    private val latestByStage = LinkedHashMap<ScanStage, StageProgress>()
    private val retained = ArrayDeque<StageProgress>()

    init { require(maxEvents in 1..MAX_DIAGNOSTIC_EVENTS) }

    fun activate(scope: DiagnosticScope) {
        if (scope == activeScope) return
        activeScope = scope
        latestByStage.clear()
    }

    fun record(progress: StageProgress) {
        require(progress.scope == activeScope) {
            "Progress belongs to a stale or different job attempt."
        }
        val previous = latestByStage[progress.stage]
        if (previous != null) {
            require(progress.sequence > previous.sequence) { "Progress sequence must increase within a stage." }
            require(progress.completed >= previous.completed) { "Progress must be monotonic within a stage." }
            require(progress.unit == previous.unit) { "Progress unit cannot change within a stage." }
            when {
                previous.total != null -> require(progress.total == previous.total) {
                    "A measured stage total cannot change after publication."
                }
                progress.total != null -> require(progress.completed <= progress.total)
            }
        }
        latestByStage[progress.stage] = progress
        retained.addLast(progress)
        while (retained.size > maxEvents) retained.removeFirst()
    }

    fun latest(stage: ScanStage): StageProgress? = latestByStage[stage]

    fun snapshot(): List<StageProgress> = retained.toList()
}

internal data class DiagnosticEvent(
    val scope: DiagnosticScope,
    val stage: ScanStage,
    val outcome: StageOutcome,
    val sequence: Long,
    val progress: StageProgress? = null,
    val failure: ScanFailure? = null,
) {
    init {
        require(sequence >= 0)
        progress?.let {
            require(it.scope == scope && it.stage == stage) { "Event progress identity does not match." }
            require(it.sequence == sequence) { "Event progress sequence does not match." }
        }
        failure?.let {
            require(it.stage == stage) { "Event failure stage does not match." }
            require(it.outcome == outcome) { "Event failure outcome does not match." }
            require(outcome != StageOutcome.SUCCEEDED) { "A successful event cannot carry a failure." }
        }
    }
}

internal class BoundedDiagnosticEventLog(
    private val maxEvents: Int = MAX_DIAGNOSTIC_EVENTS,
) {
    private val retained = ArrayDeque<DiagnosticEvent>()

    init { require(maxEvents in 1..MAX_DIAGNOSTIC_EVENTS) }

    fun append(event: DiagnosticEvent) {
        retained.addLast(event)
        while (retained.size > maxEvents) retained.removeFirst()
    }

    fun snapshot(): List<DiagnosticEvent> = retained.toList()
}
