package com.realityforge.scanner.data.deletion

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface DeletionClock {
    fun nowEpochMillis(): Long
}

/**
 * Resumable deletion coordinator. Database adapters own durable tombstones; this service owns
 * ordering: confirm/logically tombstone -> drain active work -> bounded private-file cleanup ->
 * atomic row/reference finalization. Every boundary is safe to execute again after process death.
 */
class DeletionService(
    private val persistence: DeletionPersistence,
    private val leases: DeletionLeaseCoordinator,
    private val cleaner: PrivateFileCleaner,
    private val clock: DeletionClock = DeletionClock { System.currentTimeMillis().coerceAtLeast(0L) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun confirmAndRun(
        request: ConfirmedDeletionRequest,
        budget: CleanupBudget = CleanupBudget(),
    ): DeletionRunResult = resume(persistence.stageConfirmed(request), budget)

    suspend fun retry(
        operationId: String,
        budget: CleanupBudget = CleanupBudget(),
    ): DeletionRunResult {
        requireCanonicalUuid(operationId, "Deletion operation ID")
        val deletion = persistence.load(operationId) ?: return DeletionRunResult.Missing(operationId)
        return resume(deletion, budget)
    }

    /** Runs at most one bounded pass per tombstone so startup recovery itself remains bounded. */
    suspend fun recoverPending(
        limit: Int = 32,
        budget: CleanupBudget = CleanupBudget(),
    ): List<DeletionRunResult> {
        require(limit in 1..1000) { "Recovery batch limit must be between one and 1000." }
        val recoverable = persistence.loadRecoverable(limit)
            .filter { it.phase != DeletionPhase.COMPLETED }
            .distinctBy { it.operationId }
            .take(limit)
        val results = ArrayList<DeletionRunResult>(recoverable.size)
        for (deletion in recoverable) results += resume(deletion, budget)
        return results
    }

    private suspend fun resume(
        deletion: PendingDeletion,
        budget: CleanupBudget,
    ): DeletionRunResult = when (deletion.phase) {
        DeletionPhase.COMPLETED -> DeletionRunResult.Completed(deletion)
        DeletionPhase.FILES_REMOVED,
        DeletionPhase.FINALIZING_DATABASE,
        -> finishDatabase(deletion)

        DeletionPhase.TOMBSTONED,
        DeletionPhase.WAITING_FOR_ACTIVITY,
        DeletionPhase.CLEANUP_PENDING,
        DeletionPhase.CLEANING_FILES,
        DeletionPhase.RETRYABLE_FAILURE,
        -> runCleanupAttempt(deletion, budget)
    }

    private suspend fun runCleanupAttempt(
        deletion: PendingDeletion,
        budget: CleanupBudget,
    ): DeletionRunResult {
        return when (val lease = leases.acquire(deletion.operationId, deletion.scope)) {
            is DeletionLeaseResult.Blocked -> {
                val waiting = persistence.transition(
                    operationId = deletion.operationId,
                    expectedStateVersion = deletion.stateVersion,
                    transition = DeletionTransition.WaitingForActivity(
                        blockers = lease.blockers,
                        atEpochMillis = now(),
                    ),
                ) ?: return classifyLatest(deletion.operationId)
                DeletionRunResult.Waiting(waiting)
            }

            is DeletionLeaseResult.Acquired -> {
                try {
                    val cleaning = persistence.transition(
                        operationId = deletion.operationId,
                        expectedStateVersion = deletion.stateVersion,
                        transition = DeletionTransition.CleaningFiles(now()),
                    ) ?: return classifyLatest(deletion.operationId)

                    val outcome = clean(cleaning.targets, budget)
                    when (outcome) {
                        is CleanupOutcome.MoreWork -> {
                            val pending = persistence.transition(
                                operationId = cleaning.operationId,
                                expectedStateVersion = cleaning.stateVersion,
                                transition = DeletionTransition.CleanupProgress(
                                    delta = outcome.delta,
                                    moreWork = true,
                                    atEpochMillis = now(),
                                ),
                            ) ?: return classifyLatest(cleaning.operationId)
                            DeletionRunResult.MoreWork(pending)
                        }

                        is CleanupOutcome.RetryableFailure -> {
                            val failed = persistence.transition(
                                operationId = cleaning.operationId,
                                expectedStateVersion = cleaning.stateVersion,
                                transition = DeletionTransition.RetryableFailure(
                                    failure = outcome.failure,
                                    delta = outcome.delta,
                                    atEpochMillis = now(),
                                ),
                            ) ?: return classifyLatest(cleaning.operationId)
                            DeletionRunResult.RetryableFailure(failed)
                        }

                        is CleanupOutcome.Complete -> {
                            val filesRemoved = persistence.transition(
                                operationId = cleaning.operationId,
                                expectedStateVersion = cleaning.stateVersion,
                                transition = DeletionTransition.CleanupProgress(
                                    delta = outcome.delta,
                                    moreWork = false,
                                    atEpochMillis = now(),
                                ),
                            ) ?: return classifyLatest(cleaning.operationId)
                            finishDatabase(filesRemoved)
                        }
                    }
                } finally {
                    // Contract: release is idempotent. A failure remains visible to the caller;
                    // the durable tombstone still makes retry/recovery safe.
                    leases.release(deletion.operationId, lease.leaseId)
                }
            }
        }
    }

    private suspend fun clean(
        targets: List<OwnedDeletionTarget>,
        budget: CleanupBudget,
    ): CleanupOutcome {
        return try {
            withContext(ioDispatcher) {
                val cleanupContext = currentCoroutineContext()
                cleaner.clean(targets, budget) { cleanupContext.ensureActive() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CleanupOutcome.RetryableFailure(
                CleanupDelta(),
                DeletionFailure(
                    DeletionFailureCode.INTERNAL_ERROR,
                    "Private-file cleanup stopped unexpectedly and can be retried.",
                ),
            )
        }
    }

    private suspend fun finishDatabase(initial: PendingDeletion): DeletionRunResult {
        var current = initial
        if (current.phase == DeletionPhase.FILES_REMOVED) {
            current = persistence.transition(
                operationId = current.operationId,
                expectedStateVersion = current.stateVersion,
                transition = DeletionTransition.FinalizingDatabase(now()),
            ) ?: return classifyLatest(current.operationId)
        }
        if (current.phase != DeletionPhase.FINALIZING_DATABASE) {
            return classifyLatest(current.operationId)
        }

        val completed = persistence.finalizeDeletion(
            operationId = current.operationId,
            expectedStateVersion = current.stateVersion,
            completedAtEpochMillis = now(),
        ) ?: return classifyLatest(current.operationId)

        return if (completed.phase == DeletionPhase.COMPLETED) {
            DeletionRunResult.Completed(completed)
        } else {
            DeletionRunResult.ConcurrentUpdate(completed)
        }
    }

    private suspend fun classifyLatest(operationId: String): DeletionRunResult {
        val latest = persistence.load(operationId) ?: return DeletionRunResult.Missing(operationId)
        return when (latest.phase) {
            DeletionPhase.COMPLETED -> DeletionRunResult.Completed(latest)
            DeletionPhase.WAITING_FOR_ACTIVITY -> DeletionRunResult.Waiting(latest)
            DeletionPhase.CLEANUP_PENDING -> DeletionRunResult.MoreWork(latest)
            DeletionPhase.RETRYABLE_FAILURE -> DeletionRunResult.RetryableFailure(latest)
            else -> DeletionRunResult.ConcurrentUpdate(latest)
        }
    }

    private fun now(): Long = clock.nowEpochMillis().also {
        require(it >= 0) { "Deletion clock returned a negative timestamp." }
    }
}
