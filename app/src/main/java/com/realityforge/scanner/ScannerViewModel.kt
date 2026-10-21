package com.realityforge.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.realityforge.scanner.application.OperationCoordinator
import com.realityforge.scanner.capture.session.CapturedPhoto
import com.realityforge.scanner.capture.session.CaptureCommitOutcome
import com.realityforge.scanner.data.importing.ImportRequest
import com.realityforge.scanner.data.importing.ImportSource
import com.realityforge.scanner.data.importing.ImportPublicationResult
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.BoundExternalRequestCodec
import com.realityforge.scanner.domain.CalibrationState
import com.realityforge.scanner.domain.OperationContext
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.OperationProgress
import com.realityforge.scanner.domain.PendingRequestState
import com.realityforge.scanner.domain.PresentationMessage
import com.realityforge.scanner.domain.PreviewState
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.domain.RequestId
import com.realityforge.scanner.domain.ScannerState
import com.realityforge.scanner.domain.SelectedProjectState
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.SparsePipeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SavedStateHandle())

    private val root = (app as RealityForgeApplication).compositionRoot
    val repository = root.repository
    val compositionRoot get() = root
    private val dispatchers = root.dispatchers
    private val coordinator = OperationCoordinator()
    private val exportTransfers = com.realityforge.scanner.application.BoundSafExportService(app, root)

    val selectedId = MutableStateFlow(savedStateHandle.get<String>(KEY_SELECTED_PROJECT))
    val projects = repository.dao.observeProjects().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val selectedProject = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.dao.observeProject(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val photos = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.dao.observePhotos(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Compatibility flows remain available while Agent 071 migrates ScannerApp to ScannerState.
    val notice = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow<SparsePipeline.Progress?>(null)
    val preview = MutableStateFlow<JSONObject?>(null)
    val calibrationStatus = MutableStateFlow("No calibration: estimated pinhole")

    private val activeOperation = MutableStateFlow<OperationContext?>(null)
    private val pendingRequests = MutableStateFlow<List<PendingRequestState>>(emptyList())
    private val archiveRestoreId = MutableStateFlow<String?>(null)
    val pendingArchiveRestoreId: StateFlow<String?> = archiveRestoreId
    private val typedPreview = MutableStateFlow<PreviewState>(PreviewState.None)
    private val typedCalibration = MutableStateFlow<CalibrationState>(CalibrationState.None)
    private val presentationMessage = MutableStateFlow<PresentationMessage?>(null)
    private val selectionGenerationState = MutableStateFlow(savedStateHandle.get<Long>(KEY_SELECTION_GENERATION) ?: 0L)

    private data class SelectionSlice(
        val selected: SelectedProjectState?,
        val generation: Long,
    )

    private data class OperationSlice(
        val active: OperationContext?,
        val pending: List<PendingRequestState>,
        val isBusy: Boolean,
        val progress: OperationProgress?,
    )

    private data class PresentationSlice(
        val preview: PreviewState,
        val calibration: CalibrationState,
        val message: PresentationMessage?,
    )

    private val selectionSlice = combine(selectedProject, selectedId, selectionGenerationState) { current, id, generation ->
        val project = current?.takeIf { it.id == id }
        SelectionSlice(
            selected = project?.let {
                SelectedProjectState(it.id, it.revision, it.name, it.state)
            },
            generation = generation,
        )
    }
    private val operationSlice = combine(busy, activeOperation, pendingRequests, progress) { running, active, pending, legacyProgress ->
        OperationSlice(
            active = active,
            pending = pending,
            isBusy = running,
            progress = legacyProgress?.let { OperationProgress(it.stage, it.completed, it.total) },
        )
    }
    private val presentationSlice = combine(typedPreview, typedCalibration, presentationMessage) { currentPreview, calibration, message ->
        PresentationSlice(currentPreview, calibration, message)
    }

    val scannerState: StateFlow<ScannerState> = combine(
        selectionSlice,
        operationSlice,
        presentationSlice,
    ) { selection, operation, presentation ->
        ScannerState(
            selectedProject = selection.selected,
            selectionGeneration = selection.generation,
            activeOperation = operation.active,
            pendingRequests = operation.pending,
            isBusy = operation.isBusy,
            progress = operation.progress,
            preview = presentation.preview,
            calibration = presentation.calibration,
            message = presentation.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScannerState())

    private var task: Job? = null
    private var previewTask: Job? = null
    private var selectionGeneration = selectionGenerationState.value

    private val recovery = viewModelScope.async(dispatchers.io) { root.execution.awaitRecovery() }

    init {
        restorePendingRequests()
        viewModelScope.launch {
            root.execution.current.collect { job ->
                val processing = job?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.RUNNING ||
                    job?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.QUEUED
                if (coordinator.activeOperation() == null) busy.value = processing
                if (job?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.COMPLETED && selectedId.value == job.input.projectId) {
                    val source = ProjectRevision(job.input.projectId, job.input.projectRevision)
                    val saved = withContext(dispatchers.io) {
                        runCatching { repository.currentPreview(source.projectId)?.let(
                            com.realityforge.scanner.data.reopening.PreviewUiSummary::fromValidated) }.getOrNull()
                    }
                    if (selectedId.value == source.projectId && saved != null &&
                        saved.optLong("revision", -1) == source.revision && selectedProject.value?.revision == source.revision) {
                        preview.value = saved
                        typedPreview.value = PreviewState.Ready(source, saved.getString("artifactKey"), representationOf(saved))
                    }
                }
                if (!processing && coordinator.activeOperation() == null) progress.value = null
            }
        }
        viewModelScope.launch {
            root.execution.progress.collect { update ->
                if (update != null && update.projectId == selectedId.value && root.execution.current.value?.jobId?.value == update.jobId)
                    progress.value = update.progress
            }
        }
        viewModelScope.launch {
            // Existing ScannerApp clears notice after displaying its snackbar. Keep typed state in sync.
            notice.drop(1).collect { current ->
                if (current == null) presentationMessage.value = null
            }
        }
    }

    /** Called from UI actions before any suspension, so exports/mutations cannot overlap. */
    private fun reserveOperation(kind: OperationKind, source: ProjectRevision?): OperationContext? {
        if (!busy.compareAndSet(false, true)) return null
        val context = coordinator.reserveDirect(kind, source, selectionGeneration)
        if (context == null) {
            busy.value = false
            publishMessage(
                "operation.pending_request",
                "Finish or cancel the open document/camera request before starting another operation.",
            )
            return null
        }
        operationStarted(context)
        return context
    }

    private fun claimPending(requestId: String, kind: OperationKind): Pair<BoundExternalRequest, OperationContext>? {
        if (!busy.compareAndSet(false, true)) return null
        val claimed = runCatching {
            coordinator.claimPending(RequestId(requestId), kind)
        }.getOrNull()
        if (claimed == null) {
            busy.value = false
            publishMessage(
                "operation.stale_request",
                "This result no longer matches an active request. No project was changed.",
            )
            return null
        }
        persistPendingRequests()
        operationStarted(claimed.second)
        return claimed
    }

    private fun operationStarted(context: OperationContext) {
        previewTask?.cancel()
        advanceSelectionGeneration()
        activeOperation.value = context
    }

    private fun finishOperation(context: OperationContext) {
        if (coordinator.finish(context.operationId)) {
            activeOperation.value = null
            busy.value = false
        }
    }

    private fun advanceSelectionGeneration(): Long {
        selectionGeneration += 1L
        selectionGenerationState.value = selectionGeneration
        savedStateHandle[KEY_SELECTION_GENERATION] = selectionGeneration
        return selectionGeneration
    }

    private fun selectedSource(): ProjectRevision? {
        val id = selectedId.value ?: run {
            publishMessage("project.none_selected", "Select a project first.")
            return null
        }
        val project = selectedProject.value?.takeIf { it.id == id } ?: projects.value.firstOrNull { it.id == id } ?: run {
            publishMessage("project.not_loaded", "The selected project is not available yet.")
            return null
        }
        return ProjectRevision(project.id, project.revision)
    }

    private suspend fun requireCurrent(source: ProjectRevision, allowPendingPhotoDeletion: Boolean = false): ScanProject {
        val project = requireNotNull(repository.dao.project(source.projectId)) {
            "Project no longer exists."
        }
        check(project.revision == source.revision) {
            "Project changed after this request was opened. Repeat the operation for the current revision."
        }
        check(project.state != "DELETING" && (allowPendingPhotoDeletion || project.state != "DELETING_PHOTO")) {
            "Project deletion is pending. Retry deletion before changing this project."
        }
        return project
    }

    private fun publishMessage(code: String, text: String, arguments: Map<String, String> = emptyMap()) {
        presentationMessage.value = PresentationMessage(code, text, arguments)
        notice.value = text
    }

    private fun clearVisiblePreview(projectId: String) {
        if (selectedId.value == projectId) {
            preview.value = null
            typedPreview.value = PreviewState.None
        }
    }

    private fun restorePendingRequests() {
        val encoded = savedStateHandle.get<ArrayList<String>>(KEY_PENDING_REQUESTS).orEmpty()
        val restored = encoded.mapNotNull { record ->
            runCatching { BoundExternalRequestCodec.decode(record) }.getOrNull()
        }
        coordinator.restorePending(restored)
        val archive = savedStateHandle.get<String>(KEY_ARCHIVE_RESTORE)?.let {
            runCatching { com.realityforge.scanner.domain.CaptureArchiveRestoreRequest.decode(it) }.getOrNull()
        }
        coordinator.restoreArchiveRequest(if (restored.isEmpty()) archive else null)
        syncPendingRequests()
        if (restored.size != encoded.size) {
            publishMessage(
                "operation.pending_restore_rejected",
                "An invalid pending request was discarded; no project data was changed.",
            )
            persistPendingRequests()
        }
    }

    private fun persistPendingRequests() {
        savedStateHandle[KEY_PENDING_REQUESTS] = ArrayList(
            coordinator.pendingRequests().map(BoundExternalRequestCodec::encode),
        )
        savedStateHandle[KEY_ARCHIVE_RESTORE] = coordinator.pendingArchiveRestore()?.encode()
        syncPendingRequests()
    }

    private fun syncPendingRequests() {
        archiveRestoreId.value = coordinator.pendingArchiveRestore()?.requestId?.value
        pendingRequests.value = coordinator.pendingRequests().map(PendingRequestState::from)
    }

    private fun bindRequest(request: BoundExternalRequest?): String? {
        if (request == null) {
            publishMessage(
                "operation.request_conflict",
                "Another request or operation is already active.",
            )
            return null
        }
        persistPendingRequests()
        return request.requestId.value
    }

    fun cancelPendingRequest(requestId: String) {
        val removed = runCatching { coordinator.cancelPending(RequestId(requestId)) }.getOrNull()
        val archiveRemoved = coordinator.cancelArchiveRestore(RequestId(requestId))
        if (removed != null || archiveRemoved) persistPendingRequests()
    }

    fun beginCaptureArchiveRestoreRequest(): String? {
        if (busy.value) return null
        val request = coordinator.bindArchiveRestore(selectionGeneration) ?: return null
        persistPendingRequests()
        return request.requestId.value
    }

    fun completeCaptureArchiveRestore(requestId: String, uri: Uri) {
        if (!busy.compareAndSet(false, true)) return
        val context = coordinator.claimArchiveRestore(RequestId(requestId))
        if (context == null) { busy.value = false; return }
        persistPendingRequests()
        operationStarted(context)
        task = viewModelScope.launch {
            var restoredId: String? = null
            try {
                recovery.await()
                restoredId = withContext(dispatchers.io) {
                    requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) {
                        "The archive provider did not return a readable stream."
                    }.use { repository.restoreCaptureArchive(it) }
                }
                publishMessage("archive.restored", "Capture archive restored as a new local project.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishMessage("archive.restore_failed", failure.message ?: "The capture archive could not be restored.")
            } finally {
                finishOperation(context)
            }
            restoredId?.let(::select)
        }
    }

    fun beginImportRequest(): String? {
        val source = selectedSource() ?: return null
        return bindRequest(coordinator.bindImport(source, selectionGeneration))
    }

    fun beginCaptureRequest(): String? {
        val source = selectedSource() ?: return null
        return bindRequest(coordinator.bindCapture(source, selectionGeneration))
    }

    fun beginCalibrationRequest(): String? {
        val source = selectedSource() ?: return null
        return bindRequest(coordinator.bindCalibration(source, selectionGeneration))
    }

    fun beginCaptureBundleExportRequest(): String? {
        val source = selectedSource() ?: return null
        return bindRequest(coordinator.bindCaptureBundle(source, selectionGeneration))
    }

    fun beginDerivedExportRequest(identity: com.realityforge.scanner.export.ArtifactIdentity,
        option: com.realityforge.scanner.export.ExportOption, acknowledgedArbitraryScale: Boolean): String? {
        if (option.id !in com.realityforge.scanner.export.LegacyMeshDerivedExports.IDS ||
            option.format != com.realityforge.scanner.export.LegacyMeshDerivedExports.format(option.id) ||
            option.representation != com.realityforge.scanner.export.AssetRepresentation.OBSERVED_SURFACE ||
            !option.arbitraryScale || option.photographic || option.contentSha256 == null ||
            (option.format == com.realityforge.scanner.export.ExportFormat.STL && !acknowledgedArbitraryScale)) return null
        val source = selectedSource() ?: return null
        val project = projects.value.firstOrNull { it.id == source.projectId } ?: return null
        if (source.projectId != identity.projectId || source.revision != identity.revision ||
            project.resultKey != identity.artifactKey || project.resultRevision != identity.revision) return null
        val selection = com.realityforge.scanner.domain.DerivedExportSelection(identity.sha256, option.bytes,
            option.contentSha256, acknowledgedArbitraryScale)
        return bindRequest(coordinator.bindArtifact(source, selectionGeneration, identity.artifactKey,
            identity.revision, option.id, selection))
    }

    fun beginArtifactExportRequest(extension: String): String? {
        if (extension !in SUPPORTED_EXPORT_EXTENSIONS) return null
        val source = selectedSource() ?: return null
        val project = selectedProject.value?.takeIf { it.id == source.projectId }
            ?: projects.value.firstOrNull { it.id == source.projectId } ?: return null
        val resultRevision = project.resultRevision
        val resultKey = project.resultKey
        if (resultRevision == null || resultKey == null || resultRevision != source.revision) {
            publishMessage("export.no_current_result", "No current preview is available to export.")
            return null
        }
        return bindRequest(
            coordinator.bindArtifact(
                source,
                selectionGeneration,
                resultKey,
                resultRevision,
                extension,
            ),
        )
    }

    /** Bind the selected project before dispatching any UI-initiated library or curation mutation. */
    fun projectCommand(allowPendingPhotoDeletion: Boolean = false, action: suspend (ProjectRevision) -> String) {
        val source = selectedSource() ?: return
        val context = reserveOperation(OperationKind.EDIT_PROJECT, source) ?: return
        task = viewModelScope.launch {
            try {
                recovery.await()
                val message = withContext(dispatchers.io) {
                    requireCurrent(source, allowPendingPhotoDeletion)
                    action(source)
                }
                publishMessage("project.command_completed", message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishMessage("project.command_failed", failure.message ?: "The project could not be updated.")
            } finally {
                finishOperation(context)
            }
            if (selectedId.value == source.projectId) select(source.projectId)
        }
    }

    fun create(name: String) {
        val context = reserveOperation(OperationKind.CREATE_PROJECT, null) ?: return
        task = viewModelScope.launch {
            var created: String? = null
            try {
                recovery.await()
                created = withContext(dispatchers.io) { repository.create(name) }
            } catch (_: CancellationException) {
                publishMessage("project.create_interrupted", "Project creation interrupted.")
            } catch (e: Exception) {
                publishMessage("project.create_failed", e.message ?: "Project creation failed.")
            } finally {
                finishOperation(context)
            }
            created?.let { select(it) }
        }
    }

    fun select(id: String?) {
        if (coordinator.activeOperation() != null) return
        previewTask?.cancel()
        val generation = advanceSelectionGeneration()
        savedStateHandle[KEY_SELECTED_PROJECT] = id
        selectedId.value = id
        preview.value = null
        typedPreview.value = PreviewState.None
        calibrationStatus.value = if (id == null) "" else "Reading lens profile..."
        typedCalibration.value = if (id == null) CalibrationState.None else CalibrationState.Loading(id)
        if (id != null) {
            val initial = projects.value.firstOrNull { it.id == id }
            if (initial != null) typedPreview.value = PreviewState.Loading(ProjectRevision(id, initial.revision))
            previewTask = viewModelScope.launch {
                try {
                    recovery.await()
                    val loaded = withContext(dispatchers.io) {
                        val project = requireNotNull(repository.dao.project(id)) { "Project no longer exists." }
                        val calibration = runCatching { LensCalibration.read(repository, id) }
                        val status = calibration.fold(
                            { profile ->
                                if (profile == null) {
                                    "No calibration: estimated pinhole" to CalibrationState.Estimated(id)
                                } else {
                                    "Calibrated: ${profile.acceptedViews} board views · RMS ${"%.2f".format(profile.reprojectionRmsPixels)} px" to
                                        CalibrationState.Calibrated(id, profile.acceptedViews, profile.reprojectionRmsPixels)
                                }
                            },
                            { error ->
                                "Calibration unreadable: ${error.message}; recalibrate this project" to
                                    CalibrationState.Invalid(id, error.message.orEmpty())
                            },
                        )
                        Triple(project, status, repository.currentPreview(id)?.let(
                            com.realityforge.scanner.data.reopening.PreviewUiSummary::fromValidated))
                    }
                    ensureActive()
                    // Main-thread publication cannot race another UI operation's reservation.
                    if (selectedId.value == id && selectionGeneration == generation && coordinator.activeOperation() == null) {
                        calibrationStatus.value = loaded.second.first
                        typedCalibration.value = loaded.second.second
                        preview.value = loaded.third
                        typedPreview.value = loaded.third?.let { json ->
                            PreviewState.Ready(
                                project = ProjectRevision(id, loaded.first.revision),
                                artifactKey = json.optString("artifactKey"),
                                representation = representationOf(json),
                            )
                        } ?: PreviewState.None
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (selectedId.value == id && selectionGeneration == generation) {
                        preview.value = null
                        typedPreview.value = PreviewState.Failed(id, e.message.orEmpty())
                        publishMessage(
                            "preview.open_failed",
                            "Saved preview could not be opened: ${e.message}. Original photographs are unchanged.",
                        )
                    }
                }
            }
        }
    }

    private fun representationOf(json: JSONObject): String = when {
        json.optJSONObject("texturePreview") != null || json.optJSONObject("texturedPreview") != null -> "photographic_mesh"
        json.optJSONObject("meshPreview") != null -> "observed_mesh"
        json.optJSONObject("densePreview") != null -> "dense_points"
        else -> "sparse_points"
    }

    /** Compatibility entry point. Agent 071 must bind before launching the picker. */
    fun import(uris: List<Uri>) {
        val requestId = pendingId(OperationKind.IMPORT_PHOTOS) ?: beginImportRequest() ?: return
        completeImport(requestId, uris)
    }

    fun completeImport(requestId: String, uris: List<Uri>) {
        if (uris.isEmpty()) {
            cancelPendingRequest(requestId)
            return
        }
        val (request, context) = claimPending(requestId, OperationKind.IMPORT_PHOTOS) ?: return
        request as BoundExternalRequest.ImportPhotos
        clearVisiblePreview(request.project.projectId)
        task = viewModelScope.launch {
            var imported = 0
            var duplicates = 0
            var rejected = 0
            var lastError = ""
            try {
                recovery.await()
                withContext(dispatchers.io) { requireCurrent(request.project) }
                val result = repository.importBatch(ImportRequest(
                    context.operationId.value, request.project.projectId, request.project.revision,
                    uris.map { ImportSource(it.toString()) },
                )) { update ->
                    progress.value = SparsePipeline.Progress("Importing photographs", update.sourceIndex, update.sourceCount)
                }
                imported = result.importedCount
                duplicates = result.duplicateCount
                rejected = result.rejectedCount
                lastError = result.photos.mapNotNull { (it.outcome as? ImportPublicationResult.Rejected)?.message }.lastOrNull().orEmpty()
                clearVisiblePreview(request.project.projectId)
                publishMessage(
                    "import.completed",
                    "$imported imported; $duplicates duplicates; $rejected rejected; ${result.pendingCount} awaiting recovery." +
                        if (result.pendingCount > 0) " Reopen this project to reconcile the pending photograph before importing again."
                        else if (rejected > 0) " $lastError" else "",
                    mapOf(
                        "imported" to imported.toString(),
                        "duplicates" to duplicates.toString(),
                        "rejected" to rejected.toString(),
                    ),
                )
            } catch (_: CancellationException) {
                publishMessage(
                    "import.interrupted",
                    "Import interrupted. $imported completed photographs are safe.",
                )
            } catch (e: Exception) {
                publishMessage("import.failed", e.message ?: "Import failed.")
            } finally {
                progress.value = null
                finishOperation(context)
            }
        }
    }

    /** Compatibility entry point. Agent 071 must bind before opening CameraScreen. */
    fun captureReady(file: File) {
        val requestId = pendingId(OperationKind.CAPTURE_PHOTO) ?: beginCaptureRequest()
        if (requestId == null) {
            file.delete()
            return
        }
        completeCapture(requestId, file)
    }

    fun completeCapture(requestId: String, file: File) {
        val claimed = claimPending(requestId, OperationKind.CAPTURE_PHOTO)
        if (claimed == null) {
            file.delete()
            return
        }
        val (request, context) = claimed
        request as BoundExternalRequest.CapturePhoto
        clearVisiblePreview(request.project.projectId)
        task = viewModelScope.launch {
            try {
                recovery.await()
                withContext(dispatchers.io) { requireCurrent(request.project) }
                repository.importPhoto(request.project.projectId, Uri.fromFile(file))
                clearVisiblePreview(request.project.projectId)
                publishMessage("capture.saved", "Photograph saved locally.")
            } catch (_: CancellationException) {
                publishMessage("capture.import_interrupted", "Capture import interrupted.")
            } catch (e: Exception) {
                publishMessage("capture.import_failed", e.message ?: "Capture import failed.")
            } finally {
                file.delete()
                finishOperation(context)
            }
        }
    }

    /** CameraX awaits this result before reclaiming its staging file or admitting the next shot. */
    suspend fun publishCapturedPhoto(photo: CapturedPhoto): CaptureCommitOutcome {
        if (!busy.compareAndSet(false, true)) return CaptureCommitOutcome.Rejected("Another project operation is still active.")
        val claimed = coordinator.claimCapture(photo.sessionToken, photo.projectId, photo.revision)
        if (claimed == null) {
            busy.value = false
            return CaptureCommitOutcome.Rejected("The camera session is closed or its project revision changed.")
        }
        val (_, context) = claimed
        operationStarted(context)
        return try {
            recovery.await()
            val result = repository.importBatch(ImportRequest(context.operationId.value,
                photo.projectId, photo.revision, listOf(ImportSource(
                    Uri.fromFile(photo.stagedFile).toString(), photo.metadata.captureMetadata, photo.metadata.fields))))
            val outcome = result.photos.single().outcome
            when (outcome) {
                is ImportPublicationResult.Imported -> {
                    coordinator.advanceCapture(context, outcome.committedRevision)
                    persistPendingRequests()
                    clearVisiblePreview(photo.projectId)
                    CaptureCommitOutcome.Accepted(outcome.committedRevision)
                }
                is ImportPublicationResult.Duplicate -> {
                    coordinator.advanceCapture(context, outcome.revision)
                    persistPendingRequests()
                    CaptureCommitOutcome.Accepted(outcome.revision)
                }
                is ImportPublicationResult.PendingRecovery -> {
                    coordinator.cancelPending(claimed.first.requestId)
                    persistPendingRequests()
                    CaptureCommitOutcome.Rejected(outcome.message)
                }
                is ImportPublicationResult.Rejected -> CaptureCommitOutcome.Rejected(outcome.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CaptureCommitOutcome.Rejected(failure.message ?: "The photograph could not be committed.")
        } finally {
            finishOperation(context)
        }
    }

    /** Calibrate the bound project's lens from 10-40 on-device checkerboard images. */
    fun calibrateLens(uris: List<Uri>) {
        val requestId = pendingId(OperationKind.CALIBRATE_LENS) ?: beginCalibrationRequest() ?: return
        completeCalibration(requestId, uris)
    }

    fun completeCalibration(requestId: String, uris: List<Uri>) {
        if (uris.isEmpty()) {
            cancelPendingRequest(requestId)
            return
        }
        val (request, context) = claimPending(requestId, OperationKind.CALIBRATE_LENS) ?: return
        request as BoundExternalRequest.CalibrateLens
        task = viewModelScope.launch {
            try {
                recovery.await()
                withContext(dispatchers.io) { requireCurrent(request.project) }
                val session = withContext(dispatchers.compute) {
                    val inputs = uris.mapIndexed { index, uri ->
                        com.realityforge.scanner.capture.calibration.CalibrationImageInput(uri,
                            com.realityforge.scanner.capture.calibration.CalibrationInputIdentity("view-${index + 1}",
                                optical = com.realityforge.scanner.capture.calibration.CalibrationOpticalMetadata(
                                    evidenceSource = com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED)))
                    }
                    LensCalibration.calculateSession(getApplication(), inputs) { update ->
                        progress.value = SparsePipeline.Progress("Calibrating checkerboard lens", update.completed, update.total)
                    }
                }
                val record = when (session) {
                    is com.realityforge.scanner.capture.calibration.CalibrationSessionResult.Success -> session.record
                    is com.realityforge.scanner.capture.calibration.CalibrationSessionResult.Rejected -> error(session.failure.message)
                }
                val profile = record.profile
                ensureActive()
                val saved = repository.saveCalibration(
                    com.realityforge.scanner.capture.calibration.CalibrationOperationKey(request.project.projectId,
                        request.project.revision, context.operationId.value), record)
                check(saved is com.realityforge.scanner.capture.calibration.CalibrationProfileStoreResult.Saved) {
                    "Calibration could not be committed: $saved"
                }
                if (selectedId.value == request.project.projectId) {
                    preview.value = null
                    typedPreview.value = PreviewState.None
                    calibrationStatus.value =
                        "Calibrated: ${profile.acceptedViews} board views · RMS ${"%.2f".format(profile.reprojectionRmsPixels)} px"
                    typedCalibration.value = CalibrationState.Calibrated(
                        request.project.projectId,
                        profile.acceptedViews,
                        profile.reprojectionRmsPixels,
                    )
                }
                publishMessage(
                    "calibration.saved",
                    "Lens calibration saved locally; previous sparse results invalidated. Use the same lens/zoom/aspect for object photos.",
                )
            } catch (_: CancellationException) {
                publishMessage(
                    "calibration.interrupted",
                    "Calibration interrupted. Reopen the project to check the saved lens profile.",
                )
            } catch (e: Exception) {
                publishMessage("calibration.rejected", "Calibration rejected: ${e.message}")
            } finally {
                progress.value = null
                finishOperation(context)
            }
        }
    }

    fun clearLensCalibration() {
        val source = selectedSource() ?: return
        val context = reserveOperation(OperationKind.CLEAR_CALIBRATION, source) ?: return
        task = viewModelScope.launch {
            try {
                withContext(dispatchers.io + NonCancellable) {
                    requireCurrent(source)
                    val removed = repository.removeCalibration(
                        com.realityforge.scanner.capture.calibration.CalibrationOperationKey(source.projectId,
                            source.revision, context.operationId.value))
                    check(removed is com.realityforge.scanner.capture.calibration.CalibrationProfileStoreResult.Removed) {
                        "Calibration could not be removed: $removed"
                    }
                }
                if (selectedId.value == source.projectId) {
                    preview.value = null
                    typedPreview.value = PreviewState.None
                    calibrationStatus.value = "No calibration: estimated pinhole"
                    typedCalibration.value = CalibrationState.Estimated(source.projectId)
                }
                publishMessage(
                    "calibration.removed",
                    "Lens calibration removed. New scans use estimated pinhole intrinsics.",
                )
            } catch (e: Exception) {
                publishMessage("calibration.remove_failed", e.message ?: "Cannot remove lens calibration.")
            } finally {
                finishOperation(context)
            }
        }
    }

    fun reconstruct(focal35: Double, selection: com.realityforge.scanner.data.curation.ReconstructionSelectionLease? = null) {
        val source = selectedSource() ?: return
        val context = reserveOperation(OperationKind.RECONSTRUCT, source) ?: return
        task = viewModelScope.launch {
            try {
                recovery.await()
                if (selection != null) {
                    check(selection.projectId == source.projectId && selection.projectRevision == source.revision) {
                        "Photo selection changed before processing."
                    }
                    val current = withContext(dispatchers.io) { root.curation.selectedPhotos(source.projectId) }
                    check(current != null && com.realityforge.scanner.data.curation.ReconstructionSelectionGuard.verifyPublication(
                        selection, current) == com.realityforge.scanner.data.curation.PublicationSelectionCheck.Current) {
                        "Photo selection changed before processing."
                    }
                }
                root.execution.enqueue(source.projectId, source.revision, context.operationId.value, focal35)
                publishMessage("reconstruction.started", "Reconstruction is running as a saved processing job.")
            } catch (failure: Exception) {
                publishMessage("reconstruction.start_failed", failure.message ?: "Could not start processing.")
            } finally {
                finishOperation(context)
                busy.value = root.execution.current.value?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.RUNNING ||
                    root.execution.current.value?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.QUEUED
            }
        }
    }

    fun pause() {
        val durable = root.execution.current.value
        if (durable?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.RUNNING ||
            durable?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.QUEUED) {
            viewModelScope.launch { root.execution.pause(requireNotNull(durable).jobId) }
        } else task?.cancel()
    }

    fun resumeProcessing() {
        val durable = root.execution.current.value ?: return
        viewModelScope.launch {
            runCatching { root.execution.resume(durable.jobId) }.onFailure {
                publishMessage("reconstruction.resume_failed", it.message ?: "Could not resume processing.")
            }
        }
    }

    fun onHostStopped() {
        // Foreground processing is service-owned. Capture/import UI operations retain their own lifetime.
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun deleteSelected(confirmedSource: ProjectRevision? = null) {
        val source = selectedSource() ?: return
        if (confirmedSource != null && confirmedSource != source) {
            publishMessage("project.delete_stale", "The selected project changed. Open the deletion confirmation again.")
            return
        }
        val context = reserveOperation(OperationKind.DELETE_PROJECT, source) ?: return
        task = viewModelScope.launch {
            var deleted = false
            try {
                withContext(dispatchers.io) {
                    val current = requireNotNull(repository.dao.project(source.projectId)) { "Project no longer exists." }
                    check(current.revision == source.revision) { "Project changed after deletion was confirmed." }
                    repository.deleteConfirmed(source.projectId, source.revision)
                }
                deleted = true
            } catch (_: CancellationException) {
                publishMessage(
                    "project.delete_interrupted",
                    "Deletion interrupted; reopen the project library to check its state.",
                )
            } catch (e: Exception) {
                publishMessage("project.delete_failed", e.message ?: "Deletion failed.")
            } finally {
                finishOperation(context)
            }
            if (deleted && selectedId.value == source.projectId) select(null)
        }
    }

    /** Compatibility callback; identity must already have been saved before CreateDocument launched. */
    fun exportCaptureBundle(uri: Uri) {
        val requestId = pendingId(OperationKind.EXPORT_CAPTURE_BUNDLE) ?: return
        completeCaptureBundleExport(requestId, uri)
    }

    fun completeCaptureBundleExport(requestId: String, uri: Uri) {
        val (request, context) = claimPending(requestId, OperationKind.EXPORT_CAPTURE_BUNDLE) ?: return
        request as BoundExternalRequest.CaptureBundleExport
        task = viewModelScope.launch(dispatchers.io) {
            try {
                progress.value = SparsePipeline.Progress("Preparing capture archive", 0, 1)
                val outcome = exportTransfers.captureArchive(request, uri, ::exportProgress)
                publishTransferOutcome("capture_export", outcome, "Exported the original photographs and capture manifest.")
            } catch (_: CancellationException) {
                publishMessage(
                    "capture_export.interrupted",
                    "Capture export stopped. If the provider retained an incomplete ZIP, remove it before retrying.",
                )
            } catch (e: Exception) {
                publishMessage(
                    "capture_export.failed",
                    "Capture export failed: ${e.message}. Review the selected destination before retrying.",
                )
            } finally {
                finishOperation(context)
            }
        }
    }

    /** Compatibility callback; identity must already have been saved before CreateDocument launched. */
    fun export(uri: Uri, extension: String) {
        if (extension !in setOf("json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png")) return
        val requestId = pendingArtifactId(extension) ?: return
        completeArtifactExport(requestId, uri)
    }

    fun completeArtifactExport(requestId: String, uri: Uri) {
        val (request, context) = claimPending(requestId, OperationKind.EXPORT_ARTIFACT) ?: return
        request as BoundExternalRequest.ArtifactExport
        task = viewModelScope.launch(dispatchers.io) {
            try {
                progress.value = SparsePipeline.Progress("Verifying completed result", 0, 1)
                val outcome = exportTransfers.artifact(request, uri, ::exportProgress)
                publishTransferOutcome("export", outcome, exportMessage(request.extension))
            } catch (_: CancellationException) {
                publishMessage(
                    "export.interrupted",
                    "Model export stopped. If the provider retained an incomplete file, remove it before retrying.",
                )
            } catch (e: Exception) {
                publishMessage(
                    "export.failed",
                    "Model export failed: ${e.message}. Review the selected destination before retrying.",
                )
            } finally {
                finishOperation(context)
            }
        }
    }

    private fun exportProgress(update: com.realityforge.scanner.data.safexport.TransferProgress) {
        val percent = if (update.expectedByteCount == 0L) 100 else
            (update.bytesCopied.toDouble() * 100.0 / update.expectedByteCount).toInt().coerceIn(0, 100)
        progress.value = SparsePipeline.Progress("Copying ${update.bytesCopied} of ${update.expectedByteCount} bytes", percent, 100)
    }

    private fun publishTransferOutcome(prefix: String, outcome: com.realityforge.scanner.data.safexport.TransferOutcome,
        description: String) {
        when (outcome) {
            is com.realityforge.scanner.data.safexport.TransferOutcome.VerifiedSuccess ->
                publishMessage("$prefix.verified", "$description Destination size and SHA-256 verified.")
            is com.realityforge.scanner.data.safexport.TransferOutcome.CompletedUnverified ->
                publishMessage("$prefix.unverified", "$description ${outcome.warning} Check the destination before relying on this copy.")
            is com.realityforge.scanner.data.safexport.TransferOutcome.Failure ->
                publishMessage("$prefix.failed", "Export failed: ${outcome.detail} ${exportCleanupMessage(outcome.cleanup)}")
            is com.realityforge.scanner.data.safexport.TransferOutcome.Cancelled ->
                publishMessage("$prefix.cancelled", "Export stopped after ${outcome.bytesCopied} bytes. ${exportCleanupMessage(outcome.cleanup)}")
        }
    }

    private fun exportCleanupMessage(cleanup: com.realityforge.scanner.data.safexport.CleanupResult): String = when (cleanup.disposition) {
        com.realityforge.scanner.data.safexport.CleanupDisposition.OWNED_PARTIAL_DELETED -> "The newly created incomplete document was removed. Choose a destination to retry."
        com.realityforge.scanner.data.safexport.CleanupDisposition.NOT_NEEDED -> "No transfer data was written. Choose a destination to retry."
        else -> "The provider retained the destination. Review it and remove any incomplete copy before retrying."
    }

    private fun exportMessage(extension: String): String = when (extension) {
        "mesh.obj.zip" -> "Exported observed open surface OBJ ZIP. No photographic texture; arbitrary scale."
        "mesh.stl" -> "Exported observed open surface STL at acknowledged arbitrary scale. Not certified printable."
        "json" -> "Exported revision-checked reconstruction diagnostic JSON with scan metadata and point data."
        "textured.glb" -> "Exported photo-textured GLB with embedded PNG and indexed UVs. Open surfaces, arbitrary scale."
        "texture.png" -> "Exported actual rectified photographic atlas PNG."
        "mesh.glb", "mesh.ply" -> "Exported verified open surface mesh ($extension), vertex colors and arbitrary scale."
        else -> "Exported $extension point cloud. Your document provider controls the destination."
    }

    private fun pendingId(kind: OperationKind): String? =
        coordinator.pendingRequests().singleOrNull { it.kind == kind }?.requestId?.value

    private fun pendingArtifactId(extension: String): String? =
        coordinator.pendingRequests().filterIsInstance<BoundExternalRequest.ArtifactExport>()
            .singleOrNull { it.extension == extension }?.requestId?.value

    companion object {
        private const val KEY_ARCHIVE_RESTORE = "pending_archive_restore_v1"
        private const val KEY_SELECTED_PROJECT = "scanner.selected_project"
        private const val KEY_PENDING_REQUESTS = "scanner.pending_requests.v1"
        private const val KEY_SELECTION_GENERATION = "scanner.selection_generation"
        private val SUPPORTED_EXPORT_EXTENSIONS = setOf(
            "json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply",
            "textured.glb", "texture.png",
        )
    }
}
