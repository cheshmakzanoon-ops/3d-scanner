package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import org.junit.Assert.assertEquals

internal const val PROJECT_ID = "project-018"

internal fun fixture(states: Map<String, PhotoCurationReason> = emptyMap(), count: Int = 4) = CurationSnapshot(
    PROJECT_ID, CurationRevision(7, 0), (1..count).map { n ->
        val id = "photo-$n"; val reason = states[id]
        CuratedPhoto(id, n.toString(16).padStart(64, '0'), n - 1,
            if (reason == null) CuratedPhotoState.INCLUDED else CuratedPhotoState.EXCLUDED, reason)
    },
)

internal fun service(
    store: PhotoCurationStore,
    removalId: () -> String = { "removal-default" },
    undoId: () -> String = { "undo-default" },
) = DefaultPhotoCurationService(store, { 1_800_000_000_000L }, removalId, undoId)

internal class MemoryStore(initial: CurationSnapshot) : PhotoCurationStore {
    private val lock = Any(); private var current: CurationSnapshot? = initial
    var commits = 0; private set
    override suspend fun read(projectId: String) = synchronized(lock) { current?.takeIf { it.projectId == projectId } }
    override suspend fun commit(request: CurationCommitRequest) = synchronized(lock) {
        val now = current
        if (now == null || now.projectId != request.projectId || now.revision != request.expectedRevision) CurationStoreCommitResult.Conflict(now)
        else { current = request.nextSnapshot; commits++; CurationStoreCommitResult.Committed(request.nextSnapshot) }
    }
    fun snapshot() = synchronized(lock) { requireNotNull(current) }
}

internal fun PhotoCurationOutcome.applied() = this as? PhotoCurationOutcome.Applied ?: error("Expected Applied, got $this")
internal fun PhotoCurationOutcome.rejected() = this as? PhotoCurationOutcome.Rejected ?: error("Expected Rejected, got $this")
internal fun assertInvariant(snapshot: CurationSnapshot, hashes: Map<String, String>) {
    val photos = snapshot.orderedPhotos()
    assertEquals(photos.indices.toList(), photos.map { it.position })
    assertEquals(photos.size, photos.map { it.id }.toSet().size)
    photos.forEach { assertEquals(hashes[it.id], it.sha256) }
}
