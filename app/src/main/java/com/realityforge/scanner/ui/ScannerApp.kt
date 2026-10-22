package com.realityforge.scanner.ui

import android.graphics.Bitmap
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.BuildConfig
import com.realityforge.scanner.capture.session.CaptureSessionIdentity
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.application.ProjectCommandAdapter
import com.realityforge.scanner.data.curation.*
import com.realityforge.scanner.data.library.*
import com.realityforge.scanner.data.reopening.PreparedPreviewGeometry
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.data.reopening.ScanRepresentation
import com.realityforge.scanner.data.reopening.preparePreviewGeometry
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.processing.resources.ResourceAwareCaptureBudgetProvider
import com.realityforge.scanner.ui.storage.StorageRoute
import com.realityforge.scanner.ui.storage.GlobalStorageScreen
import com.realityforge.scanner.data.cachegc.GlobalCacheInventoryService
import com.realityforge.scanner.imaging.DecodeRequest
import kotlinx.coroutines.CancellationException
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.diagnostics.ScanStage
import com.realityforge.scanner.engine.diagnostics.ProgressUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.UUID

private data class BoundPreparedPreview(
    val projectId: String,
    val inputRevision: Long,
    val observedResultKey: String?,
    val observedResultRevision: Long?,
    val geometry: PreparedPreviewGeometry,
    val photographicModel: File?,
)

private data class BoundLibraryCommand(val kind: String, val source: ProjectRevision)

private val palette = darkColorScheme(
    primary = Color(0xFF73D8C7), onPrimary = Color(0xFF002F29),
    secondary = Color(0xFFC6BEA6), background = Color(0xFF101A23),
    surface = Color(0xFF17252F), surfaceVariant = Color(0xFF233641),
    onSurface = Color(0xFFE6ECEF), onBackground = Color(0xFFE6ECEF),
)

private fun ScanStage.displayLabel(): String = when (this) {
    ScanStage.IMPORT -> "Importing photographs"
    ScanStage.CALIBRATION -> "Checking lens calibration"
    ScanStage.FEATURE_EXTRACTION -> "Finding image features"
    ScanStage.MATCHING -> "Matching photographs"
    ScanStage.CAMERA_REGISTRATION -> "Registering cameras"
    ScanStage.SPARSE_GEOMETRY -> "Reconstructing sparse points"
    ScanStage.BUNDLE_ADJUSTMENT -> "Refining cameras and points"
    ScanStage.DENSE_DEPTH -> "Reconstructing depth"
    ScanStage.SURFACE -> "Preparing observed surfaces"
    ScanStage.TEXTURE -> "Preparing photographic texture"
    ScanStage.EXPORT -> "Writing result files"
    ScanStage.STORAGE -> "Checking storage"
    ScanStage.REPORT -> "Preparing scan diagnostics"
    ScanStage.UNKNOWN -> "Processing"
}

private fun ProgressUnit.displayLabel(): String = when (this) {
    ProgressUnit.ITEMS -> "items"
    ProgressUnit.PHOTOS -> "photos"
    ProgressUnit.PAIRS -> "pairs"
    ProgressUnit.CAMERAS -> "cameras"
    ProgressUnit.TRACKS -> "tracks"
    ProgressUnit.PIXELS -> "pixels"
    ProgressUnit.TRIANGLES -> "triangles"
    ProgressUnit.BYTES -> "bytes"
    ProgressUnit.STEPS -> "steps"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditedScannerWorkspace(model: ScannerViewModel, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val commands = remember(model, context) { ProjectCommandAdapter(model.compositionRoot, context) }
    val analysisBudget = remember(context) { ResourceAwareCaptureBudgetProvider(context) }
    val projects by model.projects.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val focusedProject by model.selectedProject.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val calibrationStatus by model.calibrationStatus.collectAsStateWithLifecycle()
    val appState by model.scannerState.collectAsStateWithLifecycle()
    val pendingArchiveRestoreId by model.pendingArchiveRestoreId.collectAsStateWithLifecycle()
    val captureRequest = appState.pendingRequests.singleOrNull { it.kind == OperationKind.CAPTURE_PHOTO }
    val captureSession = captureRequest?.let { request ->
        remember(request.requestId, request.sessionId) {
            CaptureSessionIdentity(request.project.projectId, request.project.revision,
                requireNotNull(request.sessionId).value)
        }
    }
    val canMutate = !busy && appState.pendingRequests.isEmpty() && pendingArchiveRestoreId == null
    val canModifyInputs = canMutate && focusedProject?.state !in setOf("RECOVERY_REQUIRED", "DELETING", "DELETING_PHOTO")
    var viewPrevious by rememberSaveable(selected) { mutableStateOf(false) }
    var preparedPreview by remember { mutableStateOf<BoundPreparedPreview?>(null) }
    var previewReadMessage by remember(selected, viewPrevious) { mutableStateOf<String?>(null) }
    val displayedPreview = preparedPreview?.takeIf {
        it.projectId == selected && it.inputRevision == focusedProject?.revision &&
            focusedProject?.state !in setOf("DELETING", "DELETING_PHOTO") &&
            it.observedResultKey == focusedProject?.resultKey &&
            it.observedResultRevision == focusedProject?.resultRevision &&
            it.geometry.previousResult == viewPrevious &&
            (viewPrevious || (it.geometry.revision == focusedProject?.revision &&
                it.geometry.artifactKey == focusedProject?.resultKey))
    }
    LaunchedEffect(selected, focusedProject?.revision, focusedProject?.resultRevision,
        focusedProject?.resultKey, focusedProject?.state, viewPrevious, preview?.optString("artifactKey")) {
        preparedPreview = null
        previewReadMessage = null
        val source = focusedProject?.takeIf { it.id == selected } ?: return@LaunchedEffect
        if (source.state in setOf("DELETING", "DELETING_PHOTO")) {
            previewReadMessage = "Finish the pending deletion before opening a saved result."
            return@LaunchedEffect
        }
        val previous = viewPrevious
        try {
            model.compositionRoot.execution.awaitRecovery()
            val loaded = withContext(Dispatchers.IO) {
                when (val reopened = model.repository.reopen(source.id, previous)) {
                    is ReopenResult.Ready -> {
                        val geometry = preparePreviewGeometry(reopened.model)
                        val setId = reopened.publicationSetId
                        val photographic = if (ScanRepresentation.PHOTOGRAPHIC_MESH !in geometry.capabilities) null
                        else if (setId == null) model.repository.artifact(source.id, geometry.artifactKey, "textured.glb")
                        else {
                            when (val published = model.compositionRoot.publications.committed(source.id, setId)) {
                                is PublicationReadResult.Verified -> {
                                    check(published.value.manifest.setId == setId &&
                                        published.value.manifest.identity.projectRevision == geometry.revision) {
                                        "The completed result does not match the reopened geometry."
                                    }
                                    published.value.artifact("textured-glb")
                                }
                                is PublicationReadResult.Invalid -> error(published.reason)
                                PublicationReadResult.None -> error("The exact completed result is no longer available.")
                            }
                        }
                        BoundPreparedPreview(source.id, source.revision, source.resultKey, source.resultRevision,
                            geometry, photographic) to null
                    }
                    is ReopenResult.Invalid -> null to "Saved result cannot be opened: ${reopened.detail}"
                    ReopenResult.Changed -> null to "The project changed while opening this result. Select the current result again."
                    ReopenResult.Missing -> null to if (previous) "No previous completed result is available."
                        else "No completed result is available for the current input revision."
                }
            }
            val current = model.selectedProject.value
            if (model.selectedId.value == source.id && current != null && current.revision == source.revision &&
                current.resultKey == source.resultKey && current.resultRevision == source.resultRevision &&
                current.state == source.state) {
                preparedPreview = loaded.first
                previewReadMessage = loaded.second
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { previewReadMessage = failure.message ?: "Cannot open saved result." }
    }
    val durableJob by model.compositionRoot.execution.current.collectAsStateWithLifecycle()
    val diagnosticProgress by model.compositionRoot.execution.diagnosticProgress.collectAsStateWithLifecycle()
    val storageFailure by model.compositionRoot.storageBudget.failure.collectAsStateWithLifecycle()
    var storageProject by rememberSaveable { mutableStateOf<String?>(null) }
    var globalStorage by rememberSaveable { mutableStateOf(false) }
    val globalInventory = remember(commands, model) {
        GlobalCacheInventoryService(commands.library::query, model.compositionRoot.cacheService::projectInventory)
    }
    var librarySearch by rememberSaveable { mutableStateOf("") }
    var libraryState by rememberSaveable { mutableStateOf("") }
    var libraryOrder by rememberSaveable { mutableStateOf(LibraryOrder.NEWEST.name) }
    var libraryOffset by rememberSaveable { mutableIntStateOf(0) }
    var libraryPage by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var curationState by remember(selected) { mutableStateOf<PhotoCurationState?>(null) }
    var curationUndo by remember(selected) { mutableStateOf<CurationUndoToken?>(null) }
    var libraryCommand by remember { mutableStateOf<BoundLibraryCommand?>(null) }
    var libraryName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(projects, selected, durableJob, librarySearch, libraryState, libraryOrder, libraryOffset) {
        try {
            libraryPage = commands.library.query(LibraryQuery(librarySearch,
                if (libraryState.isEmpty()) emptySet() else setOf(libraryState),
                LibraryOrder.valueOf(libraryOrder), libraryOffset, 25))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { model.notice.value = failure.message ?: "Cannot read project library." }
    }
    LaunchedEffect(selected, focusedProject, photos) {
        val id = selected
        curationState = if (id == null) null else try { commands.selection(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { model.notice.value = failure.message ?: "Cannot read photo selection."; null }
    }
    fun curate(command: (PhotoCurationState) -> PhotoCurationCommand) {
        model.projectCommand { source ->
            val outcome = commands.curate(source, command)
            withContext(Dispatchers.Main) { curationUndo = (outcome as? PhotoCurationOutcome.Applied)?.undoToken }
            "${outcome.selectedPhotoSet?.photos?.size ?: 0} photos selected; originals preserved."
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.notice.value = "Processing continues. Pause and resume are available in this app."
    }
    val restoreArchive = rememberCaptureArchivePicker(model)
    var photographicFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(selected, busy, focusedProject?.revision, focusedProject?.resultKey,
        focusedProject?.resultRevision, focusedProject?.state, viewPrevious) { photographicFile = null }
    var newProject by rememberSaveable { mutableStateOf(false) }
    var deleteSource by remember { mutableStateOf<ProjectRevision?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var focal by rememberSaveable { mutableStateOf("26") }
    val snackbars = remember { SnackbarHostState() }
    val picker = rememberBoundPhotoPicker(model, calibration = false)
    val checkerboardPicker = rememberBoundPhotoPicker(model, calibration = true)
    val exportGlb = rememberBoundExporter(model, "glb", "model/gltf-binary")
    val exportPly = rememberBoundExporter(model, "ply", "application/octet-stream")
    val exportMeshGlb = rememberBoundExporter(model, "mesh.glb", "model/gltf-binary")
    val exportTexturedGlb = rememberBoundExporter(model, "textured.glb", "model/gltf-binary")
    val exportTexturePng = rememberBoundExporter(model, "texture.png", "image/png")
    val exportMeshPly = rememberBoundExporter(model, "mesh.ply", "application/octet-stream")
    val exportDenseGlb = rememberBoundExporter(model, "dense.glb", "model/gltf-binary")
    val exportDensePly = rememberBoundExporter(model, "dense.ply", "application/octet-stream")
    val exportCapture = rememberBoundExporter(model, "capture.zip", "application/zip")
    val exportDiagnostics = rememberBoundExporter(model, "json", "application/json")
    LaunchedEffect(notice) { notice?.let { snackbars.showSnackbar(it); model.notice.value = null } }
    // CameraScreen owns Back while a capture/commit is in flight.
    BackHandler(enabled = selected != null && captureSession == null && storageProject == null && !globalStorage) {
        if (canMutate) { if (onBack != null) onBack() else model.select(null) }
    }
    BackHandler(enabled = storageProject != null) { storageProject = null }
    BackHandler(enabled = globalStorage && storageProject == null) { globalStorage = false }
    MaterialTheme {
        if (captureSession != null && captureRequest != null) {
            CameraScreen(
                session = captureSession,
                onClose = { model.cancelPendingRequest(captureRequest.requestId.value) },
                onPhoto = { model.publishCapturedPhoto(it) },
                analysisBudgetProvider = analysisBudget,
            )
        } else if (storageProject != null) {
            val cache = model.compositionRoot.cacheService
            val routeProjectId = requireNotNull(storageProject)
            val storageRecord by remember(routeProjectId) { model.repository.dao.observeProject(routeProjectId) }
                .collectAsStateWithLifecycle(initialValue = null)
            StorageRoute(projectId = routeProjectId,
                projectInventory = cache::projectInventory, cleanup = cache::cleanup,
                availableBytes = { runCatching { context.filesDir.usableSpace.takeIf { it > 0 } }.getOrNull() },
                onBack = { storageProject = null }, projectBusy = busy,
                budgetFailure = storageFailure?.takeIf { it.token.projectId == routeProjectId &&
                    it.token.revision == storageRecord?.revision }?.decision)
        } else if (globalStorage) {
            GlobalStorageScreen(service = globalInventory,
                onProject = { storageProject = it }, onBack = { globalStorage = false })
        } else Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("RealityForge", fontWeight = FontWeight.Bold); Text("LOCAL PHOTOGRAMMETRY / ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall) } },
                    navigationIcon = { if (selected != null) TextButton(enabled = canMutate, onClick = { if (onBack != null) onBack() else model.select(null) }) { Text(if (onBack != null) "Back" else "Library") } },
                    actions = { TextButton(enabled = canMutate, onClick = { globalStorage = true }) { Text(if (selected == null) "Storage" else "All storage") } },
                )
            },
            snackbarHost = { SnackbarHost(snackbars) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                storageFailure?.takeIf { selected == null || it.token.projectId == selected }?.let { failure -> item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Storage admission paused this operation", style = MaterialTheme.typography.titleMedium)
                        Text("Requested ${failure.decision.requiredBytes} additional bytes; measured available " +
                            (failure.decision.measuredAvailableBytes?.let { "$it bytes" } ?: "unknown") + ".")
                        Text("Other reservations and the safety reserve are kept. Review rebuildable caches, then retry from the project.")
                        TextButton(enabled = canMutate, onClick = {
                            if (selected == failure.token.projectId) storageProject = selected else globalStorage = true
                        }) { Text("Review storage") }
                    } }
                } }
                if (busy && appState.activeOperation != null && appState.activeOperation?.kind != OperationKind.RECONSTRUCT) item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(progress?.let { "${it.stage}: ${it.completed}/${it.total}" } ?: "Saving project changes…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { model.pause() }) { Text("Cancel operation") }
                    } }
                }
                if (appState.pendingRequests.isNotEmpty() || pendingArchiveRestoreId != null) item {
                    Card { Column(Modifier.padding(16.dp)) {
                        Text("Waiting for the selected document provider.")
                        TextButton(onClick = {
                            appState.pendingRequests.forEach { model.cancelPendingRequest(it.requestId.value) }
                            pendingArchiveRestoreId?.let(model::cancelPendingRequest)
                        }) {
                            Text("Cancel pending request")
                        }
                    } }
                }
                if (selected == null) {
                    item {
                        Text("Keep the world.\nKeep it on your phone.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text("Photos → calibrated cameras → observed open triangle meshes.", color = MaterialTheme.colorScheme.secondary)
                    }
                    item {
                        InfoCard("Development build", "Inspect exported photographic GLB textures directly on your phone. Missing surfaces stay open; texture seams and full-object completeness still need real-device validation.")
                    }
                    item { Button(enabled = canMutate, onClick = { newProject = true }, modifier = Modifier.fillMaxWidth()) { Text("Create local project") } }
                    item { OutlinedButton(enabled = canMutate, onClick = restoreArchive, modifier = Modifier.fillMaxWidth()) { Text("Restore capture archive") } }
                    item {
                        LibraryQueryControls(librarySearch, libraryState, libraryOrder,
                            onSearch = { librarySearch = it; libraryOffset = 0 },
                            onState = { libraryState = it; libraryOffset = 0 },
                            onOrder = { libraryOrder = it; libraryOffset = 0 })
                    }
                    if (projects.isEmpty()) item { Text("No scans yet. Start with a matte, textured, rigid object and two overlapping views.") }
                    if (projects.isNotEmpty() && libraryPage.isEmpty()) item { Text("No projects match this page. Change the filters or return to the previous page.") }
                    items(libraryPage, key = { it.id }) { project ->
                        Card(onClick = { if (canMutate) model.select(project.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleLarge)
                                Text("${project.state.replace('_', ' ')} · revision ${project.revision}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text("${project.photoCount} photos · ${if (project.hasCurrentResult) "current result available" else "no current result"}", style = MaterialTheme.typography.bodyMedium)
                                Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(project.createdAt)), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(enabled = libraryOffset > 0, onClick = { libraryOffset = (libraryOffset - 25).coerceAtLeast(0) }) { Text("Previous") }
                        OutlinedButton(enabled = libraryPage.size == 25, onClick = { libraryOffset += 25 }) { Text("Next page") }
                    } }
                    item { InfoCard("No account. No app networking.", "Original photographs, feature caches and results are saved in app-private storage. Uninstalling removes them; export important files first.") }
                } else {
                    val project = focusedProject?.takeIf { it.id == selected }
                    item {
                        Text(project?.name ?: "Scan", style = MaterialTheme.typography.headlineMedium)
                        Text("${curationState?.selectedPhotoSet?.photos?.size ?: photos.count { it.included }} selected / ${photos.size} photographs · originals preserved", color = MaterialTheme.colorScheme.secondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = canModifyInputs, onClick = {
                                project?.takeIf { it.id == selected }?.let {
                                    libraryName = it.name
                                    libraryCommand = BoundLibraryCommand("rename", ProjectRevision(it.id, it.revision))
                                }
                            }) { Text("Rename") }
                            TextButton(enabled = canModifyInputs, onClick = {
                                project?.takeIf { it.id == selected }?.let {
                                    libraryName = "${it.name} copy"
                                    libraryCommand = BoundLibraryCommand("duplicate", ProjectRevision(it.id, it.revision))
                                }
                            }) { Text("Duplicate") }
                            TextButton(enabled = canMutate, onClick = { storageProject = selected }) { Text("Storage") }
                        }
                    }
                    if (project?.state == "RECOVERY_REQUIRED") item {
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Project recovery is required", style = MaterialTheme.typography.titleMedium)
                            Text("An interrupted local change needs recovery. Verified completed results can still be viewed; input changes and processing are paused for this project.")
                            OutlinedButton(enabled = canMutate, onClick = {
                                model.projectCommand { source ->
                                    if (model.repository.recoverProjectStorage(source.projectId)) "Project recovery completed."
                                    else "Recovery could not finish. The saved originals and completed results have been preserved."
                                }
                            }) { Text("Retry project recovery") }
                        } }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(enabled = canModifyInputs, onClick = { picker() }, modifier = Modifier.weight(1f)) { Text("Import photos") }
                            OutlinedButton(enabled = canModifyInputs, onClick = { model.beginCaptureRequest() }, modifier = Modifier.weight(1f)) { Text("Use camera") }
                        }
                    }
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Offline lens calibration", style = MaterialTheme.typography.titleMedium)
                                Text(calibrationStatus, style = MaterialTheme.typography.bodyMedium)
                                Text("Print the 10×7-square board (9×6 INNER corners). Photograph it 10–40 times using the SAME camera, zoom, aspect and orientation as your object photos; vary angle, distance and board position.")
                                Button(enabled = canModifyInputs, onClick = {
                                    checkerboardPicker()
                                }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate this lens") }
                                if (calibrationStatus.startsWith("Calibrated:")) {
                                    OutlinedButton(enabled = canModifyInputs, onClick = { model.clearLensCalibration() }) { Text("Remove calibration") }
                                }
                                Text("Calibration improves geometry but does not provide absolute physical scale. Mismatched crops/lenses are rejected.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        InfoCard("Capture for geometry", "Keep the object still. Move sideways between overlapping views; do not just rotate the phone. Keep the same lens and avoid glossy, transparent or flat subjects. Background points can appear in the reconstruction.")
                    }
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Photographic reconstruction", style = MaterialTheme.typography.titleMedium)
                                Text("Examines up to 12 photographs. Up to eight registered views contribute to adaptive depth and optional photographic mesh output—not a finished mesh guarantee.")
                                OutlinedTextField(value = focal, onValueChange = { focal = it.take(6) }, enabled = canModifyInputs,
                                    label = { Text("Fallback 35 mm-equivalent focal length") }, suffix = { Text("mm") },
                                    supportingText = { Text("Used only when EXIF lacks a value. 26 mm is an explicit assumption, not measured calibration.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(enabled = canModifyInputs && (curationState?.selectedPhotoSet?.photos?.size ?: 0) >= 2 && (focal.toDoubleOrNull()?.let { it in 10.0..200.0 } == true),
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        model.reconstruct(focal.toDouble())
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Reconstruct points + surface") }
                                if (busy && durableJob?.input?.projectId == selected && durableJob?.state in setOf(
                                        com.realityforge.scanner.data.jobs.ReconstructionJobState.RUNNING,
                                        com.realityforge.scanner.data.jobs.ReconstructionJobState.QUEUED)) {
                                    val job = durableJob
                                    val p = diagnosticProgress?.takeIf { measured ->
                                        job != null && measured.scope.jobId == job.jobId.value &&
                                            measured.scope.attempt.toLong() == job.attempt &&
                                            measured.scope.sourceRevision == job.input.projectRevision &&
                                            job.input.projectRevision == project?.revision
                                    }
                                    Text(p?.let {
                                        val count = it.total?.let { total -> "${it.completed}/$total" } ?: "${it.completed}"
                                        "${it.stage.displayLabel()}: $count ${it.unit.displayLabel()}"
                                    } ?: "Preparing reconstruction…")
                                    val fraction = p?.stageFractionOrNull()?.toFloat()
                                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    if (p?.reusedCheckpoint == true) Text("Using a verified saved result for this stage.",
                                        style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { model.pause() }) { Text("Stop at safe boundary") }
                                    Text("Stopping preserves completed per-camera depth checkpoints. Relaunching a scan with unchanged inputs can reuse them; unfinished exports are discarded.", style = MaterialTheme.typography.bodySmall)
                                }
                                if (durableJob?.input?.projectId == selected && durableJob?.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.PAUSED) {
                                    Text("Processing is paused. Completed checkpoints are saved.")
                                    OutlinedButton(enabled = canModifyInputs, onClick = { model.resumeProcessing() }) { Text("Resume saved job") }
                                }
                            }
                        }
                    }
                    if (project?.resultRevision != null) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(enabled = canMutate && viewPrevious, onClick = { viewPrevious = false }) { Text("Current result") }
                            OutlinedButton(enabled = canMutate && !viewPrevious, onClick = { viewPrevious = true }) { Text("Previous completed result") }
                        }
                    }
                    previewReadMessage?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
                    if (project?.resultRevision != null && displayedPreview == null && previewReadMessage == null) item {
                        Text("Validating saved result and preparing the viewer…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val preparedGeometry = displayedPreview?.geometry
                    if (viewPrevious && preparedGeometry != null) item {
                        PreviousResultCard(preparedGeometry, project?.revision ?: preparedGeometry.revision,
                            photographicAvailable = displayedPreview?.photographicModel != null,
                            onPhotographic = { photographicFile = displayedPreview?.photographicModel })
                    }
                    val result = preview?.takeIf { saved -> !viewPrevious && preparedGeometry != null &&
                        saved.optString("artifactKey") == preparedGeometry.artifactKey &&
                        saved.optLong("revision", -1) == preparedGeometry.revision }
                    if (result != null) {
                        val diagnostics=result.optJSONObject("scanDiagnostics")
                        if (diagnostics != null) {
                            item {
                                Card {
                                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text("Scan evidence and next actions",style=MaterialTheme.typography.titleMedium)
                                        val title=when(diagnostics.optString("status")) {
                                            "PHOTO_TEXTURED_OPEN_SURFACE" -> "Photographic texture on observed open surfaces"
                                            "PARTIAL_OPEN_MESH" -> "Partial open mesh, limited photographic coverage"
                                            else -> "Sparse or depth-only reconstruction"
                                        }
                                        Text(title,style=MaterialTheme.typography.bodyMedium)
                                        Text("${diagnostics.optInt("registeredCameraCount")}/${diagnostics.optInt("importedPhotoCount")} cameras registered · ${diagnostics.optInt("observedMeshTriangles")} observed triangles")
                                        if(!diagnostics.isNull("reciprocalDepthFraction"))
                                            Text("Reciprocal depth support: ${"%.1f".format(diagnostics.optDouble("reciprocalDepthFraction")*100)}% of sampled depth pixels")
                                        if(!diagnostics.isNull("photographicallyCoveredFaceFraction"))
                                            Text("Photographic texture: ${"%.1f".format(diagnostics.optDouble("photographicallyCoveredFaceFraction")*100)}% of observed triangles")
                                        val actions=diagnostics.optJSONArray("recommendations")
                                        if (actions != null) {
                                            for (index in 0 until minOf(actions.length(),4)) {
                                                Text("• ${actions.getJSONObject(index).optString("action")}",style=MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        Text("These measurements do not establish full-object completeness, millimeter accuracy, or print-ready topology.",style=MaterialTheme.typography.bodySmall)
                                        OutlinedButton(enabled=canMutate,onClick={exportDiagnostics("realityforge-reconstruction-report.json")}) {
                                            Text("Export scan diagnostics (JSON)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (result != null) {
                        item {
                            Card {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("${result.optInt("pointCount")} reconstructed points", style = MaterialTheme.typography.titleLarge)
                                    Text("${result.optInt("registeredCameras", 2)} registered cameras · arbitrary scale · drag to orbit, pinch to zoom", style = MaterialTheme.typography.labelMedium)
                                    preparedGeometry?.sparse?.let { PointCloudViewer(it, Modifier.fillMaxWidth().height(310.dp)) }
                                    val parallax = result.optDouble("medianParallaxDegrees").takeIf { it.isFinite() }
                                    Text(parallax?.let { "Median parallax: ${"%.2f".format(it)}°" }
                                        ?: "Median parallax measurement unavailable", color = MaterialTheme.colorScheme.primary)
                                    Text("Camera and point positions are jointly refined. Dense depth can produce an open mesh from verified neighboring samples when coverage permits.", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(enabled = canMutate, onClick = { exportGlb("realityforge-sparse.glb") }) { Text("Export GLB") }
                                        OutlinedButton(enabled = canMutate, onClick = { exportPly("realityforge-sparse.ply") }) { Text("Export PLY") }
                                    }
                                    Text("GLB contains POINTS, not triangles. PLY offers broad point-cloud inspection support.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (result != null) {
                        val dense = result.optJSONObject("densePreview")
                        val denseGeometry = preparedGeometry?.dense
                        if (dense != null && denseGeometry != null) {
                            item {
                                Card {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("${dense.optInt("pointCount")} dense stereo points",style=MaterialTheme.typography.titleLarge)
                                        Text("Rectified ${dense.optInt("sourcePhotos")} camera views · inverse-depth plane sweep · reciprocal camera-z consistency",style=MaterialTheme.typography.labelMedium)
                                        PointCloudViewer(denseGeometry,Modifier.fillMaxWidth().height(310.dp))
                                        Text("Depth grid: ${dense.optInt("rectifiedWidth")} × ${dense.optInt("rectifiedHeight")} · stride ${dense.optInt("pixelStride")} · ${dense.optInt("excludedRegisteredCameraCount")} registered views not selected",style=MaterialTheme.typography.bodySmall)
                                        Text("Points and open meshes remain separate. Texture exports are available only when photo visibility checks pass; scale is arbitrary.",style=MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(enabled = canMutate, onClick = { exportDenseGlb("realityforge-dense-points.glb") }) { Text("Dense GLB") }
                                            OutlinedButton(enabled = canMutate, onClick = { exportDensePly("realityforge-dense-points.ply") }) { Text("Dense PLY") }
                                        }
                                    }
                                }
                            }
                        } else {
                            item { Text("Dense stereo unavailable: ${result.optString("denseFailure", "insufficient calibrated coverage")}",style=MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (result != null) {
                        val mesh = result.optJSONObject("meshPreview")
                        val meshGeometry = preparedGeometry?.mesh
                        if(mesh != null && meshGeometry != null) {
                            item {
                                var wireframe by rememberSaveable { mutableStateOf(false) }
                                Card {
                                    Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${mesh.optInt("triangleCount")} verified triangles · ${mesh.optInt("vertexCount")} fused vertices",
                                            style=MaterialTheme.typography.titleLarge)
                                        Text("Real indexed mesh, vertex colors, open surfaces and arbitrary scale. Missing areas stay open.",
                                            style=MaterialTheme.typography.bodySmall)
                                        SurfaceMeshViewer(meshGeometry,Modifier.fillMaxWidth().height(320.dp),wireframe)
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            Switch(checked=wireframe,onCheckedChange={wireframe=it})
                                            Spacer(Modifier.width(12.dp))
                                            Text("Wireframe")
                                        }
                                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            Button(enabled = canMutate,onClick = { exportMeshGlb("realityforge-open-mesh.glb") },modifier=Modifier.weight(1f)) { Text("Mesh GLB") }
                                            OutlinedButton(enabled = canMutate,onClick = { exportMeshPly("realityforge-open-mesh.ply") },modifier=Modifier.weight(1f)) { Text("Mesh PLY") }
                                        }
                                        Text("These original mesh exports contain triangle normals and vertex colors. Photo-textured output is separate.",
                                            style=MaterialTheme.typography.bodySmall)
                                        val texture=result.optJSONObject("texturePreview")
                                        if(texture!=null) {
                                            Text("Photo UV atlas: ${texture.optInt("atlasWidth")} × ${texture.optInt("atlasHeight")} pixels · ${texture.optInt("triangleCount")} textured triangles",
                                                style=MaterialTheme.typography.titleMedium)
                                            Text("Photographic coverage: ${"%.1f".format(texture.optDouble("faceCoverage")*100)}% of reconstructed triangles. This is not full-object completeness.",
                                                style=MaterialTheme.typography.bodySmall)
                                            Text("${textureCameraSwitchSummary(texture)} Exposure observations: ${texture.optInt("exposureSamples")}.",
                                                style=MaterialTheme.typography.bodySmall)
                                            Text("Exposure-balanced camera UV charts. Inspect the actual embedded photographic atlas separately from the vertex-color diagnostic above.",
                                                style=MaterialTheme.typography.bodySmall)
                                            Button(enabled = canMutate && displayedPreview?.photographicModel != null, onClick = {
                                                photographicFile = displayedPreview?.photographicModel
                                            }, modifier = Modifier.fillMaxWidth()) { Text("Inspect photo-textured model") }
                                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                                Button(enabled = canMutate,onClick = { exportTexturedGlb("realityforge-photo-textured.glb") },modifier=Modifier.weight(1f)) { Text("Textured GLB") }
                                                OutlinedButton(enabled = canMutate,onClick = { exportTexturePng("realityforge-photo-atlas.png") },modifier=Modifier.weight(1f)) { Text("Atlas PNG") }
                                            }
                                            Text("Open mesh, arbitrary scale. Boundaries can retain visible seams or overlap; no watertight or real-scale guarantee.",style=MaterialTheme.typography.bodySmall)
                                        } else {
                                            Text("Photo atlas unavailable: ${result.optString("textureFailure","insufficient registered photo overlap")}",
                                                style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        } else item { Text("Surface mesh unavailable: ${result.optString("meshFailure", "insufficient consistent depth")}",
                            style=MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        OutlinedButton(enabled = canModifyInputs && photos.size >= 3,
                            onClick = { exportCapture("realityforge-capture.zip") },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Export original photos + calibration for benchmark")
                        }
                        Text("Creates a local ZIP at your chosen destination containing your original photos; treat it as private. The export does not upload anything from RealityForge.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    item { Text("Photographs", style = MaterialTheme.typography.titleLarge) }
                    curationUndo?.let { token -> item {
                        OutlinedButton(enabled = canModifyInputs && curationState?.revision == token.afterRevision,
                            onClick = { curate { state -> PhotoCurationCommand.Undo(state.projectId, state.revision, UUID.randomUUID().toString(), token) } }) {
                            Text("Undo last photo selection change")
                        }
                    } }
                    items(photos, key = { it.id }) { photo ->
                        val curated = curationState?.photos?.find { it.id == photo.id }
                        PhotoRow(photo, model, canModifyInputs && curated != null,
                            curated?.state == CuratedPhotoState.INCLUDED,
                            onToggle = { include -> curate { state ->
                                if (include) PhotoCurationCommand.Include(state.projectId, state.revision, UUID.randomUUID().toString(), photo.id)
                                else PhotoCurationCommand.Exclude(state.projectId, state.revision, UUID.randomUUID().toString(), photo.id,
                                    PhotoCurationReason(PhotoExclusionCode.USER_CHOICE))
                            } }, onMove = { direction -> curate { state ->
                                val ids = state.photos.map { it.id }.toMutableList()
                                val index = ids.indexOf(photo.id)
                                check(index >= 0)
                                val target = (index + direction).coerceIn(0, ids.lastIndex)
                                java.util.Collections.swap(ids, index, target)
                                PhotoCurationCommand.Reorder(state.projectId, state.revision, UUID.randomUUID().toString(), ids)
                            } }, removalAction = {
                                if (project != null) PermanentPhotoRemovalButton(model, commands, photo,
                                    ProjectRevision(project.id, project.revision), canModifyInputs ||
                                        (canMutate && project.state == "DELETING_PHOTO"))
                            })
                    }
                    item { InfoCard("Next engineering gate", "Validate the adaptive eight-view workload on physical devices. Larger complete objects still need stronger camera registration, resumable depth processing and topology repair.") }
                    item { TextButton(enabled = canMutate, onClick = {
                        project?.takeIf { it.id == selected }?.let { deleteSource = ProjectRevision(it.id, it.revision) }
                    }) { Text("Delete this local project", color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
        photographicFile?.takeIf { it == displayedPreview?.photographicModel }?.let { file ->
            PhotographicModelViewer(file) { photographicFile = null }
        }
        if (newProject) AlertDialog(
            onDismissRequest = { newProject = false }, title = { Text("New scan") },
            text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { model.create(name); name = ""; newProject = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newProject = false }) { Text("Cancel") } },
        )
        libraryCommand?.let { command -> AlertDialog(
            onDismissRequest = { libraryCommand = null },
            title = { Text(if (command.kind == "rename") "Rename project" else "Create independent copy") },
            text = { Column {
                OutlinedTextField(libraryName, { libraryName = it.take(160) }, label = { Text("Project name") }, singleLine = true)
                if (command.kind == "duplicate") Text("Copies original photos and selection into a separate project. Reconstruct the copy to create its own result.")
            } },
            confirmButton = { TextButton(enabled = canModifyInputs && runCatching { LibrarySelection.validatedName(libraryName) }.isSuccess,
                onClick = {
                    val requestedName = libraryName
                    libraryCommand = null
                    model.projectCommand { source ->
                        check(source == command.source) { "The project changed. Open this confirmation again." }
                        try {
                            if (command.kind == "rename") commands.rename(source, requestedName)
                            else commands.duplicate(source, requestedName) { copied, total ->
                                model.progress.value = com.realityforge.scanner.engine.SparsePipeline.Progress("Copying original photos",
                                    if (total == 0L) 0 else (copied * 100 / total).toInt().coerceIn(0, 100), 100)
                            }
                        } finally { model.progress.value = null }
                    }
                }) { Text(if (command.kind == "rename") "Rename" else "Duplicate") } },
            dismissButton = { TextButton(onClick = { libraryCommand = null }) { Text("Cancel") } },
        ) }
        deleteSource?.let { confirmed -> AlertDialog(
            onDismissRequest = { deleteSource = null }, title = { Text("Delete project and local originals?") },
            text = { Text("This removes the app's copies of your photos, caches and results. Photos outside this app are not deleted. This cannot be undone.") },
            confirmButton = { TextButton(enabled = canMutate, onClick = { deleteSource = null; model.deleteSelected(confirmed) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteSource = null }) { Text("Cancel") } },
        ) }
    }
}


/** Save the immutable request before launching; a late result never reads current selection. */
@Composable
internal fun rememberCaptureArchivePicker(model: ScannerViewModel): () -> Unit {
    var requestId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured = requestId
        requestId = null
        if (captured != null) {
            if (uri == null) model.cancelPendingRequest(captured)
            else model.completeCaptureArchiveRestore(captured, uri)
        }
    }
    return {
        val captured = model.beginCaptureArchiveRestoreRequest()
        if (captured != null) {
            requestId = captured
            try { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
            catch (failure: Exception) {
                requestId = null
                model.cancelPendingRequest(captured)
                model.notice.value = failure.message ?: "No archive provider is available."
            }
        }
    }
}

@Composable
internal fun rememberBoundPhotoPicker(model: ScannerViewModel, calibration: Boolean): () -> Unit {
    var requestId by rememberSaveable(calibration) { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val captured = requestId
        requestId = null
        if (captured != null) {
            if (calibration) model.completeCalibration(captured, uris)
            else model.completeImport(captured, uris)
        }
    }
    return {
        val captured = if (calibration) model.beginCalibrationRequest() else model.beginImportRequest()
        if (captured != null) {
            requestId = captured
            try {
                launcher.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"))
            } catch (error: Exception) {
                requestId = null
                model.cancelPendingRequest(captured)
                model.notice.value = error.message ?: "No photo provider is available."
            }
        }
    }
}

@Composable
internal fun rememberBoundExporter(model: ScannerViewModel, extension: String, mimeType: String): (String) -> Unit {
    var requestId by rememberSaveable(extension) { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val captured = requestId
        requestId = null
        if (captured != null) {
            if (uri == null) model.cancelPendingRequest(captured)
            else if (extension == "capture.zip") model.completeCaptureBundleExport(captured, uri)
            else model.completeArtifactExport(captured, uri)
        }
    }
    return { name ->
        val captured = if (extension == "capture.zip") model.beginCaptureBundleExportRequest()
            else model.beginArtifactExportRequest(extension)
        if (captured != null) {
            requestId = captured
            try {
                launcher.launch(name)
            } catch (error: Exception) {
                requestId = null
                model.cancelPendingRequest(captured)
                model.notice.value = error.message ?: "No document provider is available."
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PreviousResultCard(geometry: PreparedPreviewGeometry, inputRevision: Long,
    photographicAvailable: Boolean, onPhotographic: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Previous completed result · read-only", style = MaterialTheme.typography.titleLarge)
            Text("Result revision ${geometry.revision}; current input revision $inputRevision. Return to Current result to export a result for the current inputs.")
            Text("${geometry.sparse.pointCount} sparse points", style = MaterialTheme.typography.titleMedium)
            PointCloudViewer(geometry.sparse, Modifier.fillMaxWidth().height(280.dp))
            geometry.dense?.let { dense ->
                Text("${dense.pointCount} dense stereo points", style = MaterialTheme.typography.titleMedium)
                PointCloudViewer(dense, Modifier.fillMaxWidth().height(280.dp))
            }
            geometry.mesh?.let { mesh ->
                Text("${mesh.sourceTriangleCount} observed triangles", style = MaterialTheme.typography.titleMedium)
                SurfaceMeshViewer(mesh, Modifier.fillMaxWidth().height(300.dp))
            }
            if (photographicAvailable) OutlinedButton(onClick = onPhotographic) { Text("Inspect previous photographic model") }
            geometry.unavailableReasons.values.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PhotoRow(photo: ScanPhoto, model: ScannerViewModel, enabled: Boolean, included: Boolean,
    onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit, removalAction: @Composable () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, photo.id) {
        val owned = try {
            ImageLoader.decode(DecodeRequest(file = model.repository.photoFile(photo), maxEdge = 192))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        value = owned?.borrowBitmap()
        awaitDispose { owned?.close() }
    }
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), "Imported photo ${photo.id.take(8)}", Modifier.size(72.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text("${photo.width} × ${photo.height}", fontWeight = FontWeight.Medium)
            Text("${"%.1f".format(photo.bytes / 1_048_576.0)} MB · sharpness ${"%.0f".format(photo.sharpness)}", style = MaterialTheme.typography.bodySmall)
            Text(photo.focal35mm?.let { "EXIF equivalent focal length: ${it.toInt()} mm" } ?: "No 35 mm-equivalent focal metadata", style = MaterialTheme.typography.labelSmall)
            if (photo.darkFraction > .45 || photo.brightFraction > .45) Text("Large clipped area; inspect exposure.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Checkbox(checked = included, enabled = enabled, onCheckedChange = onToggle)
          Text(if (included) "Selected" else "Excluded", style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.weight(1f))
          TextButton(enabled = enabled, onClick = { onMove(-1) }) { Text("Earlier") }
          TextButton(enabled = enabled, onClick = { onMove(1) }) { Text("Later") }
      }
      removalAction()
    }
}
