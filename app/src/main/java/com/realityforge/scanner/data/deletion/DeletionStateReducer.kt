package com.realityforge.scanner.data.deletion

/**
 * Pure state reducer shared by database adapters and deterministic crash-boundary tests.
 * It rejects impossible phase transitions instead of allowing a tombstone to skip cleanup.
 */
object DeletionStateReducer {
    fun apply(current: PendingDeletion, transition: DeletionTransition): PendingDeletion {
        require(current.phase != DeletionPhase.COMPLETED) { "Completed deletion is immutable." }
        val at = maxOf(current.updatedAtEpochMillis, transition.atEpochMillis)
        val nextVersion = Math.addExact(current.stateVersion, 1L)

        return when (transition) {
            is DeletionTransition.WaitingForActivity -> {
                require(current.phase.canAcquireOrWait()) { "Cannot wait from ${current.phase}." }
                current.copy(
                    phase = DeletionPhase.WAITING_FOR_ACTIVITY,
                    stateVersion = nextVersion,
                    blockers = transition.blockers,
                    failure = null,
                    updatedAtEpochMillis = at,
                )
            }

            is DeletionTransition.CleaningFiles -> {
                require(current.phase.canAcquireOrWait()) { "Cannot start cleanup from ${current.phase}." }
                current.copy(
                    phase = DeletionPhase.CLEANING_FILES,
                    stateVersion = nextVersion,
                    attempts = Math.addExact(current.attempts, 1),
                    blockers = emptySet(),
                    failure = null,
                    updatedAtEpochMillis = at,
                )
            }

            is DeletionTransition.CleanupProgress -> {
                require(current.phase == DeletionPhase.CLEANING_FILES) {
                    "Cleanup progress requires an active cleanup attempt."
                }
                val aggregate = current.progress() + transition.delta
                current.copy(
                    phase = if (transition.moreWork) {
                        DeletionPhase.CLEANUP_PENDING
                    } else {
                        DeletionPhase.FILES_REMOVED
                    },
                    stateVersion = nextVersion,
                    entriesRemoved = aggregate.entriesRemoved,
                    bytesReclaimed = aggregate.bytesReclaimed,
                    entriesVisited = aggregate.entriesVisited,
                    lastRelativePath = aggregate.lastRelativePath,
                    blockers = emptySet(),
                    failure = null,
                    updatedAtEpochMillis = at,
                )
            }

            is DeletionTransition.RetryableFailure -> {
                require(current.phase == DeletionPhase.CLEANING_FILES) {
                    "Cleanup failure requires an active cleanup attempt."
                }
                val aggregate = current.progress() + transition.delta
                current.copy(
                    phase = DeletionPhase.RETRYABLE_FAILURE,
                    stateVersion = nextVersion,
                    entriesRemoved = aggregate.entriesRemoved,
                    bytesReclaimed = aggregate.bytesReclaimed,
                    entriesVisited = aggregate.entriesVisited,
                    lastRelativePath = aggregate.lastRelativePath,
                    blockers = emptySet(),
                    failure = transition.failure,
                    updatedAtEpochMillis = at,
                )
            }

            is DeletionTransition.FinalizingDatabase -> {
                require(current.phase == DeletionPhase.FILES_REMOVED ||
                    current.phase == DeletionPhase.FINALIZING_DATABASE
                ) { "Database finalization requires completed private-file cleanup." }
                current.copy(
                    phase = DeletionPhase.FINALIZING_DATABASE,
                    stateVersion = nextVersion,
                    blockers = emptySet(),
                    failure = null,
                    updatedAtEpochMillis = at,
                )
            }
        }
    }

    fun complete(current: PendingDeletion, completedAtEpochMillis: Long): PendingDeletion {
        require(current.phase == DeletionPhase.FINALIZING_DATABASE) {
            "Deletion cannot complete before database finalization."
        }
        val at = maxOf(current.updatedAtEpochMillis, completedAtEpochMillis)
        return current.copy(
            phase = DeletionPhase.COMPLETED,
            stateVersion = Math.addExact(current.stateVersion, 1L),
            blockers = emptySet(),
            failure = null,
            updatedAtEpochMillis = at,
            completedAtEpochMillis = at,
        )
    }

    private fun PendingDeletion.progress(): CleanupDelta = CleanupDelta(
        entriesRemoved = entriesRemoved,
        bytesReclaimed = bytesReclaimed,
        entriesVisited = entriesVisited,
        lastRelativePath = lastRelativePath,
    )

    private fun DeletionPhase.canAcquireOrWait(): Boolean = when (this) {
        DeletionPhase.TOMBSTONED,
        DeletionPhase.WAITING_FOR_ACTIVITY,
        DeletionPhase.CLEANUP_PENDING,
        DeletionPhase.CLEANING_FILES,
        DeletionPhase.RETRYABLE_FAILURE,
        -> true

        DeletionPhase.FILES_REMOVED,
        DeletionPhase.FINALIZING_DATABASE,
        DeletionPhase.COMPLETED,
        -> false
    }
}
