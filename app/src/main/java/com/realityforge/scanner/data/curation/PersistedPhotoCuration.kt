package com.realityforge.scanner.data.curation

/** Small persistence projection, also used to test corrupt/legacy rows without a fake Room engine. */
data class PersistedPhotoCuration(
    val id: String,
    val sha256: String,
    val included: Boolean,
    val selectionOrder: Long?,
    val addedAt: Long,
    val record: String?,
)

object PhotoCurationPersistence {
    fun snapshot(projectId: String, revision: CurationRevision, rows: List<PersistedPhotoCuration>): CurationSnapshot {
        require(rows.size <= MAX_CURATED_PHOTOS)
        val ordered = rows.sortedWith(compareBy<PersistedPhotoCuration>(
            { it.selectionOrder ?: it.addedAt }, { it.addedAt }, { it.id },
        ))
        val photos = ordered.mapIndexed { position, row ->
            row.record?.let { record ->
                PhotoCurationRecordCodec.decode(record).also { photo ->
                    check(photo.id == row.id && photo.sha256 == row.sha256) { "Curation record belongs to another original." }
                    check(photo.position == position && row.selectionOrder == position.toLong()) {
                        "Curation order projection disagrees with its durable record."
                    }
                    check(row.included == (photo.state == CuratedPhotoState.INCLUDED)) {
                        "Curation selection projection disagrees with its durable record."
                    }
                }
            } ?: CuratedPhoto(row.id, row.sha256, position,
                if (row.included) CuratedPhotoState.INCLUDED else CuratedPhotoState.EXCLUDED,
                if (row.included) null else PhotoCurationReason(PhotoExclusionCode.USER_CHOICE))
        }
        return CurationSnapshot(projectId, revision, immutable(photos))
    }

    /** Validate the persistence boundary as well as the command reducer. No original may disappear. */
    fun validateCommit(current: CurationSnapshot, request: CurationCommitRequest): CurationStoreCommitResult.Rejected? {
        val next = request.nextSnapshot
        fun reject(code: String, message: String) = CurationStoreCommitResult.Rejected(code, message)
        if (next.photos.associate { it.id to it.sha256 } != current.photos.associate { it.id to it.sha256 }) {
            return reject("ORIGINAL_REMOVAL_REQUIRES_VERIFIED_JOURNAL",
                "Retained originals can only be removed by the journaled original-removal service.")
        }
        val selectionChanged = current.toSelectedPhotoSet().inputFingerprint != next.toSelectedPhotoSet().inputFingerprint
        val oldById = current.photos.associateBy { it.id }
        val removalChanged = next.photos.any { it.pendingRemoval != oldById.getValue(it.id).pendingRemoval }
        val projectChanged = selectionChanged || removalChanged
        val kind = when {
            removalChanged -> CurationChangeKind.REMOVAL_LIFECYCLE
            selectionChanged -> CurationChangeKind.RECONSTRUCTION_INPUTS
            else -> CurationChangeKind.METADATA_ONLY
        }
        if (request.change != CurationChange(kind, projectChanged, selectionChanged)) {
            return reject("CURATION_CHANGE_MISMATCH", "Curation change flags disagree with the stored input selection.")
        }
        if (current.revision.curationVersion == Long.MAX_VALUE ||
            projectChanged && current.revision.projectRevision == Long.MAX_VALUE) {
            return reject("REVISION_OVERFLOW", "Curation revision cannot be advanced safely.")
        }
        val expectedNext = CurationRevision(current.revision.projectRevision + if (projectChanged) 1L else 0L,
            current.revision.curationVersion + 1L)
        if (next.revision != expectedNext) {
            return reject("CURATION_REVISION_MISMATCH", "The committed revision must advance exactly once.")
        }
        if (current.photos.sortedBy { it.id } == next.photos.sortedBy { it.id }) {
            return reject("CURATION_NO_CHANGE", "An unchanged curation snapshot must not advance its revision.")
        }
        return null
    }
}
