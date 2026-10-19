package com.realityforge.scanner.engine.pipeline

/**
 * Behavior-preserving bridge used by the current monolithic backend while stage providers are
 * extracted. It records the same typed identities/outcomes that PipelineGraph and Agent 009's
 * durable checkpoint store consume, without pretending an unavailable representation succeeded.
 */
class PipelineAdapterTrace(
    val snapshot: PipelineInputSnapshot,
) {
    private val mutableOutcomes = linkedMapOf<PipelineStage, PipelineStageOutcome>()

    val outcomes: List<PipelineStageOutcome>
        get() = PipelineStage.executionOrder.mapNotNull(mutableOutcomes::get)

    fun completed(
        stage: PipelineStage,
        primaryLocation: String,
        companionLocations: List<String> = emptyList(),
        contentSha256: String? = null,
    ): PipelineStageOutcome.Completed {
        val parent = stage.parent
        require(parent == null || mutableOutcomes[parent] is PipelineStageOutcome.Completed) {
            "Cannot complete $stage without completed $parent."
        }
        val outcome = PipelineStageOutcome.Completed(
            PipelineArtifactReference.forStage(
                snapshot.identity(stage),
                primaryLocation,
                companionLocations,
                contentSha256,
            ),
        )
        putOnce(stage, outcome)
        return outcome
    }

    fun unavailable(
        stage: PipelineStage,
        code: String,
        message: String,
        recoverable: Boolean = true,
    ): PipelineStageOutcome.Unavailable {
        val outcome = PipelineStageOutcome.Unavailable(PipelineFailure(stage, code, message, recoverable))
        putOnce(stage, outcome)
        return outcome
    }

    fun record(outcome: PipelineStageOutcome) {
        putOnce(outcome.stage, outcome)
    }

    fun outcome(stage: PipelineStage): PipelineStageOutcome? = mutableOutcomes[stage]

    private fun putOnce(stage: PipelineStage, outcome: PipelineStageOutcome) {
        check(stage !in mutableOutcomes) { "Pipeline stage $stage already has a terminal outcome." }
        mutableOutcomes[stage] = outcome
    }
}
