package com.realityforge.scanner.data.operations

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal fun requireCanonicalUuid(value: String, label: String): String {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Invalid $label."
    }
    return value
}

enum class ProjectAccessMode { SHARED, EXCLUSIVE }

enum class ProjectOperationKind(
    val accessMode: ProjectAccessMode,
    val stableRevisionRequired: Boolean,
    val durableJobRequired: Boolean,
) {
    READ_INPUTS(ProjectAccessMode.SHARED, true, false),
    READ_CURRENT_RESULT(ProjectAccessMode.SHARED, true, false),
    EXPORT_CAPTURE(ProjectAccessMode.SHARED, true, false),
    EXPORT_RESULT(ProjectAccessMode.SHARED, true, false),
    IMPORT_PHOTO(ProjectAccessMode.EXCLUSIVE, false, false),
    CAPTURE_PHOTO(ProjectAccessMode.EXCLUSIVE, false, false),
    MUTATE_INPUTS(ProjectAccessMode.EXCLUSIVE, false, false),
    LIBRARY_COMMAND(ProjectAccessMode.EXCLUSIVE, false, false),
    CLEANUP(ProjectAccessMode.EXCLUSIVE, false, false),
    DELETE_PROJECT(ProjectAccessMode.EXCLUSIVE, false, false),
    RECONSTRUCT(ProjectAccessMode.EXCLUSIVE, true, true),
    PUBLISH_RESULT(ProjectAccessMode.EXCLUSIVE, true, true),

    /** Compatibility path for M19 callers until owners 001/002/007/023 supply durable job identity. */
    LEGACY_PROCESS_STATE(ProjectAccessMode.EXCLUSIVE, true, false),
    LEGACY_RESULT_PUBLICATION(ProjectAccessMode.EXCLUSIVE, true, false),
    ATOMIC_PROJECT_WRITE(ProjectAccessMode.EXCLUSIVE, false, false),
}

data class ProjectJobIdentity(
    val jobId: String,
    val attemptToken: String,
) {
    init {
        requireCanonicalUuid(jobId, "job identifier")
        requireCanonicalUuid(attemptToken, "job attempt token")
    }
}

data class ProjectOperationRequest(
    val projectId: String,
    val operationId: String,
    val kind: ProjectOperationKind,
    val expectedRevision: Long? = null,
    val job: ProjectJobIdentity? = null,
) {
    init {
        requireCanonicalUuid(projectId, "project identifier")
        requireCanonicalUuid(operationId, "operation identifier")
        require(expectedRevision == null || expectedRevision >= 0L) { "Invalid expected project revision." }
        require(!kind.durableJobRequired || job != null) {
            "${kind.name.lowercase()} requires a durable job identity and attempt token."
        }
    }

    companion object {
        fun create(
            projectId: String,
            kind: ProjectOperationKind,
            expectedRevision: Long? = null,
            job: ProjectJobIdentity? = null,
        ) = ProjectOperationRequest(
            projectId = projectId,
            operationId = UUID.randomUUID().toString(),
            kind = kind,
            expectedRevision = expectedRevision,
            job = job,
        )
    }
}

/**
 * Capability for the currently held project reservation.
 *
 * The constructor and lifecycle methods are internal so callers can only receive a live session
 * from ProjectAccess.withProjectAccess. A session is bound to the repository that created it and
 * becomes unusable as soon as the reservation scope ends.
 */
class ProjectAccessSession internal constructor(
    val request: ProjectOperationRequest,
    val acquiredRevision: Long,
    val acquiredAtEpochMillis: Long,
    private val ownerToken: Any,
) {
    private val active = AtomicBoolean(true)

    internal fun requireActive(expectedOwnerToken: Any) {
        if (ownerToken !== expectedOwnerToken || !active.get()) {
            throw ProjectAccessException("Project access session is no longer active for this repository.")
        }
    }

    internal fun invalidate() {
        active.set(false)
    }
}

open class ProjectAccessException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class ProjectOperationConflictException(
    val projectId: String,
    val requestedKind: ProjectOperationKind,
    message: String,
) : ProjectAccessException(message)

class ProjectMissingException(projectId: String) :
    ProjectAccessException("Project $projectId no longer exists.")

class StaleProjectRevisionException(
    projectId: String,
    expected: Long,
    actual: Long,
) : ProjectAccessException("Project $projectId changed from revision $expected to $actual.")
