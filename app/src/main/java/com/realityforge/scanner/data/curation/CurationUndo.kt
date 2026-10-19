package com.realityforge.scanner.data.curation

internal fun undo(current: CurationSnapshot, token: CurationUndoToken): Reduction {
    if (token.projectId != current.projectId) return undoFailure("UNDO_PROJECT_MISMATCH", "Undo token belongs to another project.")
    if (token.afterRevision != current.revision) return undoFailure("UNDO_REVISION_CHANGED", "Project changed after this command.")
    if (token.afterStateFingerprint != curationStateFingerprint(current)) return undoFailure("UNDO_STATE_CHANGED", "Curation state changed after this command.")
    return try {
        val restored = CurationSnapshot(current.projectId, current.revision, immutable(token.beforePhotos))
        if (curationStateFingerprint(restored) == curationStateFingerprint(current)) Reduction.unchanged()
        else Reduction.changed(
            restored.orderedPhotos(),
            removalLifecycle = pendingIds(current) != pendingIds(restored),
        )
    } catch (bad: IllegalArgumentException) {
        undoFailure("UNDO_TOKEN_INVALID", bad.message ?: "Undo token is invalid.")
    }
}

private fun pendingIds(s: CurationSnapshot) = s.photos.mapNotNull { it.pendingRemoval?.requestId }.toSet()
private fun undoFailure(code: String, message: String) = Reduction.failed(PhotoCurationFailure.UndoNotApplicable(code, message))

internal fun removalDirectives(before: CurationSnapshot, after: CurationSnapshot): List<PhotoRemovalDirective> {
    val old = before.photos.associateBy { it.id }
    val out = ArrayList<PhotoRemovalDirective>()
    after.orderedPhotos().forEach { photo ->
        val prior = old[photo.id]?.pendingRemoval
        val next = photo.pendingRemoval
        if (next != null && prior?.requestId != next.requestId) out += PhotoRemovalDirective.Stage(
            after.projectId, photo.id, photo.sha256, next, after.revision.projectRevision,
        )
        if (prior != null && next == null) out += PhotoRemovalDirective.Cancel(
            after.projectId, photo.id, prior.requestId, after.revision.projectRevision,
        )
    }
    return immutable(out)
}
