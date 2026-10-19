package com.realityforge.scanner.data.curation

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

data class ReconstructionSelectionLease(
    val projectId: String,
    val projectRevision: Long,
    val inputFingerprint: String,
    val orderedPhotoIds: List<String>,
) {
    init {
        stableId(projectId, "project")
        require(projectRevision >= 0)
        require(SHA256.matches(inputFingerprint))
        require(orderedPhotoIds.size <= MAX_CURATED_PHOTOS)
        orderedPhotoIds.forEach { stableId(it, "photo") }
        require(orderedPhotoIds.toSet().size == orderedPhotoIds.size)
    }
}

sealed interface ReconstructionAdmission {
    data class Ready(val lease: ReconstructionSelectionLease) : ReconstructionAdmission
    data class Blocked(val status: ReconstructionSelectionStatus, val selectedCount: Int, val requiredCount: Int) : ReconstructionAdmission
}

sealed interface PublicationSelectionCheck {
    data object Current : PublicationSelectionCheck
    data class Stale(val code: String, val expectedProjectRevision: Long, val actualProjectRevision: Long) : PublicationSelectionCheck
}

fun SelectedPhotoSet.admitForReconstruction(requiredCount: Int = 2): ReconstructionAdmission {
    require(requiredCount in 2..MAX_CURATED_PHOTOS)
    if (photos.size < requiredCount) {
        val blockedStatus = if (photos.isEmpty()) {
            ReconstructionSelectionStatus.EMPTY
        } else {
            ReconstructionSelectionStatus.INSUFFICIENT_PHOTOS
        }
        return ReconstructionAdmission.Blocked(blockedStatus, photos.size, requiredCount)
    }
    return ReconstructionAdmission.Ready(
        ReconstructionSelectionLease(
            projectId,
            revision.projectRevision,
            inputFingerprint,
            immutable(photos.map { it.id }),
        ),
    )
}

object ReconstructionSelectionGuard {
    fun verifyPublication(expected: ReconstructionSelectionLease, current: SelectedPhotoSet): PublicationSelectionCheck {
        val currentIds = current.photos.map { it.id }
        if (expected.projectId == current.projectId && expected.projectRevision == current.revision.projectRevision &&
            expected.inputFingerprint == current.inputFingerprint && expected.orderedPhotoIds == currentIds
        ) return PublicationSelectionCheck.Current
        val code = when {
            expected.projectId != current.projectId -> "PROJECT_MISMATCH"
            expected.projectRevision != current.revision.projectRevision -> "PROJECT_REVISION_CHANGED"
            expected.inputFingerprint != current.inputFingerprint -> "SELECTION_FINGERPRINT_CHANGED"
            else -> "SELECTION_ORDER_CHANGED"
        }
        return PublicationSelectionCheck.Stale(code, expected.projectRevision, current.revision.projectRevision)
    }
}

internal fun CurationSnapshot.toSelectedPhotoSet(): SelectedPhotoSet {
    val ordered = orderedPhotos()
    val selected = ordered.filter { it.state == CuratedPhotoState.INCLUDED }.mapIndexed { i, p -> SelectedPhoto(p.id, p.sha256, i) }
    val status = when {
        selected.isEmpty() -> ReconstructionSelectionStatus.EMPTY
        selected.size < 2 -> ReconstructionSelectionStatus.INSUFFICIENT_PHOTOS
        else -> ReconstructionSelectionStatus.READY
    }
    return SelectedPhotoSet(
        PHOTO_CURATION_SCHEMA_VERSION,
        projectId,
        revision,
        selectionFingerprint(projectId, selected),
        immutable(selected),
        ordered.size,
        ordered.count { it.state == CuratedPhotoState.EXCLUDED },
        ordered.count { it.state == CuratedPhotoState.REMOVAL_PENDING },
        status,
    )
}

internal fun CurationSnapshot.toPhotoCurationState() = PhotoCurationState(projectId, revision, immutable(orderedPhotos()), toSelectedPhotoSet())

internal fun curationStateFingerprint(s: CurationSnapshot) = digest {
    field("curation-state-v1")
    field(s.projectId)
    field(s.revision.projectRevision.toString())
    field(s.revision.curationVersion.toString())
    s.orderedPhotos().forEach { p ->
        field(p.id)
        field(p.sha256)
        field(p.position.toString())
        field(p.state.name)
        field(p.exclusion?.code?.name ?: "")
        field(p.exclusion?.note ?: "")
        field(p.pendingRemoval?.requestId ?: "")
        field(p.pendingRemoval?.requestedAtEpochMillis?.toString() ?: "")
        field(p.pendingRemoval?.reason?.code?.name ?: "")
        field(p.pendingRemoval?.reason?.note ?: "")
        field(p.pendingRemoval?.previousState?.name ?: "")
        field(p.pendingRemoval?.previousExclusion?.code?.name ?: "")
        field(p.pendingRemoval?.previousExclusion?.note ?: "")
    }
}

private fun selectionFingerprint(projectId: String, photos: List<SelectedPhoto>) = digest {
    field("selected-v1")
    field(projectId)
    photos.forEach {
        field(it.id)
        field(it.sha256)
        field(it.position.toString())
    }
}

private inline fun digest(write: Hasher.() -> Unit) = Hasher().apply(write).finish()

private class Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray()
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    fun finish() = digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
