package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import kotlinx.coroutines.flow.Flow

/**
 * Compatibility adapter for M19 callers that still reach through ProjectRepository.dao.
 * Reads remain direct and bounded by Room; every mutation participates in C6 project conflict rules.
 */
class CoordinatedScanDao(
    private val delegate: ScanDao,
    private val coordinator: ProjectLeaseCoordinator,
    private val paths: ProjectPathPolicy,
) : ScanDao {
    override fun observeProjects(): Flow<List<ScanProject>> = delegate.observeProjects()

    override suspend fun project(id: String): ScanProject? {
        requireCanonicalUuid(id, "project identifier")
        return delegate.project(id)
    }

    override suspend fun photos(id: String): List<ScanPhoto> {
        requireCanonicalUuid(id, "project identifier")
        return delegate.photos(id)
    }

    override fun observePhotos(id: String): Flow<List<ScanPhoto>> {
        requireCanonicalUuid(id, "project identifier")
        return delegate.observePhotos(id)
    }

    override suspend fun duplicate(id: String, hash: String): Int {
        requireCanonicalUuid(id, "project identifier")
        require(hash.matches(SHA256)) { "Invalid photograph digest." }
        return delegate.duplicate(id, hash)
    }

    override suspend fun insert(project: ScanProject) {
        requireCanonicalUuid(project.id, "project identifier")
        delegate.insert(project)
    }

    override suspend fun insert(photo: ScanPhoto) {
        withMutation(photo.projectId, ProjectOperationKind.MUTATE_INPUTS, stable = false) {
            requireCanonicalUuid(photo.id, "photo identifier")
            paths.photoFile(photo)
            delegate.insert(photo)
        }
    }

    override suspend fun invalidate(id: String) {
        withMutation(id, ProjectOperationKind.MUTATE_INPUTS, stable = false) { delegate.invalidate(id) }
    }

    override suspend fun addPhoto(photo: ScanPhoto) {
        withMutation(photo.projectId, ProjectOperationKind.IMPORT_PHOTO, stable = false) {
            requireCanonicalUuid(photo.id, "photo identifier")
            paths.photoFile(photo)
            delegate.addPhoto(photo)
        }
    }

    override suspend fun state(id: String, revision: Long, state: String, message: String): Int =
        withMutation(
            projectId = id,
            kind = ProjectOperationKind.LEGACY_PROCESS_STATE,
            expectedRevision = revision,
            stable = true,
        ) { delegate.state(id, revision, state, message) }

    override suspend fun complete(id: String, revision: Long, resultKey: String, message: String): Int =
        withMutation(
            projectId = id,
            kind = ProjectOperationKind.LEGACY_RESULT_PUBLICATION,
            expectedRevision = revision,
            stable = true,
        ) {
            check(paths.artifactRevision(resultKey) == revision) {
                "Result artifact revision does not match the project revision."
            }
            delegate.complete(id, revision, resultKey, message)
        }

    override suspend fun recoverInterrupted() = delegate.recoverInterrupted()

    override suspend fun deleteProject(id: String) {
        withMutation(id, ProjectOperationKind.DELETE_PROJECT, stable = false) { delegate.deleteProject(id) }
    }

    private suspend fun <T> withMutation(
        projectId: String,
        kind: ProjectOperationKind,
        expectedRevision: Long? = null,
        stable: Boolean,
        block: suspend () -> T,
    ): T {
        requireCanonicalUuid(projectId, "project identifier")
        val before = delegate.project(projectId) ?: throw ProjectMissingException(projectId)
        val expected = expectedRevision ?: before.revision
        if (before.revision != expected) throw StaleProjectRevisionException(projectId, expected, before.revision)
        val request = ProjectOperationRequest.create(projectId, kind, expected)
        val lease = coordinator.acquire(request)
        try {
            val acquired = delegate.project(projectId) ?: throw ProjectMissingException(projectId)
            if (acquired.revision != expected) {
                throw StaleProjectRevisionException(projectId, expected, acquired.revision)
            }
            val result = block()
            if (stable) {
                val after = delegate.project(projectId) ?: throw ProjectMissingException(projectId)
                if (after.revision != expected) {
                    throw StaleProjectRevisionException(projectId, expected, after.revision)
                }
            }
            return result
        } finally {
            lease.close()
        }
    }

    companion object { private val SHA256 = Regex("[0-9a-f]{64}") }
}
