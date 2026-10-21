package com.realityforge.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.app.Activity
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.realityforge.scanner.export.*
import com.realityforge.scanner.viewer.loading.GlbStructure
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.application.ProjectCommandAdapter
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.preferences.*
import com.realityforge.scanner.ui.designsystem.AppTheme
import com.realityforge.scanner.ui.library.*
import com.realityforge.scanner.ui.navigation.*
import com.realityforge.scanner.ui.onboarding.*
import com.realityforge.scanner.ui.photoreview.*
import com.realityforge.scanner.ui.processing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feature routes keep the audited ownership, SAF, capture and recovery workspace available. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(model: ScannerViewModel, features: ScannerFeatureServices? = null) {
    val context = LocalContext.current
    val installed = remember(model, context) { installedScannerFeatures(model, context) }
    val services = features ?: installed
    val commands = remember(model, context) { ProjectCommandAdapter(model.compositionRoot, context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Results can be redelivered after recreation; they must never submit reconstruction.
        ReconstructionNotificationRequest.permissionMessage(granted)?.let {
            if (model.notice.value == null) model.notice.value = it
        }
    }
    val scope = rememberCoroutineScope()
    val projects by model.projects.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val project by model.selectedProject.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val scanner by model.scannerState.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val calibration by model.calibrationStatus.collectAsStateWithLifecycle()
    val archive by model.pendingArchiveRestoreId.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val exportSource = remember(model.repository) { RepositoryExportSource(model.repository,
        geometryValidator = { file, mode -> GlbStructure.validate(file, mode); Unit },
        imageValidator = { file -> GlbStructure.validatePng(file); Unit },
        publication = { model.compositionRoot.publications.current(it) }) }
    var exportRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var stlConfirmation by remember { mutableStateOf<Pair<ArtifactIdentity, ExportOption>?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val captured = exportRequestId
        exportRequestId = null
        if (captured != null) {
            val destination = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
            if (destination == null) model.cancelPendingRequest(captured)
            else model.completeArtifactExport(captured, destination)
        }
    }
    fun export(identity: ArtifactIdentity, option: ExportOption, acknowledged: Boolean = false) {
        if (exportRequestId != null || model.selectedId.value != identity.projectId ||
            model.selectedProject.value?.revision != identity.revision ||
            model.selectedProject.value?.resultKey != identity.artifactKey) return
        val id = if (option.id in setOf("mesh.obj.zip", "mesh.stl"))
            model.beginDerivedExportRequest(identity, option, acknowledged)
        else model.beginArtifactExportRequest(option.id)
        if (id != null) {
            exportRequestId = id
            try {
                exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(option.format.mime).putExtra(Intent.EXTRA_TITLE, "realityforge-${option.id}"))
            } catch (error: Exception) {
                exportRequestId = null; model.cancelPendingRequest(id)
                model.notice.value = error.message ?: "No document provider is available."
            }
        }
    }
    val mutable = !busy && scanner.pendingRequests.isEmpty() && archive == null
    var route by rememberSaveable { mutableStateOf("library") }
    var workspace by rememberSaveable { mutableStateOf(false) }
    var openCreated by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectRevision?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val prefsStore = remember(context) { PreferencesStore(context) }
    var prefs by remember { mutableStateOf(PreferenceRead(UserPreferences())) }
    val importPhotos = rememberBoundPhotoPicker(model, false)
    val calibrate = rememberBoundPhotoPicker(model, true)
    val restoreArchive = rememberCaptureArchivePicker(model)
    val exportArchive = rememberBoundExporter(model, "capture.zip", "application/zip")
    LaunchedEffect(prefsStore) {
        prefs = withContext(Dispatchers.IO) { prefsStore.read() }
        if (prefs.issue == null && prefs.preferences.onboardingCompletedVersion < CaptureGuide.VERSION && route == "library") route = "onboarding"
    }
    LaunchedEffect(selected) {
        if (selected == null && route !in setOf("settings", "onboarding")) { route = "library"; workspace = false }
        else if (selected != null && route == "library" && openCreated) { route = "review"; openCreated = false }
    }
    LaunchedEffect(notice, workspace) {
        if (!workspace) notice?.let { snackbars.showSnackbar(it); if (model.notice.value == it) model.notice.value = null }
    }
    val dark = when (prefs.preferences.theme) {
        DisplayTheme.SYSTEM -> isSystemInDarkTheme()
        DisplayTheme.DARK -> true
        DisplayTheme.LIGHT -> false
    }
    fun back() { if (route in setOf("settings", "onboarding")) route = if (selected == null) "library" else "review"
        else if (route != "review" && selected != null) route = "review"
        else if (mutable) { model.select(null); route = "library" } }
    AppTheme(darkTheme = dark) {
        if (workspace) {
            AuditedScannerWorkspace(model) { workspace = false; route = if (selected == null) "library" else "review" }
        } else {
            BackHandler(route != "library") { back() }
            Scaffold(
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                topBar = { TopAppBar(title = { Text(when (route) {
                    "library" -> "RealityForge"; "review" -> project?.name ?: "Photo review"
                    "processing" -> "Processing"; "studio" -> "Model studio"; "export" -> "Export"; "settings" -> "Settings"; else -> "Capture guide"
                }) }, navigationIcon = {
                    if (route != "library") TextButton(onClick = { back() }, modifier = Modifier.testTag("a071-back")) { Text("Back") }
                }, actions = {
                    TextButton(onClick = { menu = true }, modifier = Modifier.testTag("a071-open-menu")) { Text("More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Capture guide") }, onClick = { menu = false; route = "onboarding" }, modifier = Modifier.testTag("a071-open-help"))
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; route = "settings" }, modifier = Modifier.testTag("a071-open-settings"))
                        DropdownMenuItem(text = { Text("Storage and recovery") }, onClick = { menu = false; workspace = true })
                    }
                }) },
                snackbarHost = { SnackbarHost(snackbars) },
            ) { padding ->
                val modifier = Modifier.fillMaxSize().padding(padding).testTag("a071-route-$route")
                when (route) {
                    "onboarding" -> {
                        fun finishGuide() {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) {
                                    prefsStore.save(prefs.preferences.copy(onboardingCompletedVersion = CaptureGuide.VERSION))
                                    prefsStore.read()
                                } }.onSuccess { prefs = it }.onFailure { model.notice.value = it.message }
                            }
                            route = if (selected == null) "library" else "review"
                        }
                        OnboardingScreen(onComplete = { finishGuide() }, onSkip = { finishGuide() }, modifier = modifier)
                    }
                    "settings" -> PreferencesRoute(!mutable, modifier, onHelp = { route = "onboarding" }, read = prefs, onSaved = { prefs = it })
                    "library" -> {
                        val presenter = remember(services.library) { LibraryPresenter(requireNotNull(services.library)) { it.message ?: "Cannot read project library." } }
                        val library by presenter.state.collectAsStateWithLifecycle()
                        LaunchedEffect(projects) { presenter.reload() }
                        LibraryScreen(library.copy(busy = !mutable, canRestoreArchive = true), onAction = { action ->
                            when (action) {
                                is LibraryAction.Create -> if (mutable) { openCreated = true; model.create(action.name) }
                                is LibraryAction.Open -> if (mutable) { model.select(action.projectId); route = "review" }
                                is LibraryAction.Delete -> if (mutable) { model.select(action.projectId); deleting = ProjectRevision(action.projectId, action.revision) }
                                is LibraryAction.Rename -> if (mutable) {
                                    model.select(action.projectId)
                                    model.projectCommand { source ->
                                        check(source == ProjectRevision(action.projectId, action.revision)) { "Project changed." }
                                        commands.rename(source, action.name)
                                    }
                                }
                                is LibraryAction.Duplicate -> if (mutable) {
                                    model.select(action.projectId)
                                    model.projectCommand { source ->
                                        check(source == ProjectRevision(action.projectId, action.revision)) { "Project changed." }
                                        commands.duplicate(source, action.name ?: projects.first { it.id == action.projectId }.name)
                                    }
                                }
                                LibraryAction.RestoreArchive -> if (mutable) { openCreated = true; restoreArchive() }
                                LibraryAction.Retry -> scope.launch { presenter.reload() }
                                LibraryAction.LoadMore -> scope.launch { presenter.loadMore() }
                            }
                        }, modifier = modifier, onQueryChange = { query, sort, filter -> scope.launch { presenter.refresh(query, sort, filter) } })
                    }
                    "studio" -> StudioRoute(preview, model, modifier) { route = "export" }
                    "export" -> selected?.let { id ->
                        ExportRoute(id, exportSource, modifier, !mutable, onExport = { identity, option ->
                            if (option.id == "mesh.stl") stlConfirmation = identity to option
                            else export(identity, option)
                        }, onArchive = { exportArchive("realityforge-capture.zip") })
                    }
                    "processing" -> {
                        val p = project?.takeIf { it.id == selected }
                        val flow = remember(services.jobStates, p?.id, p?.revision) { p?.let { services.jobStates?.invoke(it.id, it.revision) } }
                        val job = flow?.collectAsState(initial = null)?.value
                        if (job != null) Column(modifier) {
                            ProcessingScreen(job, onAction = { action -> services.jobAction?.invoke(action) ?: false }, modifier = Modifier.weight(1f))
                            TextButton(onClick = { route = "review" }) { Text("Back to photographs") }
                            if (job.phase == JobPhase.COMPLETED) Button(onClick = { route = "studio" }) { Text("Inspect and export result") }
                        } else Column(modifier.padding(16.dp)) {
                            Text("No saved processing job for this project.")
                            TextButton(onClick = { route = "review" }) { Text("Back to photographs") }
                        }
                    }
                    else -> {
                        val p = project?.takeIf { it.id == selected }
                        if (p == null) Box(modifier.padding(16.dp)) { Text("Opening project…") }
                        else {
                            val blocked = p.state in setOf("RECOVERY_REQUIRED", "DELETING", "DELETING_PHOTO")
                            val metadata = ProjectReviewUiState(p.id, p.revision, photos.map {
                                ReviewPhoto.from(it, model.repository.photoFile(it))
                            }, p.name, busy = !mutable || blocked, calibrationStatus = calibration,
                                canInspectResult = p.resultKey != null && p.resultRevision == p.revision,
                                diagnostics = if (blocked) listOf("Open Storage and recovery to finish the interrupted operation.") else emptyList())
                            val presenter = remember(services.curation, services.removePhotos) { PhotoCurationPresenter(
                                requireNotNull(services.curation), { "Photo selection was not changed: $it" },
                                { it.message ?: "Cannot update photo selection." }, services.removePhotos, true) }
                            Column(modifier) {
                                CurationReviewScreen(presenter, metadata, onHostAction = { action ->
                                    if (action is ReviewAction.Command && mutable && action.projectId == selected &&
                                        action.revision == model.selectedProject.value?.revision) when (action.command) {
                                        ReviewCommand.IMPORT -> importPhotos()
                                        ReviewCommand.CALIBRATE -> calibrate()
                                        ReviewCommand.CLEAR_CALIBRATION -> model.clearLensCalibration()
                                        ReviewCommand.CAPTURE -> { if (model.beginCaptureRequest() != null) workspace = true }
                                        ReviewCommand.OPEN_RESULT -> route = "studio"
                                        ReviewCommand.HELP -> route = "onboarding"
                                        else -> Unit
                                    }
                                }, onReconstruct = { command, lease ->
                                    // A curation admission may finish after navigation or another tap.
                                    if (route == "review" && !model.busy.value &&
                                        model.scannerState.value.pendingRequests.isEmpty() &&
                                        model.pendingArchiveRestoreId.value == null &&
                                        command.projectId == model.selectedId.value &&
                                        command.revision == model.selectedProject.value?.revision) {
                                        ReconstructionNotificationRequest.submit(
                                            reconstruct = services.reconstructSelection?.let { submit ->
                                                { submit(command, lease) }
                                            },
                                            inspectJobs = { route = "processing" },
                                            requestNotifications = {
                                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                                                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                            },
                                            notify = { if (model.notice.value == null) model.notice.value = it },
                                        )
                                    }
                                }, modifier = Modifier.weight(1f), fallbackFocal35mm = prefs.preferences.fallbackFocal35mm)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { route = "processing" }) { Text("Saved processing job") }
                                    TextButton(onClick = { workspace = true }) { Text("Results, export and storage") }
                                }
                            }
                        }
                    }
                }
            }
            stlConfirmation?.let { (identity, option) ->
                AlertDialog(onDismissRequest = { stlConfirmation = null }, title = { Text("Export unitless STL?") },
                    text = { Text("This observed mesh has arbitrary scale. STL has no textures or units and may retain open boundaries. Set and verify scale before printing.") },
                    confirmButton = { TextButton(enabled = mutable, onClick = {
                        stlConfirmation = null; export(identity, option, true)
                    }) { Text("Acknowledge and export") } },
                    dismissButton = { TextButton(onClick = { stlConfirmation = null }) { Text("Cancel") } })
            }
            deleting?.let { source ->
                AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete local project?") },
                    text = { Text("Deletes this app's original photos and results. This cannot be undone. Photos outside the app are not deleted.") },
                    confirmButton = { TextButton(enabled = mutable, onClick = { deleting = null; model.deleteSelected(source) }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
            }
        }
    }
}
