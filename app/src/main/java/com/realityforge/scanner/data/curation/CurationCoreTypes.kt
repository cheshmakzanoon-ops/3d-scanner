package com.realityforge.scanner.data.curation

const val PHOTO_CURATION_SCHEMA_VERSION = 1
const val MAX_CURATED_PHOTOS = 4_096

data class CurationRevision(val projectRevision: Long, val curationVersion: Long) {
    init { require(projectRevision >= 0 && curationVersion >= 0) }
}

enum class PhotoExclusionCode { BLUR, UNDEREXPOSED, OVEREXPOSED, DUPLICATE_VIEW, LOW_OVERLAP, MOVING_SUBJECT, USER_CHOICE, OTHER }
enum class PhotoRemovalCode { USER_REQUEST, DUPLICATE, PRIVACY, CORRUPT_INPUT, OTHER }

data class PhotoCurationReason(val code: PhotoExclusionCode, val note: String? = null) {
    init { validateNote(note) }
}

data class PhotoRemovalReason(val code: PhotoRemovalCode, val note: String? = null) {
    init { validateNote(note) }
}

enum class CuratedPhotoState { INCLUDED, EXCLUDED, REMOVAL_PENDING }
enum class RestorablePhotoState { INCLUDED, EXCLUDED }

data class PendingPhotoRemoval(
    val requestId: String,
    val requestedAtEpochMillis: Long,
    val reason: PhotoRemovalReason,
    val previousState: RestorablePhotoState,
    val previousExclusion: PhotoCurationReason? = null,
) {
    init {
        stableId(requestId, "removal request")
        require(requestedAtEpochMillis >= 0)
        require((previousState == RestorablePhotoState.EXCLUDED) == (previousExclusion != null))
    }
}
