package com.realityforge.scanner.ui.navigation

import android.content.Context
import com.realityforge.scanner.R
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.application.ProjectCommandAdapter
import com.realityforge.scanner.data.curation.*
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.jobs.toSnapshot
import com.realityforge.scanner.ui.processing.DurableJobUiAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One database, one project-operation gate, and the actual foreground execution owner. */
fun installedScannerFeatures(model: ScannerViewModel, context: Context): ScannerFeatureServices {
    val root = model.compositionRoot
    val commands = ProjectCommandAdapter(root, context)
    val selection = object : PhotoCurationService {
        override suspend fun state(projectId: String) = commands.selection(projectId)
        override suspend fun selectedPhotos(projectId: String) = state(projectId)?.selectedPhotoSet
        override suspend fun execute(command: PhotoCurationCommand) = withContext(Dispatchers.IO) {
            check(!model.busy.value && model.scannerState.value.pendingRequests.isEmpty()) {
                "Finish the current operation before changing photo selection."
            }
            root.curation.execute(command)
        }
    }
    return ScannerFeatureServices(
        library = commands.library,
        curation = selection,
        removePhotos = { directives ->
            withContext(Dispatchers.IO) {
                directives.forEach { directive ->
                    when (directive) {
                        is PhotoRemovalDirective.Stage -> root.repository.deletePhoto(directive.projectId,
                            directive.photoId, directive.sourceProjectRevision, directive.request.requestId,
                            directive.photoSha256)
                        is PhotoRemovalDirective.Cancel -> {
                            // The committed curation transaction already restored the original.
                            check(root.curation.state(directive.projectId)?.photos?.any {
                                it.id == directive.photoId && it.pendingRemoval == null
                            } == true) { "The removal could not be restored." }
                        }
                    }
                }
            }
        },
        reconstructSelection = { action, lease -> model.reconstruct(requireNotNull(action.focal35mm), lease) },
        jobStates = { projectId, revision ->
            root.database.jobDao().observeLatestForProject(projectId, revision).map { row ->
                row?.toSnapshot()?.let {
                    DurableJobUiAdapter.map(it, revision, false,
                        { stage -> stage.name.lowercase().replace('_', ' ') }, { code ->
                            when (code) {
                                "CAMERA_REGISTRATION_FAILED" -> context.getString(R.string.a074_geometry_unestablished)
                                else -> code.replace('_', ' ')
                            }
                        })
                }
            }
        },
        jobAction = { action -> root.execution.dispatchUiAction(action) },
    )
}
