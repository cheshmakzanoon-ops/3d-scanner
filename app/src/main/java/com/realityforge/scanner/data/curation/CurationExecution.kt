package com.realityforge.scanner.data.curation

import kotlinx.coroutines.CancellationException

internal suspend fun executeCuration(service: DefaultPhotoCurationService, command: PhotoCurationCommand): PhotoCurationOutcome {
    validateEnvelope(command)?.let { return PhotoCurationOutcome.Rejected(it) }
    val current = try { service.store.read(command.projectId) } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
        return rejectedStore("READ_FAILED", e)
    } ?: return PhotoCurationOutcome.Rejected(PhotoCurationFailure.ProjectNotFound(command.projectId))
    if (current.revision != command.expectedRevision) return PhotoCurationOutcome.Rejected(
        PhotoCurationFailure.RevisionConflict(command.expectedRevision, current.revision), current.toSelectedPhotoSet(),
    )
    val reduced = try { reduceCuration(current, command, service.clockMillis, service.removalRequestId) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (e: IllegalArgumentException) { return PhotoCurationOutcome.Rejected(PhotoCurationFailure.InvalidCommand("INVALID_GENERATED_IDENTITY", e.message ?: "Invalid generated identity."), current.toSelectedPhotoSet()) }
    reduced.failure?.let { return PhotoCurationOutcome.Rejected(it, current.toSelectedPhotoSet()) }
    val photos = reduced.photos ?: return PhotoCurationOutcome.Unchanged(current, current.toSelectedPhotoSet())
    val proposal = CurationSnapshot(current.projectId, current.revision, photos)
    val selectedChanged = current.toSelectedPhotoSet().inputFingerprint != proposal.toSelectedPhotoSet().inputFingerprint
    val projectChanged = selectedChanged || reduced.removalLifecycleChanged || reduced.retainedOriginalsChanged
    val nextRevision = advance(current.revision, projectChanged) ?: return PhotoCurationOutcome.Rejected(
        PhotoCurationFailure.InvalidCommand("REVISION_OVERFLOW", "Revision cannot be advanced safely."), current.toSelectedPhotoSet(),
    )
    val next = proposal.copy(revision = nextRevision)
    val change = CurationChange(
        when {
            reduced.retainedOriginalsChanged -> CurationChangeKind.RETAINED_ORIGINALS
            reduced.removalLifecycleChanged -> CurationChangeKind.REMOVAL_LIFECYCLE
            selectedChanged -> CurationChangeKind.RECONSTRUCTION_INPUTS
            else -> CurationChangeKind.METADATA_ONLY
        },
        projectChanged,
        selectedChanged,
    )
    val undoId = if (command is PhotoCurationCommand.CompleteRemoval) null else try {
        service.undoTokenId().also { stableId(it, "undo token") }
    } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
        return PhotoCurationOutcome.Rejected(PhotoCurationFailure.InvalidCommand("INVALID_UNDO_IDENTITY", e.message ?: "Undo identity failed."), current.toSelectedPhotoSet())
    }
    val stored = try { service.store.commit(CurationCommitRequest(command.requestId, command.projectId, current.revision, next, change)) }
    catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { return rejectedStore("COMMIT_FAILED", e, current.toSelectedPhotoSet()) }
    return when (stored) {
        is CurationStoreCommitResult.Conflict -> PhotoCurationOutcome.Rejected(PhotoCurationFailure.RevisionConflict(current.revision, stored.currentSnapshot?.revision), stored.currentSnapshot?.toSelectedPhotoSet())
        is CurationStoreCommitResult.Rejected -> PhotoCurationOutcome.Rejected(PhotoCurationFailure.StoreFailure(stored.code, stored.message), current.toSelectedPhotoSet())
        is CurationStoreCommitResult.Committed -> committed(command, current, next, stored.snapshot, change, undoId)
    }
}

private fun committed(
    command: PhotoCurationCommand, before: CurationSnapshot, expected: CurationSnapshot, actual: CurationSnapshot,
    change: CurationChange, undoId: String?,
): PhotoCurationOutcome {
    if (actual.projectId != expected.projectId || actual.revision != expected.revision || curationStateFingerprint(actual) != curationStateFingerprint(expected))
        return PhotoCurationOutcome.Rejected(PhotoCurationFailure.StoreProtocolViolation("Store returned state different from the committed transaction."), actual.toSelectedPhotoSet())
    val undo = if (undoId == null) null else CurationUndoToken(
        undoId, before.projectId, command.requestId, actual.revision, immutable(before.orderedPhotos()), curationStateFingerprint(actual),
    )
    return PhotoCurationOutcome.Applied(actual, actual.toSelectedPhotoSet(), change, undo, removalDirectives(before, actual))
}

private fun advance(r: CurationRevision, projectChanged: Boolean): CurationRevision? {
    if (r.curationVersion == Long.MAX_VALUE || projectChanged && r.projectRevision == Long.MAX_VALUE) return null
    return CurationRevision(if (projectChanged) r.projectRevision + 1 else r.projectRevision, r.curationVersion + 1)
}

private fun validateEnvelope(c: PhotoCurationCommand): PhotoCurationFailure? = try {
    stableId(c.projectId, "project"); stableId(c.requestId, "command request")
    when (c) {
        is PhotoCurationCommand.Include -> stableId(c.photoId, "photo")
        is PhotoCurationCommand.Exclude -> stableId(c.photoId, "photo")
        is PhotoCurationCommand.Reorder -> c.orderedPhotoIds.forEach { stableId(it, "photo") }
        is PhotoCurationCommand.RequestRemoval -> stableId(c.photoId, "photo")
        is PhotoCurationCommand.RestorePendingRemoval -> stableId(c.photoId, "photo")
        is PhotoCurationCommand.CompleteRemoval -> { stableId(c.photoId, "photo"); stableId(c.removalRequestId, "removal request"); require(SHA256.matches(c.removedOriginalSha256)) }
        is PhotoCurationCommand.Undo -> Unit
    }; null
} catch (e: IllegalArgumentException) { PhotoCurationFailure.InvalidCommand("INVALID_IDENTITY", e.message ?: "Invalid command identity.") }

private fun rejectedStore(code: String, e: Exception, selection: SelectedPhotoSet? = null) =
    PhotoCurationOutcome.Rejected(PhotoCurationFailure.StoreFailure(code, e.message ?: e.javaClass.simpleName), selection)
