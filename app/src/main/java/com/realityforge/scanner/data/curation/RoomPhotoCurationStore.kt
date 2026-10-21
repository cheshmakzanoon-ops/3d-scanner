package com.realityforge.scanner.data.curation

import androidx.room.withTransaction
import com.realityforge.scanner.data.ScanDatabase

/**
 * One Room transaction updates the canonical records, indexed engine projections and both CAS
 * revisions. The repository additionally owns its project-operation exclusion around commands.
 * Original removal is deliberately delegated to owner 022's verified journal; this store never
 * deletes a file or forgets a photo row in response to an exclusion/removal-request command.
 */
class RoomPhotoCurationStore(private val database: ScanDatabase) : PhotoCurationStore {
    override suspend fun read(projectId: String): CurationSnapshot? {
        stableId(projectId, "project")
        return database.withTransaction { readTransaction(projectId) }
    }

    override suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult {
        // A caller cannot mutate the collection while a suspended Room transaction is queued.
        val proposal = request.copy(nextSnapshot = request.nextSnapshot.copy(photos = immutable(request.nextSnapshot.photos)))
        return database.withTransaction {
            val current = readTransaction(proposal.projectId)
            if (current == null || current.revision != proposal.expectedRevision) {
                return@withTransaction CurationStoreCommitResult.Conflict(current)
            }
            PhotoCurationPersistence.validateCommit(current, proposal)?.let { return@withTransaction it }
            val dao = database.dao()
            proposal.nextSnapshot.orderedPhotos().forEach { photo ->
                check(dao.updatePhotoCurationRecord(proposal.projectId, photo.id, photo.sha256,
                    photo.state == CuratedPhotoState.INCLUDED, photo.position.toLong(),
                    PhotoCurationRecordCodec.encode(photo)) == 1) { "Photo identity changed during curation." }
            }
            check(dao.compareAndSetCurationRevision(proposal.projectId,
                proposal.expectedRevision.projectRevision, proposal.expectedRevision.curationVersion,
                proposal.nextSnapshot.revision.projectRevision, proposal.nextSnapshot.revision.curationVersion,
                proposal.change.projectRevisionChanged) == 1) { "Curation revision changed during transaction." }
            CurationStoreCommitResult.Committed(requireNotNull(readTransaction(proposal.projectId)))
        }
    }

    private suspend fun readTransaction(projectId: String): CurationSnapshot? {
        val dao = database.dao()
        val project = dao.project(projectId) ?: return null
        return PhotoCurationPersistence.snapshot(projectId, CurationRevision(project.revision, project.curationVersion),
            dao.photos(projectId).map { photo ->
                PersistedPhotoCuration(photo.id, photo.sha256, photo.included, photo.selectionOrder,
                    photo.addedAt, photo.curationRecord)
            })
    }
}
