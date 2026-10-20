package com.realityforge.scanner.processing.resources

import android.content.Context
import com.realityforge.scanner.data.storagebudget.ReservationToken
import com.realityforge.scanner.data.storagebudget.StorageBudgetService
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Instantiate once per operation. Call before stages/checkpoints; never interrupt atomic publication. */
internal class ResourceCheckpointGate(
    context: Context,
    private val reservations: StorageBudgetService? = null,
    private val token: ReservationToken? = null,
    private val policy: ResourcePolicy = ResourcePolicy(),
) {
    private val appContext = context.applicationContext

    suspend fun check(plan: WorkloadPlan): ResourcePolicyDecision {
        coroutineContext.ensureActive()
        val snapshot = AndroidResourceObserver.sample(appContext)
        val decision = policy.decide(snapshot, plan)
        if (decision.action == ResourceAction.PAUSE_AT_CHECKPOINT) throw ResourceCheckpointPause(decision)
        if (reservations != null && token != null) {
            val storage = reservations.recheck(token, plan.estimatedTransientStorageBytes)
            if (!storage.admitted) throw ResourceCheckpointPause(decision.copy(
                action = ResourceAction.PAUSE_AT_CHECKPOINT, reasons = setOf(storage.reason)))
        }
        return decision
    }
}

/** Execution owner maps this to durable PAUSED, retaining depth checkpoints and prior results. */
internal class ResourceCheckpointPause(val decision: ResourcePolicyDecision) : IllegalStateException(
    "Processing paused at a checkpoint: ${decision.reasons.joinToString()}",
)
