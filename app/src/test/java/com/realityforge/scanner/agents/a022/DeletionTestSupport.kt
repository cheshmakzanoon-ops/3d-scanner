package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.data.deletion.ConfirmedDeletionRequest
import com.realityforge.scanner.data.deletion.DeletionBlocker
import com.realityforge.scanner.data.deletion.DeletionLeaseCoordinator
import com.realityforge.scanner.data.deletion.DeletionLeaseResult
import com.realityforge.scanner.data.deletion.DeletionPersistence
import com.realityforge.scanner.data.deletion.DeletionPhase
import com.realityforge.scanner.data.deletion.DeletionScope
import com.realityforge.scanner.data.deletion.DeletionStateReducer
import com.realityforge.scanner.data.deletion.DeletionTransition
import com.realityforge.scanner.data.deletion.OwnedDeletionTarget
import com.realityforge.scanner.data.deletion.PendingDeletion

internal class SimulatedProcessDeath(message: String) : RuntimeException(message)

internal class FakeDeletionPersistence(
    private val targetsFor: (ConfirmedDeletionRequest) -> List<OwnedDeletionTarget>,
    private val retainedFor: (ConfirmedDeletionRequest) -> Set<String> = { emptySet() },
) : DeletionPersistence {
    val rows = linkedMapOf<String, PendingDeletion>()
    val events = mutableListOf<String>()
    val finalizedScopes = mutableListOf<DeletionScope>()
    var afterStage: (() -> Unit)? = null
    var beforeTransition: ((DeletionTransition) -> Unit)? = null
    var afterTransition: ((DeletionTransition, PendingDeletion) -> Unit)? = null
    var beforeFinalize: (() -> Unit)? = null
    var afterFinalize: ((PendingDeletion) -> Unit)? = null

    override suspend fun stageConfirmed(request: ConfirmedDeletionRequest): PendingDeletion {
        rows[request.operationId]?.let { existing ->
            require(existing.scope == request.scope && existing.expectedRevision == request.expectedRevision)
            events += "stage:idempotent"
            return existing
        }
        val staged = PendingDeletion(
            operationId = request.operationId,
            scope = request.scope,
            expectedRevision = request.expectedRevision,
            confirmedAtEpochMillis = request.confirmedAtEpochMillis,
            phase = DeletionPhase.TOMBSTONED,
            stateVersion = 0,
            targets = targetsFor(request).toList(),
            retainedReferenceIds = retainedFor(request).toSet(),
            updatedAtEpochMillis = request.confirmedAtEpochMillis,
        )
        rows[request.operationId] = staged
        events += "stage"
        afterStage?.also { afterStage = null }?.invoke()
        return staged
    }

    override suspend fun load(operationId: String): PendingDeletion? = rows[operationId]

    override suspend fun loadRecoverable(limit: Int): List<PendingDeletion> = rows.values
        .filter { it.phase != DeletionPhase.COMPLETED }
        .sortedWith(compareBy<PendingDeletion> { it.updatedAtEpochMillis }.thenBy { it.operationId })
        .take(limit)

    override suspend fun transition(
        operationId: String,
        expectedStateVersion: Long,
        transition: DeletionTransition,
    ): PendingDeletion? {
        val current = rows[operationId] ?: return null
        if (current.stateVersion != expectedStateVersion) return null
        beforeTransition?.invoke(transition)
        val next = DeletionStateReducer.apply(current, transition)
        rows[operationId] = next
        events += "transition:${next.phase}"
        afterTransition?.invoke(transition, next)
        return next
    }

    override suspend fun finalizeDeletion(
        operationId: String,
        expectedStateVersion: Long,
        completedAtEpochMillis: Long,
    ): PendingDeletion? {
        val current = rows[operationId] ?: return null
        if (current.stateVersion != expectedStateVersion) return null
        beforeFinalize?.invoke()
        val completed = DeletionStateReducer.complete(current, completedAtEpochMillis)
        rows[operationId] = completed
        finalizedScopes += current.scope
        events += "finalize"
        afterFinalize?.invoke(completed)
        return completed
    }
}

internal class FakeDeletionLeases : DeletionLeaseCoordinator {
    var blockers: Set<DeletionBlocker> = emptySet()
    var acquisitions = 0
    var releases = 0
    val events = mutableListOf<String>()

    override suspend fun acquire(operationId: String, scope: DeletionScope): DeletionLeaseResult {
        acquisitions++
        events += "acquire"
        return if (blockers.isEmpty()) {
            DeletionLeaseResult.Acquired("lease-$operationId")
        } else {
            DeletionLeaseResult.Blocked(blockers)
        }
    }

    override suspend fun release(operationId: String, leaseId: String) {
        require(leaseId == "lease-$operationId")
        releases++
        events += "release"
    }
}
