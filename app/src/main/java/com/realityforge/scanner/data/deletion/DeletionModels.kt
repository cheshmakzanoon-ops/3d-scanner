package com.realityforge.scanner.data.deletion

import java.util.UUID

internal fun requireCanonicalUuid(value: String, label: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "$label must be a canonical UUID."
    }
}

/** A user action that has not yet crossed the destructive confirmation boundary. */
data class DeletionIntent(
    val operationId: String,
    val scope: DeletionScope,
    val expectedRevision: Long,
    val requestedAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(operationId, "Deletion operation ID")
        require(expectedRevision >= 0) { "Expected revision cannot be negative." }
        require(requestedAtEpochMillis >= 0) { "Requested time cannot be negative." }
    }

    /**
     * Confirmation is explicit and produces the only request type accepted by [DeletionService].
     * Dismissing the confirmation UI leaves no durable tombstone and performs no file work.
     */
    fun confirm(confirmedAtEpochMillis: Long): ConfirmedDeletionRequest {
        require(confirmedAtEpochMillis >= requestedAtEpochMillis) {
            "Confirmation cannot predate the deletion request."
        }
        return ConfirmedDeletionRequest(
            operationId = operationId,
            scope = scope,
            expectedRevision = expectedRevision,
            requestedAtEpochMillis = requestedAtEpochMillis,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            confirmationVersion = ConfirmedDeletionRequest.CURRENT_CONFIRMATION_VERSION,
        )
    }
}

/** Persist this request atomically with logical deletion of its project or photo. */
data class ConfirmedDeletionRequest internal constructor(
    val operationId: String,
    val scope: DeletionScope,
    val expectedRevision: Long,
    val requestedAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long,
    val confirmationVersion: Int,
) {
    init {
        requireCanonicalUuid(operationId, "Deletion operation ID")
        require(expectedRevision >= 0) { "Expected revision cannot be negative." }
        require(requestedAtEpochMillis >= 0 && confirmedAtEpochMillis >= requestedAtEpochMillis)
        require(confirmationVersion == CURRENT_CONFIRMATION_VERSION) {
            "Unsupported deletion confirmation version."
        }
    }

    companion object {
        const val CURRENT_CONFIRMATION_VERSION = 1
    }
}

sealed interface DeletionScope {
    val projectId: String

    data class Project(override val projectId: String) : DeletionScope {
        init { requireCanonicalUuid(projectId, "Project ID") }
    }

    data class Photo(
        override val projectId: String,
        val photoId: String,
    ) : DeletionScope {
        init {
            requireCanonicalUuid(projectId, "Project ID")
            requireCanonicalUuid(photoId, "Photo ID")
        }
    }
}

/**
 * Persisted app-private targets. No absolute path enters the tombstone schema.
 * The persistence owner must omit paths that still have another live owner/reference.
 */
sealed interface OwnedDeletionTarget {
    val projectId: String

    data class ProjectTree(override val projectId: String) : OwnedDeletionTarget {
        init { requireCanonicalUuid(projectId, "Project ID") }
    }

    data class ProjectRelativeNode(
        override val projectId: String,
        val relativePath: String,
    ) : OwnedDeletionTarget {
        init {
            requireCanonicalUuid(projectId, "Project ID")
            validateProjectRelativePath(relativePath)
        }
    }
}

internal fun validateProjectRelativePath(path: String) {
    require(path.isNotBlank()) { "Owned relative path cannot be blank." }
    require(path.length <= 1024) { "Owned relative path is too long." }
    require('\u0000' !in path && '\n' !in path && '\r' !in path) {
        "Owned relative path contains a forbidden character."
    }
    require(!path.startsWith('/') && !path.endsWith('/')) {
        "Owned relative path must not be absolute or end with a separator."
    }
    require('\\' !in path) { "Owned relative paths use '/' separators only." }
    val segments = path.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "Owned relative path contains traversal or an empty segment."
    }
}

enum class DeletionPhase {
    /** Logical deletion and immutable owned-target snapshot have committed. */
    TOMBSTONED,

    /** Existing admitted work still owns the project/photo; new work remains blocked. */
    WAITING_FOR_ACTIVITY,

    /** A bounded pass completed and another deterministic file pass is required. */
    CLEANUP_PENDING,

    /** A worker owns the deletion lease and may currently be unlinking private files. */
    CLEANING_FILES,

    /** Cleanup stopped on a typed, user-visible failure and can be retried. */
    RETRYABLE_FAILURE,

    /** Every staged private-file target is absent; database finalization is now safe. */
    FILES_REMOVED,

    /** The atomic row/reference finalization transaction may be retried idempotently. */
    FINALIZING_DATABASE,

    COMPLETED,
}

enum class DeletionBlocker {
    ACTIVE_IMPORT,
    ACTIVE_CAPTURE,
    ACTIVE_RECONSTRUCTION,
    ACTIVE_EXPORT,
    ACTIVE_CURATION,
    ACTIVE_CACHE_CLEANUP,
    ACTIVE_PUBLICATION,
}

enum class DeletionFailureCode {
    INVALID_TARGET,
    ROOT_NOT_DIRECTORY,
    PATH_COMPONENT_SYMLINK,
    PATH_OUTSIDE_ROOT,
    IO_READ_FAILED,
    IO_DELETE_FAILED,
    CONCURRENT_MUTATION,
    INTERNAL_ERROR,
}

data class DeletionFailure(
    val code: DeletionFailureCode,
    /** Safe project-relative context only; never an absolute private path. */
    val detail: String,
) {
    init {
        require(detail.isNotBlank() && detail.length <= 300) {
            "Deletion failure detail must be present and bounded."
        }
        require(!detail.startsWith('/') && '\u0000' !in detail && '\n' !in detail && '\r' !in detail) {
            "Deletion failure detail must not expose an absolute or multiline private path."
        }
    }
}

data class CleanupDelta(
    val entriesRemoved: Long = 0,
    val bytesReclaimed: Long = 0,
    val entriesVisited: Long = 0,
    val lastRelativePath: String? = null,
) {
    init {
        require(entriesRemoved >= 0 && bytesReclaimed >= 0 && entriesVisited >= 0)
        require(lastRelativePath == null ||
            (!lastRelativePath.startsWith('/') && '\u0000' !in lastRelativePath && '\n' !in lastRelativePath && '\r' !in lastRelativePath)
        ) { "Cleanup progress must contain only a safe relative path." }
    }

    operator fun plus(other: CleanupDelta): CleanupDelta = CleanupDelta(
        entriesRemoved = Math.addExact(entriesRemoved, other.entriesRemoved),
        bytesReclaimed = Math.addExact(bytesReclaimed, other.bytesReclaimed),
        entriesVisited = Math.addExact(entriesVisited, other.entriesVisited),
        lastRelativePath = other.lastRelativePath ?: lastRelativePath,
    )
}

data class PendingDeletion(
    val operationId: String,
    val scope: DeletionScope,
    val expectedRevision: Long,
    val confirmedAtEpochMillis: Long,
    val phase: DeletionPhase,
    val stateVersion: Long,
    val targets: List<OwnedDeletionTarget>,
    /** Opaque database/artifact identities kept because another live owner still references them. */
    val retainedReferenceIds: Set<String> = emptySet(),
    val attempts: Int = 0,
    val entriesRemoved: Long = 0,
    val bytesReclaimed: Long = 0,
    val entriesVisited: Long = 0,
    val lastRelativePath: String? = null,
    val blockers: Set<DeletionBlocker> = emptySet(),
    val failure: DeletionFailure? = null,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
) {
    init {
        requireCanonicalUuid(operationId, "Deletion operation ID")
        require(expectedRevision >= 0 && confirmedAtEpochMillis >= 0 && stateVersion >= 0)
        require(updatedAtEpochMillis >= confirmedAtEpochMillis)
        require(attempts >= 0 && entriesRemoved >= 0 && bytesReclaimed >= 0 && entriesVisited >= 0)
        require(targets.size <= MAX_TARGETS) { "Deletion target snapshot is too large." }
        require(targets.distinct().size == targets.size) { "Deletion target snapshot contains duplicates." }
        require(targets.all { it.projectId == scope.projectId }) {
            "Every deletion target must belong to the scoped project."
        }
        if (scope is DeletionScope.Photo) {
            require(targets.none { it is OwnedDeletionTarget.ProjectTree }) {
                "Photo deletion cannot stage the entire project tree."
            }
        }
        require(retainedReferenceIds.size <= MAX_RETAINED_REFERENCES)
        require(retainedReferenceIds.none {
            it.isBlank() || it.length > 200 || '\u0000' in it || '\n' in it || '\r' in it
        })
        require(lastRelativePath == null || !lastRelativePath.startsWith('/'))
        require((phase == DeletionPhase.COMPLETED) == (completedAtEpochMillis != null)) {
            "Only completed tombstones carry a completion time."
        }
        require((phase == DeletionPhase.WAITING_FOR_ACTIVITY) == blockers.isNotEmpty()) {
            "Only a waiting deletion carries active-operation blockers."
        }
        require((phase == DeletionPhase.RETRYABLE_FAILURE) == (failure != null)) {
            "Only a retryable deletion carries a cleanup failure."
        }
    }

    companion object {
        const val MAX_TARGETS = 10_000
        const val MAX_RETAINED_REFERENCES = 10_000
    }
}

sealed interface DeletionTransition {
    val atEpochMillis: Long

    data class WaitingForActivity(
        val blockers: Set<DeletionBlocker>,
        override val atEpochMillis: Long,
    ) : DeletionTransition {
        init { require(blockers.isNotEmpty()) }
    }

    data class CleaningFiles(override val atEpochMillis: Long) : DeletionTransition

    data class CleanupProgress(
        val delta: CleanupDelta,
        val moreWork: Boolean,
        override val atEpochMillis: Long,
    ) : DeletionTransition

    data class RetryableFailure(
        val failure: DeletionFailure,
        val delta: CleanupDelta,
        override val atEpochMillis: Long,
    ) : DeletionTransition

    data class FinalizingDatabase(override val atEpochMillis: Long) : DeletionTransition
}

/**
 * Agent 005 implementation contract.
 *
 * - [stageConfirmed] is one database transaction: validate expected revision, insert/idempotently
 *   load the tombstone and mark the target logically deleting so new work cannot start.
 * - persisted targets are immutable snapshots and contain only project-owned relative targets.
 * - [transition] is compare-and-set on [PendingDeletion.stateVersion], using
 *   [DeletionStateReducer] for the exact state/counter semantics.
 * - [finalizeDeletion] atomically removes the target rows/references and marks the tombstone
 *   completed; it must never run before the state reaches [DeletionPhase.FINALIZING_DATABASE].
 */
interface DeletionPersistence {
    suspend fun stageConfirmed(request: ConfirmedDeletionRequest): PendingDeletion
    suspend fun load(operationId: String): PendingDeletion?
    suspend fun loadRecoverable(limit: Int): List<PendingDeletion>
    suspend fun transition(
        operationId: String,
        expectedStateVersion: Long,
        transition: DeletionTransition,
    ): PendingDeletion?

    suspend fun finalizeDeletion(
        operationId: String,
        expectedStateVersion: Long,
        completedAtEpochMillis: Long,
    ): PendingDeletion?
}

sealed interface DeletionLeaseResult {
    data class Acquired(val leaseId: String) : DeletionLeaseResult {
        init { require(leaseId.isNotBlank() && leaseId.length <= 200) }
    }

    data class Blocked(val blockers: Set<DeletionBlocker>) : DeletionLeaseResult {
        init { require(blockers.isNotEmpty()) }
    }
}

/**
 * Agent 007/006 contract: persistent logical deletion blocks new work; this lease drains current
 * work and serializes cleanup. Release must be idempotent and must not admit new work while the
 * tombstone remains recoverable.
 */
interface DeletionLeaseCoordinator {
    suspend fun acquire(operationId: String, scope: DeletionScope): DeletionLeaseResult
    suspend fun release(operationId: String, leaseId: String)
}

sealed interface DeletionRunResult {
    val deletion: PendingDeletion?

    data class Completed(override val deletion: PendingDeletion) : DeletionRunResult
    data class MoreWork(override val deletion: PendingDeletion) : DeletionRunResult
    data class Waiting(override val deletion: PendingDeletion) : DeletionRunResult
    data class RetryableFailure(override val deletion: PendingDeletion) : DeletionRunResult
    data class ConcurrentUpdate(override val deletion: PendingDeletion?) : DeletionRunResult
    data class Missing(val operationId: String) : DeletionRunResult {
        override val deletion: PendingDeletion? = null
    }
}
