package com.realityforge.scanner.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.realityforge.scanner.preferences.*
import com.realityforge.scanner.ui.onboarding.CaptureGuide
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realityforge.scanner.BuildConfig
import com.realityforge.scanner.R
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.export.*
import com.realityforge.scanner.viewer.loading.GlbStructure
import com.realityforge.scanner.ui.designsystem.AppTheme
import com.realityforge.scanner.ui.library.*
import com.realityforge.scanner.ui.navigation.*
import com.realityforge.scanner.ui.onboarding.OnboardingScreen
import com.realityforge.scanner.ui.photoreview.*
import com.realityforge.scanner.ui.processing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Composition owns routes/picker envelopes. Domain owners still admit and execute operations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(model: ScannerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by model.projects.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val calibration by model.calibrationStatus.collectAsStateWithLifecycle()
    var routeValue by rememberSaveable { mutableStateOf(AppRoute(Destination.LIBRARY).encode()) }
    val route = AppRoute.restore(routeValue)
    var pendingValue by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = PendingRequest.restore(pendingValue)
    var deleteValue by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocal by rememberSaveable { mutableDoubleStateOf(26.0) }
    val snackbars = remember { SnackbarHostState() }
    var overflowMenu by rememberSaveable {mutableStateOf(false)}
    val preferencesStore=remember(context) {PreferencesStore(context)}
    var preferences by remember { mutableStateOf(PreferenceRead(UserPreferences())) }
    LaunchedEffect(preferencesStore) {
        preferences=withContext(Dispatchers.IO) {preferencesStore.read()}
        if(preferences.issue==null && preferences.preferences.onboardingCompletedVersion<CaptureGuide.VERSION && AppRoute.restore(routeValue).destination==Destination.LIBRARY) routeValue=AppRoute(Destination.ONBOARDING).encode()
    }
    val routeFocus=remember {FocusRequester()}
    LaunchedEffect(routeValue,pendingValue) { if(pendingValue==null) runCatching {routeFocus.requestFocus()} }
    val source = remember(model.repository) { RepositoryExportSource(model.repository) {file,mode->GlbStructure.validate(file,mode);Unit} }
    val exportStore=remember(context) {PendingExportStore(File(context.filesDir,"ui-pending-export/request.v1"))}
    var invalidExport by remember {mutableStateOf(false)}
    var exportAdmission by remember {mutableStateOf(false)}
    LaunchedEffect(exportStore) {
        val restore=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}
        invalidExport=restore.isFailure
        val restored=restore.getOrNull()
        if(restored!=null && pendingValue==null) pendingValue=PendingRequest(restored.requestId,PendingKind.EXPORT,restored.artifact.projectId,restored.artifact.revision,restored.artifact.artifactKey,restored.option.id).encode()
    }
    val staleText = stringResource(R.string.a071_pending_stale)
    val pendingText = stringResource(R.string.a071_picker_busy)
    val errorText = stringResource(R.string.a071_error)
    fun navigate(next: AppRoute) { routeValue = next.encode() }
    fun currentRequest(kind: PendingKind, extension: String? = null): PendingRequest? {
        if (PendingRequest.restore(pendingValue) != null || model.busy.value || exportAdmission) { model.notice.value = pendingText; return null }
        val project = model.projects.value.find { it.id == model.selectedId.value } ?: return null
        return PendingRequest.create(kind, project.id, project.revision,
            if (kind == PendingKind.EXPORT) project.resultKey else null, extension)
    }
    fun consume(kind: PendingKind): PendingRequest? {
        val request = PendingRequest.restore(pendingValue)
        pendingValue = null
        val project = model.projects.value.find { it.id == request?.projectId }
        return request?.takeIf { it.kind == kind && it.accepts(model.selectedId.value, project?.revision, project?.resultKey, model.busy.value) }
            .also { if (it == null && request != null) model.notice.value = staleText }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (consume(PendingKind.IMPORT) != null && uris.isNotEmpty()) model.import(uris)
    }
    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (consume(PendingKind.CALIBRATE) != null && uris.isNotEmpty()) model.calibrateLens(uris)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = PendingRequest.restore(pendingValue)
        val kind = request?.kind ?: return@rememberLauncherForActivityResult
        val accepted = consume(kind)
        val uri = result.data?.data
        if(kind==PendingKind.EXPORT) {
          exportAdmission=true
          scope.launch {
            val typed=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}.getOrNull()}
            try {
                if(accepted!=null && typed?.requestId==accepted.requestId && result.resultCode==Activity.RESULT_OK && uri!=null) {
                    val valid=withContext(Dispatchers.IO) {runCatching {source.isCurrent(typed.artifact)}.getOrDefault(false)}
                    val current=model.projects.value.find {it.id==accepted.projectId}
                    if(valid && accepted.accepts(model.selectedId.value,current?.revision,current?.resultKey,model.busy.value)) model.export(uri,requireNotNull(accepted.extension))
                    else model.notice.value=staleText
                }
            } finally {
                if(typed!=null && typed.requestId==request?.requestId) withContext(NonCancellable+Dispatchers.IO) {runCatching {exportStore.clear(typed.requestId)}}
                exportAdmission=false
            }
          }
        } else if(accepted!=null && result.resultCode==Activity.RESULT_OK && uri!=null && kind==PendingKind.CAPTURE_ARCHIVE) model.exportCaptureBundle(uri)
    }
    LaunchedEffect(notice) { notice?.let { snackbars.showSnackbar(it); model.notice.value = null } }
    // Selection is restored from an explicit saved route, never from an activity-result payload.
    LaunchedEffect(route.projectId) {
        val id=route.projectId
        if(id!=null && model.selectedId.value!=id && !model.busy.value) {
            if(withContext(Dispatchers.IO) {model.repository.dao.project(id)}==null) {navigate(AppRoute(Destination.LIBRARY));model.select(null)} else model.select(id)
        }
    }
    LaunchedEffect(selected) {
        if (selected != null && route.destination == Destination.LIBRARY) navigate(AppRoute(Destination.REVIEW, selected))
    }
    LaunchedEffect(routeValue,pendingValue) {
        if(route.destination==Destination.CAPTURE && (pending?.kind!=PendingKind.CAMERA || pending.projectId!=route.projectId)) navigate(route.back())
    }
    BackHandler(route.destination != Destination.LIBRARY) {
        if (route.destination == Destination.CAPTURE) pendingValue = null
        val back = route.back()
        navigate(back)
        if (back.destination == Destination.LIBRARY && !busy) model.select(null)
    }
    fun beginImport() { currentRequest(PendingKind.IMPORT)?.let { pendingValue=it.encode(); runCatching {importPicker.launch(imageTypes)}.onFailure {pendingValue=null;model.notice.value=errorText} } }
    fun beginCamera() { currentRequest(PendingKind.CAMERA)?.let { pendingValue=it.encode(); navigate(AppRoute(Destination.CAPTURE,it.projectId)) } }
    fun beginCalibration() { currentRequest(PendingKind.CALIBRATE)?.let { pendingValue=it.encode(); runCatching {calibrationPicker.launch(imageTypes)}.onFailure {pendingValue=null;model.notice.value=errorText} } }
    fun launchExport(identity:ArtifactIdentity,option:ExportOption) {
        val admitted=currentRequest(PendingKind.EXPORT,option.id) ?: return
        if(admitted.projectId!=identity.projectId || admitted.revision!=identity.revision || admitted.artifactKey!=identity.artifactKey) {model.notice.value=staleText;return}
        val typed=RepositoryExportSource.request(identity,option)
        pendingValue=PendingRequest(typed.requestId,PendingKind.EXPORT,identity.projectId,identity.revision,identity.artifactKey,option.id).encode()
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {exportStore.save(typed)}}
                .onSuccess {
                    if(PendingRequest.restore(pendingValue)?.requestId!=typed.requestId) {
                        withContext(Dispatchers.IO) {exportStore.clear(typed.requestId)}
                    } else runCatching {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(option.format.mime).putExtra(Intent.EXTRA_TITLE,"realityforge-${option.representation.name.lowercase()}.${option.format.extension}"))}
                        .onFailure {pendingValue=null;withContext(NonCancellable+Dispatchers.IO) {runCatching {exportStore.clear(typed.requestId)}};model.notice.value=errorText}
                }
                .onFailure {pendingValue=null;invalidExport=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}.isFailure};model.notice.value=staleText}
        }
    }
    fun leaveGuide() {
        if(preferences.issue==null) scope.launch {
            val next=preferences.preferences.copy(onboardingCompletedVersion=CaptureGuide.VERSION)
            runCatching {withContext(Dispatchers.IO) {preferencesStore.save(next);preferencesStore.read()}}.onSuccess {preferences=it}
        }
        navigate(route.back())
    }
    val dark=when(preferences.preferences.theme) { DisplayTheme.SYSTEM->isSystemInDarkTheme();DisplayTheme.DARK->true;DisplayTheme.LIGHT->false }
    AppTheme(darkTheme=dark) {
        Box(Modifier.semantics { testTagsAsResourceId=true }) {
        if (route.destination == Destination.CAPTURE && route.projectId == selected && pending?.kind == PendingKind.CAMERA) {
            CameraScreen(onClose = { pendingValue=null; navigate(route.back()) }, onPhoto = { file ->
                val accepted = consume(PendingKind.CAMERA)
                navigate(route.back())
                if (accepted != null) model.captureReady(file) else file.delete()
            })
        } else Scaffold(
            topBar = { TopAppBar(title={Text(stringResource(routeTitle(route.destination)),Modifier.focusRequester(routeFocus).focusable().semantics {heading()})},
                navigationIcon={if(route.destination != Destination.LIBRARY) TextButton(onClick={
                    val back=route.back(); navigate(back); if(back.destination==Destination.LIBRARY && !busy) model.select(null)
                },modifier=Modifier.testTag("a071-back")) {Text(stringResource(R.string.a071_back))}},
                actions={
                    TextButton(onClick={overflowMenu=true},modifier=Modifier.testTag("a071-open-menu")) {Text(stringResource(R.string.a071_more))}
                    DropdownMenu(expanded=overflowMenu,onDismissRequest={overflowMenu=false},modifier=Modifier.semantics {testTagsAsResourceId=true}) {
                        DropdownMenuItem(text={Text(stringResource(R.string.a071_help))},onClick={overflowMenu=false;navigate(AppRoute(Destination.ONBOARDING,selected))},modifier=Modifier.testTag("a071-open-help"))
                        DropdownMenuItem(text={Text(stringResource(R.string.a071_settings))},onClick={overflowMenu=false;navigate(AppRoute(Destination.SETTINGS,selected))},modifier=Modifier.testTag("a071-open-settings"))
                    }
                }) },
            snackbarHost={SnackbarHost(snackbars)},
            bottomBar={if(pending!=null && pending.kind!=PendingKind.CAMERA) Surface(tonalElevation=3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.a071_pending_waiting))
                    TextButton(onClick={
                        val cancelled=PendingRequest.restore(pendingValue);pendingValue=null
                        if(cancelled?.kind==PendingKind.EXPORT) scope.launch {withContext(Dispatchers.IO) {runCatching {exportStore.clear(cancelled.requestId)}}}
                    },modifier=Modifier.testTag("a071-cancel-pending")) {Text(stringResource(R.string.a071_cancel_pending))}
                }
            }},
        ) { padding ->
            val modifier=Modifier.fillMaxSize().padding(padding).testTag("a071-route-${route.destination.name.lowercase()}")
            val project=projects.find { it.id==route.projectId }
            when(route.destination) {
                Destination.LIBRARY -> LibraryScreen(LibraryUiState(projects.map(LibraryProject::from),busy=busy),onAction={action->when(action) {
                    is LibraryAction.Create -> model.create(action.name)
                    is LibraryAction.Open -> if(!busy) {model.select(action.projectId);navigate(AppRoute(Destination.REVIEW,action.projectId))}
                    is LibraryAction.Delete -> if(!busy) { model.select(action.projectId); deleteValue="${action.projectId}\n${action.revision}" }
                    else -> Unit // Unsupported commands are not offered by this baseline adapter.
                }},modifier=modifier)
                Destination.ONBOARDING -> OnboardingScreen(onComplete=::leaveGuide,onSkip=::leaveGuide,modifier=modifier)
                Destination.SETTINGS -> PreferencesRoute(busy,modifier,onHelp={navigate(AppRoute(Destination.ONBOARDING,selected))},read=preferences,onSaved={preferences=it})
                else -> if(project == null || selected != project.id) { Box(modifier.padding(16.dp)) {Text(stringResource(R.string.a071_loading))} }
                else when(route.destination) {
                    Destination.REVIEW -> {
                        val evidence=preview?.optJSONObject("scanDiagnostics")
                        val lines=if(evidence==null) emptyList() else listOf(stringResource(R.string.a071_evidence_counts,
                            evidence.optInt("registeredCameraCount"),evidence.optInt("importedPhotoCount"),evidence.optInt("observedMeshTriangles")),stringResource(R.string.a071_evidence_limits))
                        val state=ProjectReviewUiState(project.id,project.revision,photos.map { ReviewPhoto.from(it,model.repository.photoFile(it)) },project.name,
                            busy=busy,calibrationStatus=calibration,diagnostics=lines,canCurate=false,canInspectResult=preview!=null)
                        ProjectReviewScreen(state,onAction={action->
                            if(ReviewAdmission.allowed(state,action) && action is ReviewAction.Command) when(action.command) {
                                ReviewCommand.IMPORT -> beginImport()
                                ReviewCommand.CAPTURE -> beginCamera()
                                ReviewCommand.CALIBRATE -> beginCalibration()
                                ReviewCommand.CLEAR_CALIBRATION -> model.clearLensCalibration()
                                ReviewCommand.RECONSTRUCT -> { lastFocal=requireNotNull(action.focal35mm);model.reconstruct(lastFocal);navigate(AppRoute(Destination.PROCESSING,project.id)) }
                                ReviewCommand.OPEN_RESULT -> navigate(AppRoute(Destination.STUDIO,project.id))
                                ReviewCommand.HELP -> navigate(AppRoute(Destination.ONBOARDING,selected))
                                else -> Unit
                            }
                        },modifier=modifier,fallbackFocal35mm=preferences.preferences.fallbackFocal35mm)
                    }
                    Destination.PROCESSING -> {
                        val phase=if(busy) JobPhase.RUNNING else when(project.state) {"INTERRUPTED"->JobPhase.INTERRUPTED;"FAILED"->JobPhase.FAILED;"SPARSE_READY"->JobPhase.COMPLETED;else->JobPhase.PAUSED}
                        val state=JobUiState(JobIdentity(project.id,project.revision,"legacy-${project.id}-${project.revision}"),phase,
                            stage=progress?.stage,completed=progress?.completed?.toLong(),total=progress?.total?.toLong(),
                            message=project.message,hasPreviousResult=preview!=null,pauseSupported=true,resumeSupported=false)
                        ProcessingScreen(state,onAction={action->if(action.identity==state.identity && state.can(action.command)) when(action.command) {
                            JobCommand.PAUSE -> model.pause()
                            JobCommand.RETRY -> model.reconstruct(lastFocal)
                            JobCommand.OPEN_PREVIOUS -> navigate(AppRoute(Destination.STUDIO,project.id))
                            else -> Unit
                        }},modifier=modifier)
                    }
                    Destination.STUDIO -> StudioRoute(preview,model,modifier,onExport={navigate(AppRoute(Destination.EXPORT,project.id))})
                    Destination.EXPORT -> ExportRoute(project.id,source,modifier,busy || exportAdmission || pending!=null,onExport=::launchExport,onArchive={
                        currentRequest(PendingKind.CAPTURE_ARCHIVE)?.let { pendingValue=it.encode();exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"realityforge-capture.zip")) }
                    })
                    else -> Unit
                }
            }
        }
        if(invalidExport) AlertDialog(onDismissRequest={},title={Text(stringResource(R.string.a071_pending_recovery_title))},
            text={Text(stringResource(R.string.a071_pending_recovery_body))},confirmButton={TextButton(onClick={scope.launch {
                runCatching {withContext(Dispatchers.IO) {exportStore.quarantineInvalid()}}.onSuccess {pendingValue=null;invalidExport=false}.onFailure {model.notice.value=staleText}
            }}) {Text(stringResource(R.string.a071_pending_recover))}},dismissButton={TextButton(onClick={invalidExport=false}) {Text(stringResource(R.string.a071_cancel))}})
        deleteValue?.split('\n')?.takeIf { it.size==2 }?.let { request ->
            AlertDialog(onDismissRequest={deleteValue=null},title={Text(stringResource(R.string.a071_delete_title))},
                text={Text(stringResource(R.string.a071_delete_body))},confirmButton={TextButton(enabled=!busy,onClick={
                    val original=projects.find { it.id==request[0] };deleteValue=null
                    if(original?.revision==request[1].toLongOrNull() && selected==original.id) model.deleteSelected() else model.notice.value=staleText
                    navigate(AppRoute(Destination.LIBRARY))
                }) {Text(stringResource(R.string.a071_delete))}},dismissButton={TextButton(onClick={deleteValue=null}) {Text(stringResource(R.string.a071_cancel))}})
        }
        }
    }
}
private val imageTypes=arrayOf("image/jpeg","image/png","image/webp","image/heic","image/heif")
private fun routeTitle(destination:Destination):Int=when(destination) {
    Destination.LIBRARY->R.string.app_name;Destination.REVIEW->R.string.a071_review;Destination.CAPTURE->R.string.a071_camera
    Destination.PROCESSING->R.string.a071_processing;Destination.STUDIO->R.string.a071_studio;Destination.EXPORT->R.string.a071_export
    Destination.SETTINGS->R.string.a071_settings;Destination.ONBOARDING->R.string.a071_help
}
