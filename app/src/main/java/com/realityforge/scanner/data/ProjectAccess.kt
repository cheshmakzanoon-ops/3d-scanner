package com.realityforge.scanner.data

import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectOperationRequest

/**
 * Stable project boundary consumed by application and pipeline owners.
 *
 * Callers bind an operation to a concrete project, revision and operation ID before
 * suspending. Reconstruction and publication requests also carry a durable job attempt.
 * The repository validates that identity after obtaining the project reservation.
 */
interface ProjectAccess {
    /** Convenience read that acquires and releases its own READ_INPUTS reservation. */
    suspend fun inputSnapshot(projectId: String): ProjectInputSnapshot

    /** Read inputs through an already-held stable-revision reservation without nesting a lease. */
    suspend fun inputSnapshot(session: ProjectAccessSession): ProjectInputSnapshot

    /** Convenience read that acquires and releases its own READ_CURRENT_RESULT reservation. */
    suspend fun currentResult(projectId: String): ProjectCurrentResult?

    /** Read the current result through an already-held stable-revision reservation. */
    suspend fun currentResult(session: ProjectAccessSession): ProjectCurrentResult?

    suspend fun <T> withProjectAccess(
        request: ProjectOperationRequest,
        block: suspend (ProjectAccessSession) -> T,
    ): T
}

data class ProjectInputPhoto(
    val id: String,
    val sha256: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val focal35mm: Double?,
    val addedAt: Long,
)

data class ProjectInputSnapshot(
    val projectId: String,
    val revision: Long,
    val photos: List<ProjectInputPhoto>,
)

data class ProjectCurrentResult(
    val projectId: String,
    val revision: Long,
    val artifactKey: String,
    val diagnosticJson: ProjectArtifactReference,
)

data class ProjectArtifactReference(
    val projectId: String,
    val revision: Long,
    val artifactKey: String,
    val extension: String,
    val absolutePath: String,
)
