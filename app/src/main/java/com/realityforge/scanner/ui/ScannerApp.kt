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
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.CalibrationState
import com.realityforge.scanner.localization.UserMessageResolver
import com.realityforge.scanner.localization.LocalizedFormats
import androidx.compose.ui.platform.LocalConfiguration
import com.realityforge.scanner.ui.designsystem.AppTheme
import com.realityforge.scanner.ui.library.*
import com.realityforge.scanner.ui.navigation.*
import com.realityforge.scanner.ui.onboarding.OnboardingScreen
import com.realityforge.scanner.ui.photoreview.*
import com.realityforge.scanner.ui.processing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Composition owns routes/picker envelopes. Domain owners still admit and execute operations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(model: ScannerViewModel,features:ScannerFeatureServices=ScannerFeatureServices()) {
    val context = LocalContext.current
    val messages=remember(context) {UserMessageResolver(context)}
    val locale=LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val scanner by model.scannerState.collectAsStateWithLifecycle()
    val projects by model.projects.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val calibration=when(val optical=scanner.calibration) {
        is CalibrationState.Calibrated -> stringResource(R.string.a071_calibrated,optical.acceptedViews,LocalizedFormats.number(optical.rmsPixels,locale))
        is CalibrationState.Loading -> stringResource(R.string.a071_calibration_loading)
        is CalibrationState.Invalid -> stringResource(R.string.a071_calibration_invalid)
        else -> stringResource(R.string.a071_calibration_estimated)
    }
    var routeValue by rememberSaveable { mutableStateOf(AppRoute(Destination.LIBRARY).encode()) }
    val route = AppRoute.restore(routeValue)
    var pendingValue by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = PendingRequest.restore(pendingValue)
    var pendingEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var launchEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var callbackSlotsValue by rememberSaveable { mutableStateOf(PickerCallbackSlots.empty().encode()) }
    fun setPending(request:PendingRequest?) { pendingEpoch++;pendingValue=request?.encode() }
    fun setSlots(slots:PickerCallbackSlots) { launchEpoch++;callbackSlotsValue=slots.encode() }
    fun clearPending(request:PendingRequest) { if(PendingRequest.restore(pendingValue)==request) setPending(null) }

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
    val source = remember(model.repository) { RepositoryExportSource(model.repository,geometryValidator={file,mode->GlbStructure.validate(file,mode);Unit},imageValidator={file->GlbStructure.validatePng(file);Unit}) }
    val exportStore=remember(context) {PendingExportStore(File(context.filesDir,"ui-pending-export/request.v1"))}
    var invalidExport by remember {mutableStateOf(false)}
    var exportAdmission by remember {mutableStateOf(false)}
    val staleText = stringResource(R.string.a071_pending_stale)
    val pendingText = stringResource(R.string.a071_picker_busy)
    val drainingText = stringResource(R.string.a071_picker_draining)
    val unconfirmedText = stringResource(R.string.a071_export_unconfirmed)
    val errorText = stringResource(R.string.a071_error)
    fun navigate(next: AppRoute) { routeValue = next.encode() }
    fun currentRequest(kind: PendingKind, extension: String? = null): PendingRequest? {
        if (PendingRequest.restore(pendingValue) != null || model.busy.value || exportAdmission) { model.notice.value = pendingText; return null }
        if(!PickerCallbackSlots.restore(callbackSlotsValue).canLaunch(kind)) {model.notice.value=drainingText;return null}
        val project = model.projects.value.find { it.id == model.selectedId.value } ?: return null
        val requestId=when(kind) {
            PendingKind.IMPORT->model.beginImportRequest()
            PendingKind.CAMERA->model.beginCaptureRequest()
            PendingKind.CALIBRATE->model.beginCalibrationRequest()
            PendingKind.EXPORT->extension?.let(model::beginArtifactExportRequest)
            PendingKind.CAPTURE_ARCHIVE->model.beginCaptureBundleExportRequest()
        } ?: return null
        return PendingRequest(requestId,kind,project.id,project.revision,
            if (kind == PendingKind.EXPORT) project.resultKey else null, extension)
    }
    fun cancelRequest(request:PendingRequest) {
        clearPending(request)
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).cancel(request.requestId))
        model.cancelPendingRequest(request.requestId)
    }
    fun consume(slot:PickerCallbackSlot):PendingRequest? {
        val current=PendingRequest.restore(pendingValue)
        val consumed=PickerCallbackBinding.consume(slot,current,model.busy.value)
        if(consumed.pendingAfter!=current) setPending(consumed.pendingAfter)
        consumed.cancelRequestId?.let {model.cancelPendingRequest(it)}
        if(consumed.accepted==null && !slot.cancelled) model.notice.value=staleText
        return consumed.accepted
    }
    fun takeCallback(kind:PendingKind):PickerCallbackSlot? {
        val (slot,remaining)=PickerCallbackSlots.restore(callbackSlotsValue).take(kind)
        setSlots(remaining)
        return slot
    }
    fun launchPicker(request:PendingRequest,launch:()->Unit):Boolean = try {
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).launched(request))
        launch();true
    } catch(_:Exception) {
        // A synchronous launch failure has no outstanding Android result to drain.
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).launchFailed(request.requestId))
        clearPending(request);model.cancelPendingRequest(request.requestId);model.notice.value=errorText
        false
    }
    suspend fun clearStored(requestId:String):Boolean = withContext(NonCancellable+Dispatchers.IO) {
        runCatching {exportStore.clear(requestId)}.isSuccess
    }
    LaunchedEffect(exportStore) {
        val snapshot=ExportRestoreSnapshot(pendingEpoch,launchEpoch,PendingRequest.restore(pendingValue))
        val restore=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}
        if(PickerCallbackBinding.epochsMatch(snapshot,pendingEpoch,launchEpoch)) invalidExport=restore.isFailure
        val restored=restore.getOrNull()
        if(restored!=null && PickerCallbackBinding.epochsMatch(snapshot,pendingEpoch,launchEpoch)) {
            val request=PendingRequest(restored.requestId,PendingKind.EXPORT,restored.artifact.projectId,restored.artifact.revision,restored.artifact.artifactKey,restored.option.id)
            val slots=PickerCallbackSlots.restore(callbackSlotsValue)
            val terminal=slots.terminalExport(request)
            if(terminal!=null) {
                // Death may precede callback-finally cleanup. Retire only the saved terminal origin;
                // a mismatched durable request and any newer pending request remain untouched.
                clearPending(terminal)
                model.cancelPendingRequest(terminal.requestId)
                if(!clearStored(terminal.requestId)) model.notice.value=unconfirmedText
            } else if(PickerCallbackBinding.mayRestore(snapshot,pendingEpoch,launchEpoch,PendingRequest.restore(pendingValue),exportAdmission) && slots.permitsRestore(request)) setPending(request)
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeCallback(PendingKind.IMPORT)?.let {slot->consume(slot)?.let {model.completeImport(it.requestId,uris)}}
    }
    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeCallback(PendingKind.CALIBRATE)?.let {slot->consume(slot)?.let {model.completeCalibration(it.requestId,uris)}}
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val slot=takeCallback(PendingKind.EXPORT) ?: return@rememberLauncherForActivityResult
        val request=slot.request
        val accepted=consume(slot)
        val uri=result.data?.data
        if(request.kind==PendingKind.EXPORT) {
            exportAdmission=true
            scope.launch {
                var typed:ExportRequest?=null
                try {
                    val restored=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}
                    typed=restored.getOrNull()
                    if(restored.isFailure) invalidExport=true
                    if(accepted!=null && PickerCallbackBinding.matchesStoredExport(request,typed) && result.resultCode==Activity.RESULT_OK && uri!=null) {
                        val fixed=requireNotNull(typed)
                        val valid=withContext(Dispatchers.IO) {runCatching {
                            if(!source.isCurrent(fixed.artifact)) false else {
                                source.validate(fixed,model.repository.artifact(fixed.artifact.projectId,fixed.artifact.artifactKey,fixed.option.id))
                                source.isCurrent(fixed.artifact)
                            }
                        }.getOrDefault(false)}
                        if(valid) model.completeArtifactExport(request.requestId,uri)
                        else model.notice.value=unconfirmedText
                    } else if(result.resultCode==Activity.RESULT_OK && uri!=null) model.notice.value=unconfirmedText
                } catch(cancelled:CancellationException) {
                    model.notice.value=unconfirmedText
                    throw cancelled
                } finally {
                    // C1 removes an admitted request before transfer. This cancels only an unclaimed
                    // original, including mismatched records; it never cancels another/new request.
                    model.cancelPendingRequest(request.requestId)
                    if(PickerCallbackBinding.matchesStoredExport(request,typed) && !clearStored(request.requestId)) model.notice.value=unconfirmedText
                    exportAdmission=false
                }
            }
        } else if(request.kind==PendingKind.CAPTURE_ARCHIVE && accepted!=null && result.resultCode==Activity.RESULT_OK && uri!=null)
            model.completeCaptureBundleExport(request.requestId,uri)
        else model.cancelPendingRequest(request.requestId)
    }
    LaunchedEffect(notice) {notice?.let {
        val event=model.scannerState.value.message
        val text=if(event!=null && event.fallbackText==it) messages.resolveCode(event.code) else it
        snackbars.showSnackbar(text);model.notice.value=null
    }}
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
        if (route.destination == Destination.CAPTURE) pending?.let(::cancelRequest)
        val back = route.back()
        navigate(back)
        if (back.destination == Destination.LIBRARY && !busy) model.select(null)
    }
    fun beginImport() { currentRequest(PendingKind.IMPORT)?.let {request->setPending(request);launchPicker(request) {importPicker.launch(imageTypes)}} }
    fun beginCamera() { currentRequest(PendingKind.CAMERA)?.let {setPending(it);navigate(AppRoute(Destination.CAPTURE,it.projectId))} }
    fun beginCalibration() { currentRequest(PendingKind.CALIBRATE)?.let {request->setPending(request);launchPicker(request) {calibrationPicker.launch(imageTypes)}} }
    fun launchExport(identity:ArtifactIdentity,option:ExportOption) {
        val admitted=currentRequest(PendingKind.EXPORT,option.id) ?: return
        if(admitted.projectId!=identity.projectId || admitted.revision!=identity.revision || admitted.artifactKey!=identity.artifactKey) {model.cancelPendingRequest(admitted.requestId);model.notice.value=staleText;return}
        val typed=ExportRequest(admitted.requestId,identity,option)
        setPending(admitted)
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                check(source.isCurrent(identity))
                source.validate(typed,model.repository.artifact(identity.projectId,identity.artifactKey,option.id))
                check(source.isCurrent(identity))
                exportStore.save(typed)
            }}.onSuccess {
                if(PendingRequest.restore(pendingValue)!=admitted) {
                    clearStored(typed.requestId);model.cancelPendingRequest(typed.requestId)
                } else if(!launchPicker(admitted) {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(option.format.mime).putExtra(Intent.EXTRA_TITLE,"realityforge-${option.representation.name.lowercase()}.${option.format.extension}"))}) {
                    if(!clearStored(typed.requestId)) model.notice.value=unconfirmedText
                }
            }.onFailure {
                model.cancelPendingRequest(typed.requestId);clearPending(admitted)
                clearStored(typed.requestId) // Only the exact privately staged intent may be removed.
                invalidExport=withContext(NonCancellable+Dispatchers.IO) {runCatching {exportStore.restore()}.isFailure}
                model.notice.value=staleText
            }
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
            val captureRequest=requireNotNull(pending)
            CameraScreen(onClose = {
                if(PendingRequest.restore(pendingValue)==captureRequest) {cancelRequest(captureRequest);navigate(route.back())}
                else model.cancelPendingRequest(captureRequest.requestId)
            }, onPhoto = { file ->
                val accepted=consume(PickerCallbackSlot(captureRequest))
                if(accepted!=null) {navigate(route.back());model.completeCapture(accepted.requestId,file)} else file.delete()
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
            bottomBar={if((pending!=null && pending.kind!=PendingKind.CAMERA) || PickerCallbackSlots.restore(callbackSlotsValue).awaitingCancelledResult) Surface(tonalElevation=3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if(pending!=null) stringResource(R.string.a071_pending_waiting) else drainingText)
                    if(pending!=null) TextButton(onClick={
                        val cancelled=PendingRequest.restore(pendingValue)
                        cancelled?.let(::cancelRequest)
                        if(cancelled?.kind==PendingKind.EXPORT) scope.launch {if(!clearStored(cancelled.requestId)) model.notice.value=unconfirmedText}
                    },modifier=Modifier.testTag("a071-cancel-pending")) {Text(stringResource(R.string.a071_cancel_pending))}
                }
            }},
        ) { padding ->
            val modifier=Modifier.fillMaxSize().padding(padding).testTag("a071-route-${route.destination.name.lowercase()}")
            val project=projects.find { it.id==route.projectId }
            when(route.destination) {
                Destination.LIBRARY -> {
                    val hostAction:(LibraryAction)->Unit={action->when(action) {
                    is LibraryAction.Create -> model.create(action.name)
                    is LibraryAction.Open -> if(!busy) {model.select(action.projectId);navigate(AppRoute(Destination.REVIEW,action.projectId))}
                    is LibraryAction.Delete -> if(!busy) { model.select(action.projectId); deleteValue="${action.projectId}\n${action.revision}" }
                    else -> Unit // Unsupported commands are not offered by this baseline adapter.
                }}
                    val service=features.library
                    if(service!=null) {
                        val presenter=remember(service,errorText) {LibraryPresenter(service) {errorText}}
                        LibraryServiceScreen(presenter,hostAction,modifier)
                    } else LibraryScreen(LibraryUiState(projects.map {p->LibraryProject.from(p).let {it.copy(message=messages.projectSummary(p.state,it.hasCurrentResult))}},busy=busy),hostAction,modifier)
                }
                Destination.ONBOARDING -> OnboardingScreen(onComplete=::leaveGuide,onSkip=::leaveGuide,modifier=modifier)
                Destination.SETTINGS -> PreferencesRoute(busy,modifier,onHelp={navigate(AppRoute(Destination.ONBOARDING,selected))},read=preferences,onSaved={preferences=it})
                else -> if(project == null || selected != project.id) { Box(modifier.padding(16.dp)) {Text(stringResource(R.string.a071_loading))} }
                else when(route.destination) {
                    Destination.REVIEW -> {
                        val currentResult=preview?.takeIf {project.resultRevision==project.revision && project.resultKey==it.optString("artifactKey")}
                        val evidence=currentResult?.optJSONObject("scanDiagnostics")
                        val lines=if(evidence==null) emptyList() else listOf(stringResource(R.string.a071_evidence_counts,
                            evidence.optInt("registeredCameraCount"),evidence.optInt("importedPhotoCount"),evidence.optInt("observedMeshTriangles")),stringResource(R.string.a071_evidence_limits))
                        val state=ProjectReviewUiState(project.id,project.revision,photos.map { ReviewPhoto.from(it,model.repository.photoFile(it)) },project.name,
                            busy=busy,calibrationStatus=calibration,diagnostics=lines,canCurate=false,canInspectResult=currentResult!=null)
                        val hostAction:(ReviewAction)->Unit={action->
                            if(ReviewAdmission.allowed(state,action) && model.selectedId.value==action.projectId && model.projects.value.find {it.id==action.projectId}?.revision==action.revision && action is ReviewAction.Command) when(action.command) {
                                ReviewCommand.IMPORT -> beginImport()
                                ReviewCommand.CAPTURE -> beginCamera()
                                ReviewCommand.CALIBRATE -> beginCalibration()
                                ReviewCommand.CLEAR_CALIBRATION -> model.clearLensCalibration()
                                ReviewCommand.RECONSTRUCT -> { lastFocal=requireNotNull(action.focal35mm);model.reconstruct(lastFocal);navigate(AppRoute(Destination.PROCESSING,project.id)) }
                                ReviewCommand.OPEN_RESULT -> navigate(AppRoute(Destination.STUDIO,project.id))
                                ReviewCommand.HELP -> navigate(AppRoute(Destination.ONBOARDING,selected))
                                else -> Unit
                            }
                        }
                        val curation=features.curation
                        if(curation!=null) {
                            val presenter=remember(curation,errorText,features.removePhotos,features.reconstructSelection) {
                                PhotoCurationPresenter(curation,{errorText},{errorText},features.removePhotos,features.reconstructSelection!=null)
                            }
                            CurationReviewScreen(presenter,state,hostAction,onReconstruct={command,lease->features.reconstructSelection?.invoke(command,lease)},modifier=modifier,fallbackFocal35mm=preferences.preferences.fallbackFocal35mm)
                        } else ProjectReviewScreen(state,hostAction,modifier,fallbackFocal35mm=preferences.preferences.fallbackFocal35mm)
                    }
                    Destination.PROCESSING -> {
                        val hasRecord=(busy && scanner.activeOperation?.kind==OperationKind.RECONSTRUCT) || project.state in setOf("PROCESSING","INTERRUPTED","FAILED","SPARSE_READY")
                        if(!hasRecord && features.jobStates==null) Column(modifier.padding(16.dp)) {
                            Text(stringResource(R.string.a071_no_active_job))
                            TextButton(onClick={navigate(AppRoute(Destination.REVIEW,project.id))}) {Text(stringResource(R.string.a071_back))}
                        } else {
                        val execution=scanner.activeOperation?.takeIf {it.kind==OperationKind.RECONSTRUCT && it.source?.projectId==project.id && it.source.revision==project.revision}
                        val phase=if(execution!=null && busy) JobPhase.RUNNING else when(project.state) {"INTERRUPTED"->JobPhase.INTERRUPTED;"FAILED"->JobPhase.FAILED;"SPARSE_READY"->JobPhase.COMPLETED;else->JobPhase.INTERRUPTED}
                        val state=JobUiState(JobIdentity(project.id,project.revision,execution?.jobId?.value ?: "project-status-${project.id}-${project.revision}"),phase,
                            stage=if(execution!=null) progress?.stage else null,completed=if(execution!=null) progress?.completed?.toLong() else null,total=if(execution!=null) progress?.total?.toLong() else null,
                            technicalDetails=project.message,hasPreviousResult=preview!=null,pauseSupported=true,resumeSupported=false)
                        val durableFlow=remember(features.jobStates,project.id,project.revision) {features.jobStates?.invoke(project.id,project.revision)}
                        val durable=durableFlow?.collectAsState(initial=null)?.value
                        val visible=durable?:state
                        ProcessingScreen(visible,onAction={action->
                            val currentProject=model.projects.value.find {it.id==action.identity.projectId}
                            if(action.identity!=visible.identity || !visible.can(action.command) ||
                                model.selectedId.value!=action.identity.projectId || currentProject?.revision!=action.identity.revision) false
                            else if(durable!=null) features.jobAction?.invoke(action) ?: false
                            else if(action.identity!=state.identity || !state.can(action.command)) false
                            else when(action.command) {
                                JobCommand.PAUSE -> {
                                    val current=model.scannerState.value.activeOperation
                                    val currentSource=current?.source
                                    if(model.busy.value && current?.kind==OperationKind.RECONSTRUCT &&
                                        current.jobId.value==action.identity.jobId && currentSource?.projectId==action.identity.projectId &&
                                        currentSource.revision==action.identity.revision) {model.pause();true} else false
                                }
                                JobCommand.RETRY -> if(!model.busy.value && currentProject.state in setOf("FAILED","INTERRUPTED")) {
                                    model.reconstruct(lastFocal)
                                    model.busy.value // C1 leaves this false when reservation rejects the dispatch.
                                } else false
                                JobCommand.OPEN_PREVIOUS -> {
                                    val currentPreview=model.preview.value
                                    if(currentProject.resultRevision==currentProject.revision && currentProject.resultKey!=null &&
                                        currentPreview?.optString("artifactKey")==currentProject.resultKey) {
                                        navigate(AppRoute(Destination.STUDIO,action.identity.projectId));true
                                    } else false
                                }
                                else -> false
                            }
                        },modifier=modifier)
                        }
                    }
                    Destination.STUDIO -> StudioRoute(preview?.takeIf {project.resultRevision==project.revision && project.resultKey==it.optString("artifactKey")},model,modifier,onExport={navigate(AppRoute(Destination.EXPORT,project.id))})
                    Destination.EXPORT -> ExportRoute(project.id,source,modifier,busy || exportAdmission || pending!=null,onExport=::launchExport,onArchive={
                        currentRequest(PendingKind.CAPTURE_ARCHIVE)?.let {request->setPending(request);launchPicker(request) {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"realityforge-capture.zip"))}}
                    })
                    else -> Unit
                }
            }
        }
        if(invalidExport) AlertDialog(onDismissRequest={},title={Text(stringResource(R.string.a071_pending_recovery_title))},
            text={Text(stringResource(R.string.a071_pending_recovery_body))},confirmButton={TextButton(onClick={scope.launch {
                runCatching {withContext(Dispatchers.IO) {exportStore.quarantineInvalid()}}.onSuccess {model.scannerState.value.pendingRequests.filter {it.kind==OperationKind.EXPORT_ARTIFACT}.forEach {request->
                    setSlots(PickerCallbackSlots.restore(callbackSlotsValue).cancel(request.requestId.value));model.cancelPendingRequest(request.requestId.value)
                };PendingRequest.restore(pendingValue)?.takeIf {it.kind==PendingKind.EXPORT}?.let(::cancelRequest);invalidExport=false}.onFailure {model.notice.value=staleText}
            }}) {Text(stringResource(R.string.a071_pending_recover))}},dismissButton={TextButton(onClick={invalidExport=false}) {Text(stringResource(R.string.a071_cancel))}})
        deleteValue?.split('\n')?.takeIf { it.size==2 }?.let { request ->
            AlertDialog(onDismissRequest={deleteValue=null},title={Text(stringResource(R.string.a071_delete_title))},
                text={Text(stringResource(R.string.a071_delete_body))},confirmButton={TextButton(enabled=!busy,onClick={
                    val original=projects.find { it.id==request[0] };deleteValue=null
                    if(original!=null && original.revision==request[1].toLongOrNull() && selected==original.id) model.deleteSelected() else model.notice.value=staleText
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
