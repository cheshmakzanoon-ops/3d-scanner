package com.realityforge.scanner.application

import android.content.Context
import com.realityforge.scanner.data.curation.DefaultPhotoCurationService
import com.realityforge.scanner.data.curation.PhotoCurationCommand
import com.realityforge.scanner.data.curation.PhotoCurationOutcome
import com.realityforge.scanner.data.curation.PhotoCurationState
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
}
