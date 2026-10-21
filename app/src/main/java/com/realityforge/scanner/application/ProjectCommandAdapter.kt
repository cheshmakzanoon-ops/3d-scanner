package com.realityforge.scanner.application

import android.content.Context
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.curation.CuratedPhotoState
import com.realityforge.scanner.data.curation.DefaultPhotoCurationService
import com.realityforge.scanner.data.curation.PhotoCurationCommand
import com.realityforge.scanner.data.curation.PhotoCurationOutcome
import com.realityforge.scanner.data.curation.PhotoCurationState
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.curation.RoomPhotoCurationStore
import com.realityforge.scanner.data.library.ProjectLibraryService
import com.realityforge.scanner.data.library.RoomProjectLibraryPort
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.domain.ProjectRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** UI commands bind a revision before suspending and use the same exclusion as reconstruction. */
class ProjectCommandAdapter(private val root: ScannerCompositionRoot, context: Context) {
    val library = ProjectLibraryService(RoomProjectLibraryPort(root.database,
        File(context.applicationContext.filesDir, "projects")), File(context.applicationContext.filesDir, "projects"))
    private val curation = DefaultPhotoCurationService(RoomPhotoCurationStore(root.database))

    suspend fun rename(source: ProjectRevision, name: String): String = root.repository.withProjectAccess(
        ProjectOperationRequest.create(source.projectId, ProjectOperationKind.LIBRARY_COMMAND, source.revision),
    ) {
        check(library.rename(source.projectId, source.revision, name)) { "Project changed before rename." }
        "Project renamed."
    }

    suspend fun duplicate(source: ProjectRevision, name: String,
        progress: (Long, Long) -> Unit = { _, _ -> }): String = root.repository.withProjectAccess(
        ProjectOperationRequest.create(source.projectId, ProjectOperationKind.LIBRARY_COMMAND, source.revision),
    ) {
        library.duplicate(source.projectId, source.revision, name) { progress(it.copiedBytes, it.totalBytes) }
        "Independent project copy saved in the library."
    }

    suspend fun selection(projectId: String): PhotoCurationState? = withContext(Dispatchers.IO) {
        root.repository.withProjectAccess(ProjectOperationRequest.create(projectId, ProjectOperationKind.READ_INPUTS)) {
            curation.state(projectId)
        }
    }

    suspend fun curate(source: ProjectRevision,
        command: (PhotoCurationState) -> PhotoCurationCommand): PhotoCurationOutcome = root.repository.withProjectAccess(
        ProjectOperationRequest.create(source.projectId, ProjectOperationKind.MUTATE_INPUTS, source.revision),
    ) {
        val state = requireNotNull(curation.state(source.projectId)) { "Project no longer exists." }
        check(state.revision.projectRevision == source.revision) { "Photo selection changed." }
        val request = command(state)
        check(request.projectId == source.projectId && request.expectedRevision == state.revision)
        curation.execute(request).also {
            if (it is PhotoCurationOutcome.Rejected) error("Photo selection was not changed: ${it.failure}")
        }
    }

    /** The deletion service takes its own C6 lease and persists the confirmation before cleanup. */
    suspend fun removePhoto(source: ProjectRevision, confirmation: PhotoRemovalRequest): String {
        confirmation.requireSource(source)
        root.repository.deletePhoto(source.projectId, confirmation.photoId, source.revision,
            confirmation.pendingRemovalRequestId, confirmation.originalSha256)
        return "Original photograph removed from this project. Reconstruct to update its result."
    }
}

/** An immutable copy of exactly what was displayed before the permanent-removal alert opened. */
data class PhotoRemovalRequest private constructor(
    val project: ProjectRevision,
    val photoId: String,
    val originalSha256: String,
    val originalBytes: Long,
    val pendingRemovalRequestId: String?,
) {
    fun requireSource(source: ProjectRevision) {
        check(source == project) { "The selected project or revision changed. Review the photograph again." }
    }

    fun matches(source: ProjectRevision, photo: ScanPhoto): Boolean =
        runCatching { this == bind(source, photo) }.getOrDefault(false)

    companion object {
        fun bind(source: ProjectRevision, photo: ScanPhoto): PhotoRemovalRequest {
            require(photo.projectId == source.projectId && photo.id.isNotBlank()) {
                "The photograph does not belong to this project."
            }
            require(photo.sha256.matches(Regex("[a-f0-9]{64}")) && photo.bytes > 0) {
                "Original photograph identity is incomplete. Refresh before removing it."
            }
            val curation = photo.curationRecord?.let(PhotoCurationRecordCodec::decode)
            if (curation != null) {
                require(curation.id == photo.id && curation.sha256 == photo.sha256 &&
                    curation.position.toLong() == photo.selectionOrder &&
                    (curation.state == CuratedPhotoState.INCLUDED) == photo.included) {
                    "Photo selection metadata changed. Refresh before removing this photograph."
                }
            }
            return PhotoRemovalRequest(source, photo.id, photo.sha256, photo.bytes,
                curation?.pendingRemoval?.requestId)
        }
    }
}
