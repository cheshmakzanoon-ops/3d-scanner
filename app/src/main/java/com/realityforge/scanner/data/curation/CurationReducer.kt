package com.realityforge.scanner.data.curation

internal data class Reduction(
    val photos: List<CuratedPhoto>? = null,
    val failure: PhotoCurationFailure? = null,
    val removalLifecycleChanged: Boolean = false,
    val retainedOriginalsChanged: Boolean = false,
) {
    companion object {
        fun unchanged() = Reduction()
        fun changed(
            photos: List<CuratedPhoto>,
            removalLifecycle: Boolean = false,
            retainedOriginals: Boolean = false,
        ) = Reduction(
            immutable(photos),
            removalLifecycleChanged = removalLifecycle,
            retainedOriginalsChanged = retainedOriginals,
        )
        fun failed(failure: PhotoCurationFailure) = Reduction(failure = failure)
    }
}

internal fun reduceCuration(
    snapshot: CurationSnapshot,
    command: PhotoCurationCommand,
    clockMillis: () -> Long,
    removalRequestId: () -> String,
): Reduction = when (command) {
    is PhotoCurationCommand.Include -> include(snapshot, command.photoId)
    is PhotoCurationCommand.Exclude -> exclude(snapshot, command.photoId, command.reason)
    is PhotoCurationCommand.Reorder -> reorder(snapshot, command.orderedPhotoIds)
    is PhotoCurationCommand.RequestRemoval -> requestRemoval(snapshot, command.photoId, command.reason, clockMillis, removalRequestId)
    is PhotoCurationCommand.RestorePendingRemoval -> restoreRemoval(snapshot, command.photoId)
    is PhotoCurationCommand.CompleteRemoval -> completeRemoval(snapshot, command)
    is PhotoCurationCommand.Undo -> undo(snapshot, command.token)
}

private fun include(s: CurationSnapshot, id: String): Reduction = mutatePhoto(s, id) { p ->
    if (p.state == CuratedPhotoState.REMOVAL_PENDING) return@mutatePhoto pending(p)
    if (p.state == CuratedPhotoState.INCLUDED) return@mutatePhoto Reduction.unchanged()
    replace(s, p, p.copy(state = CuratedPhotoState.INCLUDED, exclusion = null))
}

private fun exclude(s: CurationSnapshot, id: String, reason: PhotoCurationReason): Reduction = mutatePhoto(s, id) { p ->
    if (p.state == CuratedPhotoState.REMOVAL_PENDING) return@mutatePhoto pending(p)
    if (p.state == CuratedPhotoState.EXCLUDED && p.exclusion == reason) return@mutatePhoto Reduction.unchanged()
    replace(s, p, p.copy(state = CuratedPhotoState.EXCLUDED, exclusion = reason))
}

private fun reorder(s: CurationSnapshot, ids: List<String>): Reduction {
    val current = s.orderedPhotos()
    if (ids.size > MAX_CURATED_PHOTOS || ids.size != current.size || ids.toSet().size != ids.size)
        return invalid("ORDER_NOT_A_PERMUTATION", "Photo order must contain every photo exactly once.")
    val byId = current.associateBy { it.id }
    if (ids.any { it !in byId }) return invalid("ORDER_UNKNOWN_PHOTO", "Photo order contains an unknown photo.")
    if (ids == current.map { it.id }) return Reduction.unchanged()
    return Reduction.changed(ids.mapIndexed { i, id -> requireNotNull(byId[id]).copy(position = i) })
}

private fun requestRemoval(
    s: CurationSnapshot,
    id: String,
    reason: PhotoRemovalReason,
    clock: () -> Long,
    requestId: () -> String,
): Reduction = mutatePhoto(s, id) { p ->
    if (p.state == CuratedPhotoState.REMOVAL_PENDING) return@mutatePhoto pending(p)
    val rid = requestId().also { stableId(it, "removal request") }
    if (s.photos.any { it.pendingRemoval?.requestId == rid }) {
        return@mutatePhoto invalid("REMOVAL_REQUEST_DUPLICATE", "Removal request identity is already active.")
    }
    val previous = if (p.state == CuratedPhotoState.EXCLUDED) RestorablePhotoState.EXCLUDED else RestorablePhotoState.INCLUDED
    val staged = p.copy(
        state = CuratedPhotoState.REMOVAL_PENDING,
        exclusion = null,
        pendingRemoval = PendingPhotoRemoval(rid, clock(), reason, previous, p.exclusion),
    )
    replace(s, p, staged, removalLifecycle = true)
}

private fun restoreRemoval(s: CurationSnapshot, id: String): Reduction = mutatePhoto(s, id) { p ->
    val pending = p.pendingRemoval ?: return@mutatePhoto invalid("REMOVAL_NOT_PENDING", "Photo is not pending removal.")
    val restored = if (pending.previousState == RestorablePhotoState.EXCLUDED)
        p.copy(state = CuratedPhotoState.EXCLUDED, exclusion = pending.previousExclusion, pendingRemoval = null)
    else p.copy(state = CuratedPhotoState.INCLUDED, pendingRemoval = null)
    replace(s, p, restored, removalLifecycle = true)
}

private fun completeRemoval(s: CurationSnapshot, c: PhotoCurationCommand.CompleteRemoval): Reduction = mutatePhoto(s, c.photoId) { p ->
    val pending = p.pendingRemoval ?: return@mutatePhoto invalid("REMOVAL_NOT_PENDING", "Photo is not pending removal.")
    if (pending.requestId != c.removalRequestId) return@mutatePhoto invalid("REMOVAL_REQUEST_MISMATCH", "Removal proof does not match the staged request.")
    if (p.sha256 != c.removedOriginalSha256) return@mutatePhoto invalid("REMOVED_ORIGINAL_MISMATCH", "Removal proof does not match the original SHA-256.")
    Reduction.changed(
        s.orderedPhotos().filter { it.id != p.id }.mapIndexed { i, photo -> photo.copy(position = i) },
        retainedOriginals = true,
    )
}

private inline fun mutatePhoto(s: CurationSnapshot, id: String, block: (CuratedPhoto) -> Reduction): Reduction {
    val photo = s.photos.firstOrNull { it.id == id } ?: return Reduction.failed(PhotoCurationFailure.PhotoNotFound(id))
    return block(photo)
}

private fun replace(
    s: CurationSnapshot,
    old: CuratedPhoto,
    next: CuratedPhoto,
    removalLifecycle: Boolean = false,
    retainedOriginals: Boolean = false,
) = Reduction.changed(
    s.orderedPhotos().map { if (it.id == old.id) next else it },
    removalLifecycle = removalLifecycle,
    retainedOriginals = retainedOriginals,
)

private fun pending(p: CuratedPhoto) = Reduction.failed(PhotoCurationFailure.PhotoPendingRemoval(p.id, requireNotNull(p.pendingRemoval).requestId))
internal fun invalid(code: String, message: String) = Reduction.failed(PhotoCurationFailure.InvalidCommand(code, message))
