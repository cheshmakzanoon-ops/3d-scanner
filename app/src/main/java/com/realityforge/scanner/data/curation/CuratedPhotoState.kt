package com.realityforge.scanner.data.curation

data class CuratedPhoto(
    val id: String,
    val sha256: String,
    val position: Int,
    val state: CuratedPhotoState = CuratedPhotoState.INCLUDED,
    val exclusion: PhotoCurationReason? = null,
    val pendingRemoval: PendingPhotoRemoval? = null,
) {
    init {
        stableId(id, "photo")
        require(SHA256.matches(sha256))
        require(position >= 0)
        require(
            when (state) {
                CuratedPhotoState.INCLUDED -> exclusion == null && pendingRemoval == null
                CuratedPhotoState.EXCLUDED -> exclusion != null && pendingRemoval == null
                CuratedPhotoState.REMOVAL_PENDING -> exclusion == null && pendingRemoval != null
            },
        )
    }
}

data class CurationSnapshot(
    val projectId: String,
    val revision: CurationRevision,
    val photos: List<CuratedPhoto>,
) {
    init {
        stableId(projectId, "project")
        require(photos.size <= MAX_CURATED_PHOTOS)
        require(photos.map { it.id }.toSet().size == photos.size)
        require(photos.map { it.position }.sorted() == photos.indices.toList())
        val removalRequestIds = photos.mapNotNull { it.pendingRemoval?.requestId }
        require(removalRequestIds.toSet().size == removalRequestIds.size) {
            "Pending removal request identities must be unique within a project."
        }
    }

    fun orderedPhotos() = photos.sortedBy { it.position }
}
