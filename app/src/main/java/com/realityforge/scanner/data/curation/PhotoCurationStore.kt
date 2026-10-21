package com.realityforge.scanner.data.curation

/**
 * Persistence boundary implemented transactionally by Agent 005/006. A commit must compare
 * both revision fields and update photo metadata plus the project revision atomically.
 */
interface PhotoCurationStore {
    suspend fun read(projectId: String): CurationSnapshot?

    suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult
}

data class CurationCommitRequest(
    val requestId: String,
    val projectId: String,
    val expectedRevision: CurationRevision,
    val nextSnapshot: CurationSnapshot,
    val change: CurationChange,
) {
    init {
        requireStableId(requestId, "command request")
        requireStableId(projectId, "project")
        require(nextSnapshot.projectId == projectId) { "Committed snapshot belongs to another project." }
    }
}

sealed interface CurationStoreCommitResult {
    data class Committed(val snapshot: CurationSnapshot) : CurationStoreCommitResult
    data class Conflict(val currentSnapshot: CurationSnapshot?) : CurationStoreCommitResult
    data class Rejected(val code: String, val message: String) : CurationStoreCommitResult
}
