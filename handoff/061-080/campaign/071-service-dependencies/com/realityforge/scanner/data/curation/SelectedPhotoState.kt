package com.realityforge.scanner.data.curation

data class SelectedPhoto(val id: String, val sha256: String, val position: Int) {
    init {
        stableId(id, "photo")
        require(SHA256.matches(sha256))
        require(position >= 0)
    }
}

enum class ReconstructionSelectionStatus { EMPTY, INSUFFICIENT_PHOTOS, READY }

data class SelectedPhotoSet(
    val schemaVersion: Int,
    val projectId: String,
    val revision: CurationRevision,
    val inputFingerprint: String,
    val photos: List<SelectedPhoto>,
    val retainedOriginalCount: Int,
    val excludedCount: Int,
    val pendingRemovalCount: Int,
    val status: ReconstructionSelectionStatus,
) {
    init {
        require(schemaVersion == PHOTO_CURATION_SCHEMA_VERSION)
        stableId(projectId, "project")
        require(SHA256.matches(inputFingerprint))
        require(photos.size <= MAX_CURATED_PHOTOS)
        require(photos.map { it.position } == photos.indices.toList())
        require(photos.map { it.id }.toSet().size == photos.size)
        require(excludedCount >= 0 && pendingRemovalCount >= 0)
        require(retainedOriginalCount == photos.size + excludedCount + pendingRemovalCount) {
            "Selected, excluded, and pending counts must account for every retained original."
        }
        val expectedStatus = when {
            photos.isEmpty() -> ReconstructionSelectionStatus.EMPTY
            photos.size < 2 -> ReconstructionSelectionStatus.INSUFFICIENT_PHOTOS
            else -> ReconstructionSelectionStatus.READY
        }
        require(status == expectedStatus) { "Selection status does not match the selected photo count." }
    }
}

data class PhotoCurationState(
    val projectId: String,
    val revision: CurationRevision,
    val photos: List<CuratedPhoto>,
    val selectedPhotoSet: SelectedPhotoSet,
) {
    init {
        require(selectedPhotoSet.projectId == projectId && selectedPhotoSet.revision == revision)
        require(photos.map { it.position } == photos.indices.toList())
    }
}
