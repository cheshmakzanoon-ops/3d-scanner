package com.realityforge.scanner.data.curation

sealed interface PhotoCurationCommand {
    val projectId: String; val expectedRevision: CurationRevision; val requestId: String
    data class Include(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val photoId: String) : PhotoCurationCommand
    data class Exclude(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val photoId: String, val reason: PhotoCurationReason) : PhotoCurationCommand
    data class Reorder(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val orderedPhotoIds: List<String>) : PhotoCurationCommand
    data class RequestRemoval(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val photoId: String, val reason: PhotoRemovalReason) : PhotoCurationCommand
    data class RestorePendingRemoval(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val photoId: String) : PhotoCurationCommand
    data class CompleteRemoval(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val photoId: String, val removalRequestId: String, val removedOriginalSha256: String) : PhotoCurationCommand
    data class Undo(override val projectId: String, override val expectedRevision: CurationRevision, override val requestId: String, val token: CurationUndoToken) : PhotoCurationCommand
}

class CurationUndoToken internal constructor(
    val tokenId: String,
    val projectId: String,
    val commandRequestId: String,
    val afterRevision: CurationRevision,
    internal val beforePhotos: List<CuratedPhoto>,
    internal val afterStateFingerprint: String,
)

enum class CurationChangeKind { METADATA_ONLY, RECONSTRUCTION_INPUTS, REMOVAL_LIFECYCLE, RETAINED_ORIGINALS }
data class CurationChange(val kind: CurationChangeKind, val projectRevisionChanged: Boolean, val selectedInputChanged: Boolean)

sealed interface PhotoRemovalDirective {
    data class Stage(val projectId: String, val photoId: String, val photoSha256: String, val request: PendingPhotoRemoval, val sourceProjectRevision: Long) : PhotoRemovalDirective
    data class Cancel(val projectId: String, val photoId: String, val removalRequestId: String, val sourceProjectRevision: Long) : PhotoRemovalDirective
}

sealed interface PhotoCurationFailure {
    data class ProjectNotFound(val projectId: String) : PhotoCurationFailure
    data class RevisionConflict(val expected: CurationRevision, val actual: CurationRevision?) : PhotoCurationFailure
    data class PhotoNotFound(val photoId: String) : PhotoCurationFailure
    data class PhotoPendingRemoval(val photoId: String, val requestId: String) : PhotoCurationFailure
    data class InvalidCommand(val code: String, val message: String) : PhotoCurationFailure
    data class UndoNotApplicable(val code: String, val message: String) : PhotoCurationFailure
    data class StoreFailure(val code: String, val message: String) : PhotoCurationFailure
    data class StoreProtocolViolation(val message: String) : PhotoCurationFailure
}

sealed interface PhotoCurationOutcome {
    val selectedPhotoSet: SelectedPhotoSet?
    data class Applied(
        val snapshot: CurationSnapshot,
        override val selectedPhotoSet: SelectedPhotoSet,
        val change: CurationChange,
        val undoToken: CurationUndoToken?,
        val removalDirectives: List<PhotoRemovalDirective>,
    ) : PhotoCurationOutcome
    data class Unchanged(val snapshot: CurationSnapshot, override val selectedPhotoSet: SelectedPhotoSet) : PhotoCurationOutcome
    data class Rejected(val failure: PhotoCurationFailure, override val selectedPhotoSet: SelectedPhotoSet? = null) : PhotoCurationOutcome
}
