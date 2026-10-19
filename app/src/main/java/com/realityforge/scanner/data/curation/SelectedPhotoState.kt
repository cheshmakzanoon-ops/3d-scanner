package com.realityforge.scanner.data.curation

data class SelectedPhoto(val id: String, val sha256: String, val position: Int)
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
        require(photos.map { it.position } == photos.indices.toList())
        require(retainedOriginalCount >= photos.size && excludedCount >= 0 && pendingRemovalCount >= 0)
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
