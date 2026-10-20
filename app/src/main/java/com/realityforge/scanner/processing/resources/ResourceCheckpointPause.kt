package com.realityforge.scanner.processing.resources

/** Execution maps this policy result to durable PAUSED, retaining checkpoints and prior results. */
internal class ResourceCheckpointPause(val decision: ResourcePolicyDecision) : IllegalStateException(
    "Processing paused at a checkpoint: ${decision.reasons.joinToString()}",
)
