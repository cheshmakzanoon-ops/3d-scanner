package com.realityforge.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.realityforge.scanner.application.OperationCoordinator
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
import java.util.concurrent.atomic.AtomicLong
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
    private val dispatchers = root.dispatchers
    private val coordinator = OperationCoordinator()

    val selectedId = MutableStateFlow(savedStateHandle.get<String>(KEY_SELECTED_PROJECT))
    val projects = repository.dao.observeProjects().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
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

    private val selectionSlice = combine(projects, selectedId, selectionGenerationState) { all, id, generation ->
        val project = all.firstOrNull { it.id == id }
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
    private val nativeOperation = AtomicLong(0)
    private var selectionGeneration = selectionGenerationState.value

    private val recovery = viewModelScope.async(dispatchers.io) {
        repository.dao.recoverInterrupted()
    }

    init {
        restorePendingRequests()
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
        val project = projects.value.firstOrNull { it.id == id } ?: run {
            publishMessage("project.not_loaded", "The selected project is not available yet.")
            return null
        }
        return ProjectRevision(project.id, project.revision)
    }

    private suspend fun requireCurrent(source: ProjectRevision): ScanProject {
        val project = requireNotNull(repository.dao.project(source.projectId)) {
            "Project no longer exists."
        }
        check(project.revision == source.revision) {
            "Project changed after this request was opened. Repeat the operation for the current revision."
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
        syncPendingRequests()
    }

    private fun syncPendingRequests() {
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
        if (removed != null) persistPendingRequests()
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

    fun beginArtifactExportRequest(extension: String): String? {
        if (extension !in SUPPORTED_EXPORT_EXTENSIONS) return null
        val source = selectedSource() ?: return null
        val project = projects.value.firstOrNull { it.id == source.projectId } ?: return null
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
        if (busy.value) return
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
                        Triple(project, status, repository.currentPreview(id))
                    }
                    ensureActive()
                    // Main-thread publication cannot race another UI operation's reservation.
                    if (selectedId.value == id && selectionGeneration == generation && !busy.value) {
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
                for ((index, uri) in uris.withIndex()) {
                    ensureActive()
                    progress.value = SparsePipeline.Progress("Importing photographs", index, uris.size)
                    try {
                        if (repository.importPhoto(request.project.projectId, uri)) imported++ else duplicates++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        rejected++
                        lastError = e.message.orEmpty()
                    }
                }
                clearVisiblePreview(request.project.projectId)
                publishMessage(
                    "import.completed",
                    "$imported imported; $duplicates duplicates; $rejected rejected." +
                        if (rejected > 0) " $lastError" else "",
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
                val profile = withContext(dispatchers.compute) {
                    LensCalibration.calculate(getApplication(), uris) { done, total ->
                        progress.value = SparsePipeline.Progress("Calibrating checkerboard lens", done, total)
                    }
                }
                ensureActive()
                // Invalidate previous sparse geometry BEFORE switching its optical model.
                withContext(dispatchers.io + NonCancellable) {
                    requireCurrent(request.project)
                    val id = request.project.projectId
                    repository.dao.invalidate(id)
                    repository.writeAtomic(
                        LensCalibration.file(repository, id),
                        LensCalibration.json(profile),
                    )
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
                    repository.dao.invalidate(source.projectId)
                    val file = LensCalibration.file(repository, source.projectId)
                    check(!file.exists() || file.delete()) { "Cannot delete lens calibration" }
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

    fun reconstruct(focal35: Double) {
        val source = selectedSource() ?: return
        val context = reserveOperation(OperationKind.RECONSTRUCT, source) ?: return
        task = viewModelScope.launch {
            var token = 0L
            try {
                recovery.await()
                val project = withContext(dispatchers.io) { requireCurrent(source) }
                clearVisiblePreview(source.projectId)
                repository.dao.state(
                    source.projectId,
                    project.revision,
                    "PROCESSING",
                    "Reconstructing sparse geometry and attempting CPU dense stereo preview.",
                )
                // Ownership is created once for this specific reconstruction.
                // pause() can signal the atomic flag while JNI is still executing.
                token = NativeCore.beginReconstruction()
                check(nativeOperation.compareAndSet(0, token)) { "Another native scan is already active" }
                val output = withContext(dispatchers.compute) {
                    SparsePipeline(repository).run(source.projectId, focal35, token) {
                        progress.value = it
                    }
                }
                ensureActive()
                check(
                    repository.dao.complete(
                        source.projectId,
                        project.revision,
                        output.getString("artifactKey"),
                        "${output.getInt("pointCount")} sparse points; " +
                            "${output.optJSONObject("densePreview")?.optInt("pointCount") ?: 0} dense points; " +
                            "${output.optJSONObject("meshPreview")?.optInt("triangleCount") ?: 0} observed triangles.",
                    ) == 1,
                ) { "Result became stale." }
                if (selectedId.value == source.projectId) {
                    preview.value = output
                    typedPreview.value = PreviewState.Ready(
                        source,
                        output.getString("artifactKey"),
                        representationOf(output),
                    )
                }
                publishMessage(
                    "reconstruction.saved",
                    "Preview saved. M09 exports real photo-based UV textures when visibility is sufficient; geometry remains open and unscaled.",
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    repository.dao.state(
                        source.projectId,
                        source.revision,
                        "INTERRUPTED",
                        "Paused. Originals and feature caches are safe; rerun to continue from cached features.",
                    )
                }
                publishMessage("reconstruction.interrupted", "Processing stopped at a safe boundary.")
            } catch (e: Exception) {
                repository.dao.state(
                    source.projectId,
                    source.revision,
                    "FAILED",
                    e.message.orEmpty().take(600),
                )
                publishMessage("reconstruction.failed", e.message ?: "Reconstruction failed.")
            } catch (e: LinkageError) {
                repository.dao.state(
                    source.projectId,
                    source.revision,
                    "FAILED",
                    "Native library load failed. This device/build needs verification.",
                )
                publishMessage("native.load_failed", "Native library load failed: ${e.message}")
            } finally {
                if (token > 0) {
                    nativeOperation.compareAndSet(token, 0L)
                    NativeCore.finishReconstruction(token)
                }
                progress.value = null
                finishOperation(context)
            }
        }
    }

    fun pause() {
        val token = nativeOperation.get()
        if (token > 0) NativeCore.cancelReconstruction(token)
        task?.cancel()
    }

    fun onHostStopped() {
        // Remains intentionally conservative until Agent 008's durable runner passes lifecycle gates.
        pause()
    }

    override fun onCleared() {
        // Cancels the native worker even if Activity recreation closes the owner.
        val token = nativeOperation.get()
        if (token > 0) NativeCore.cancelReconstruction(token)
        super.onCleared()
    }

    fun deleteSelected() {
        val source = selectedSource() ?: return
        val context = reserveOperation(OperationKind.DELETE_PROJECT, source) ?: return
        task = viewModelScope.launch {
            var deleted = false
            try {
                withContext(dispatchers.io) {
                    requireCurrent(source)
                    repository.delete(source.projectId)
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

    /** Compatibility entry point. Agent 071 must bind before launching CreateDocument. */
    fun exportCaptureBundle(uri: Uri) {
        val requestId = pendingId(OperationKind.EXPORT_CAPTURE_BUNDLE) ?: beginCaptureBundleExportRequest() ?: return
        completeCaptureBundleExport(requestId, uri)
    }

    fun completeCaptureBundleExport(requestId: String, uri: Uri) {
        val (request, context) = claimPending(requestId, OperationKind.EXPORT_CAPTURE_BUNDLE) ?: return
        request as BoundExternalRequest.CaptureBundleExport
        task = viewModelScope.launch(dispatchers.io) {
            try {
                requireCurrent(request.project)
                val stream = requireNotNull(
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt"),
                ) { "Cannot open destination." }
                val count = stream.use { repository.exportCaptureBundle(request.project.projectId, it) }
                publishMessage(
                    "capture_export.completed",
                    "Exported $count original photos and a local benchmark manifest. Keep the ZIP private.",
                )
            } catch (_: CancellationException) {
                publishMessage(
                    "capture_export.interrupted",
                    "Capture export interrupted. Delete any incomplete destination ZIP.",
                )
            } catch (e: Exception) {
                publishMessage(
                    "capture_export.failed",
                    "Capture export failed: ${e.message}. Delete any incomplete destination ZIP.",
                )
            } finally {
                finishOperation(context)
            }
        }
    }

    /** Compatibility entry point. Agent 071 must bind before launching CreateDocument. */
    fun export(uri: Uri, extension: String) {
        if (extension !in setOf("json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png")) return
        val requestId = pendingArtifactId(extension) ?: beginArtifactExportRequest(extension) ?: return
        completeArtifactExport(requestId, uri)
    }

    fun completeArtifactExport(requestId: String, uri: Uri) {
        val (request, context) = claimPending(requestId, OperationKind.EXPORT_ARTIFACT) ?: return
        request as BoundExternalRequest.ArtifactExport
        task = viewModelScope.launch(dispatchers.io) {
            try {
                val project = requireCurrent(request.project)
                check(
                    project.resultRevision == request.resultRevision &&
                        project.resultKey == request.artifactKey,
                ) { "The completed result changed after this export was opened." }
                val file = repository.artifact(
                    request.project.projectId,
                    request.artifactKey,
                    request.extension,
                )
                file.inputStream().use { input ->
                    requireNotNull(
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt"),
                    ).use { destination ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                }
                val latest = repository.dao.project(request.project.projectId)
                check(
                    latest != null &&
                        latest.revision == request.project.revision &&
                        latest.resultRevision == request.resultRevision &&
                        latest.resultKey == request.artifactKey,
                ) { "Project changed during export; discard the destination file." }
                publishMessage("export.completed", exportMessage(request.extension))
            } catch (_: CancellationException) {
                publishMessage(
                    "export.interrupted",
                    "Model export interrupted. Delete any incomplete destination file.",
                )
            } catch (e: Exception) {
                publishMessage(
                    "export.failed",
                    "Model export failed: ${e.message}. Delete any incomplete destination file.",
                )
            } finally {
                finishOperation(context)
            }
        }
    }

    private fun exportMessage(extension: String): String = when (extension) {
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
        private const val KEY_SELECTED_PROJECT = "scanner.selected_project"
        private const val KEY_PENDING_REQUESTS = "scanner.pending_requests.v1"
        private const val KEY_SELECTION_GENERATION = "scanner.selection_generation"
        private val SUPPORTED_EXPORT_EXTENSIONS = setOf(
            "json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply",
            "textured.glb", "texture.png",
        )
    }
}
