package com.realityforge.scanner.engine.pipeline

import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A checkpoint becomes reusable only after its artifact was atomically committed. */
data class PipelineCheckpoint(
    val identity: PipelineStageIdentity,
    val artifact: PipelineArtifactReference,
    val committed: Boolean = true,
) {
    init {
        require(artifact.identity == identity) { "Checkpoint artifact identity mismatch." }
    }

    fun isReusable(expected: PipelineStageIdentity): Boolean =
        committed && identity == expected &&
            artifact.identity.projectId == expected.projectId &&
            artifact.identity.sourceRevision == expected.sourceRevision &&
            artifact.representation == expected.stage.representation
}

/** Agent 009 supplies the durable implementation; this contract forbids partial publication. */
interface PipelineCheckpointStore {
    suspend fun latest(projectId: String, stage: PipelineStage): PipelineCheckpoint?
    suspend fun commit(checkpoint: PipelineCheckpoint)
}

data class PipelineProgress(
    val stage: PipelineStage,
    val completed: Int,
    val total: Int,
    val message: String,
) {
    init {
        require(completed >= 0 && total >= 0 && completed <= total) { "Invalid stage progress." }
        require(message.isNotBlank()) { "Progress message is required." }
    }
}

fun interface PipelineProgressSink {
    suspend fun report(progress: PipelineProgress)

    companion object {
        val NONE = PipelineProgressSink { }
    }
}

data class PipelineStageRequest(
    val snapshot: PipelineInputSnapshot,
    val identity: PipelineStageIdentity,
    val upstream: Map<PipelineStage, PipelineArtifactReference>,
    val progress: PipelineProgressSink,
) {
    init {
        require(identity.projectId == snapshot.projectId && identity.sourceRevision == snapshot.revision)
        require(identity == snapshot.identity(identity.stage)) { "Stage request identity is not derived from its input snapshot." }
        val required = PipelineStage.through(identity.stage).dropLast(1)
        require(required.all(upstream::containsKey)) { "Missing upstream artifact for ${identity.stage}." }
    }
}

sealed interface PipelineStageExecution {
    data class Completed(val artifact: PipelineArtifactReference) : PipelineStageExecution
    data class Unavailable(val failure: PipelineFailure) : PipelineStageExecution
    data class Cancelled(val reason: String) : PipelineStageExecution {
        init { require(reason.isNotBlank()) }
    }
}

fun interface PipelineStageRunner {
    suspend fun run(request: PipelineStageRequest): PipelineStageExecution
}

enum class PipelineTraceAction {
    REUSED,
    EXECUTED,
    RECOMPUTED,
    UNAVAILABLE,
    CANCELLED,
}

data class PipelineStageTrace(
    val stage: PipelineStage,
    val expectedIdentity: String,
    val action: PipelineTraceAction,
    val previousIdentity: String? = null,
    val artifactId: String? = null,
    val detail: String? = null,
)

enum class PipelineTerminalState {
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

data class PipelineResult(
    val state: PipelineTerminalState,
    val projectId: String,
    val sourceRevision: Long,
    val requestedStage: PipelineStage,
    val artifacts: Map<PipelineStage, PipelineArtifactReference>,
    val failures: List<PipelineFailure>,
    val trace: List<PipelineStageTrace>,
) {
    val lastCompletedStage: PipelineStage?
        get() = PipelineStage.executionOrder.lastOrNull(artifacts::containsKey)

    val lastValidArtifact: PipelineArtifactReference?
        get() = lastCompletedStage?.let(artifacts::get)

    init {
        require(artifacts.all { (stage, artifact) ->
            artifact.identity.projectId == projectId &&
                artifact.identity.sourceRevision == sourceRevision &&
                artifact.identity.stage == stage
        }) { "Pipeline result contains an artifact from another project/revision/stage." }
        require(state != PipelineTerminalState.COMPLETED || artifacts.containsKey(requestedStage)) {
            "Completed result does not contain the requested representation."
        }
        require(failures.all { it.stage.ordinal <= requestedStage.ordinal })
    }
}

/**
 * Cancellation remains structured: coroutine cancellation is rethrown as a CancellationException,
 * with the exact last-valid typed result attached for durable job/UI owners.
 */
class PipelineCancelledException(
    val partialResult: PipelineResult,
    cause: CancellationException,
) : CancellationException(cause.message ?: "Pipeline cancelled") {
    init { initCause(cause) }
}

/**
 * Executes the typed stage DAG, reusing only exact committed identities. Stale descendants are
 * recomputed naturally because every identity contains its parent's digest.
 */
class PipelineGraph(
    private val checkpoints: PipelineCheckpointStore,
    private val runners: Map<PipelineStage, PipelineStageRunner>,
) {
    init {
        require(PipelineStage.executionOrder.all(runners::containsKey)) { "A runner is required for every pipeline stage." }
    }

    suspend fun run(
        snapshot: PipelineInputSnapshot,
        target: PipelineStage = PipelineStage.TEXTURE,
        progress: PipelineProgressSink = PipelineProgressSink.NONE,
    ): PipelineResult {
        val artifacts = linkedMapOf<PipelineStage, PipelineArtifactReference>()
        val failures = mutableListOf<PipelineFailure>()
        val trace = mutableListOf<PipelineStageTrace>()

        for (stage in PipelineStage.through(target)) {
            currentCoroutineContext().ensureActive()
            val expected = snapshot.identity(stage)
            val previous = checkpoints.latest(snapshot.projectId, stage)
            if (previous?.isReusable(expected) == true) {
                artifacts[stage] = previous.artifact
                trace += PipelineStageTrace(
                    stage,
                    expected.digest,
                    PipelineTraceAction.REUSED,
                    previousIdentity = previous.identity.digest,
                    artifactId = previous.artifact.artifactId,
                )
                continue
            }

            val action = if (previous == null) PipelineTraceAction.EXECUTED else PipelineTraceAction.RECOMPUTED
            val request = PipelineStageRequest(snapshot, expected, artifacts.toMap(), progress)
            val execution = try {
                runners.getValue(stage).run(request)
            } catch (cancelled: CancellationException) {
                trace += PipelineStageTrace(
                    stage,
                    expected.digest,
                    PipelineTraceAction.CANCELLED,
                    previousIdentity = previous?.identity?.digest,
                    detail = cancelled.message,
                )
                throw PipelineCancelledException(
                    result(snapshot, target, PipelineTerminalState.CANCELLED, artifacts, failures, trace),
                    cancelled,
                )
            }
            currentCoroutineContext().ensureActive()

            when (execution) {
                is PipelineStageExecution.Completed -> {
                    val artifact = execution.artifact
                    require(artifact.identity == expected) {
                        "${stage.name} runner returned an artifact for a different input/settings identity."
                    }
                    require(artifact.representation == stage.representation) {
                        "${stage.name} runner returned ${artifact.representation}."
                    }
                    // Persist only the validated completed artifact; a store implementation must
                    // publish atomically and must never expose an in-progress checkpoint.
                    checkpoints.commit(PipelineCheckpoint(expected, artifact, committed = true))
                    artifacts[stage] = artifact
                    trace += PipelineStageTrace(
                        stage,
                        expected.digest,
                        action,
                        previousIdentity = previous?.identity?.digest,
                        artifactId = artifact.artifactId,
                    )
                }
                is PipelineStageExecution.Unavailable -> {
                    require(execution.failure.stage == stage) { "Failure attributed to the wrong stage." }
                    failures += execution.failure
                    trace += PipelineStageTrace(
                        stage,
                        expected.digest,
                        PipelineTraceAction.UNAVAILABLE,
                        previousIdentity = previous?.identity?.digest,
                        detail = execution.failure.message,
                    )
                    return result(
                        snapshot,
                        target,
                        if (artifacts.isEmpty()) PipelineTerminalState.FAILED else PipelineTerminalState.PARTIAL,
                        artifacts,
                        failures,
                        trace,
                    )
                }
                is PipelineStageExecution.Cancelled -> {
                    trace += PipelineStageTrace(
                        stage,
                        expected.digest,
                        PipelineTraceAction.CANCELLED,
                        previousIdentity = previous?.identity?.digest,
                        detail = execution.reason,
                    )
                    return result(
                        snapshot,
                        target,
                        PipelineTerminalState.CANCELLED,
                        artifacts,
                        failures,
                        trace,
                    )
                }
            }
        }
        return result(snapshot, target, PipelineTerminalState.COMPLETED, artifacts, failures, trace)
    }

    private fun result(
        snapshot: PipelineInputSnapshot,
        target: PipelineStage,
        state: PipelineTerminalState,
        artifacts: Map<PipelineStage, PipelineArtifactReference>,
        failures: List<PipelineFailure>,
        trace: List<PipelineStageTrace>,
    ) = PipelineResult(
        state = state,
        projectId = snapshot.projectId,
        sourceRevision = snapshot.revision,
        requestedStage = target,
        artifacts = artifacts.toMap(),
        failures = failures.toList(),
        trace = trace.toList(),
    )
}
