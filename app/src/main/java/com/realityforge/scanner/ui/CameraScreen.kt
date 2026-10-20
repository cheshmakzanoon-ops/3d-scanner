package com.realityforge.scanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface as AndroidSurface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realityforge.scanner.R
import com.realityforge.scanner.capture.session.BoundCaptureFeatures
import com.realityforge.scanner.capture.analysis.CaptureQualityObservation
import com.realityforge.scanner.capture.analysis.CaptureQualityActionCode
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.analysis.StaticCaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.controls.CaptureOpticsPanel
import com.realityforge.scanner.capture.controls.CaptureOpticsContinuity
import com.realityforge.scanner.capture.guidance.CaptureGuidancePanel
import com.realityforge.scanner.capture.guidance.GuidanceObservation
import com.realityforge.scanner.capture.guidance.SavedPhotoGuidanceSession
import com.realityforge.scanner.capture.permissions.CameraPermissionObservation
import com.realityforge.scanner.capture.permissions.CameraPermissionPolicy
import com.realityforge.scanner.capture.permissions.CameraPermissionStatus
import com.realityforge.scanner.capture.session.CaptureAdapter
import com.realityforge.scanner.capture.session.CaptureCloseReason
import com.realityforge.scanner.capture.session.CaptureCommitOutcome
import com.realityforge.scanner.capture.session.CaptureMetadataSnapshot
import com.realityforge.scanner.capture.session.CaptureSessionCheckpoint
import com.realityforge.scanner.capture.session.CaptureSessionController
import com.realityforge.scanner.capture.session.CaptureSessionExtension
import com.realityforge.scanner.capture.session.CaptureSessionIdentity
import com.realityforge.scanner.capture.session.CaptureSessionPhase
import com.realityforge.scanner.capture.session.CaptureSessionState
import com.realityforge.scanner.capture.session.CaptureStagingStore
import com.realityforge.scanner.capture.session.CapturedPhoto
import com.realityforge.scanner.capture.session.SavedImageDecision
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continuous camera route bound to an immutable project, revision, and session token.
 * [onPhoto] must return only after accepting/rejecting that exact photo transaction.
 */
@Composable
fun CameraScreen(
    session: CaptureSessionIdentity,
    onClose: (CaptureCloseReason) -> Unit,
    onPhoto: suspend (CapturedPhoto) -> CaptureCommitOutcome,
    extensions: List<CaptureSessionExtension> = emptyList(),
    analysisBudgetProvider: CaptureAnalysisBudgetProvider = StaticCaptureAnalysisBudgetProvider(),
    sessionContent: @Composable ColumnScope.(CaptureSessionState) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val stagingStore = remember(context.cacheDir) {
        CaptureStagingStore(File(context.cacheDir, CAPTURE_STAGING_DIRECTORY))
    }
    var restoredExpectedRevision by rememberSaveable(session.sessionToken) {
        mutableStateOf(session.initialRevision)
    }
    var restoredSavedCount by rememberSaveable(session.sessionToken) { mutableStateOf(0) }
    val controller = remember(session.projectId, session.sessionToken) {
        CaptureSessionController(
            identity = session,
            stagingStore = stagingStore,
            restoredCheckpoint = CaptureSessionCheckpoint(
                expectedRevision = maxOf(session.initialRevision, restoredExpectedRevision),
                savedCount = restoredSavedCount,
            ),
        )
    }
    val state by controller.state.collectAsState()
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnClose by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    val opticsContinuity = remember(session.projectId, session.sessionToken) { CaptureOpticsContinuity() }
    val retainedThresholds = remember(session.projectId, session.sessionToken) {
        com.realityforge.scanner.capture.analysis.RetainedCaptureQualityThresholds(
            com.realityforge.scanner.capture.analysis.CaptureAnalysisSessionId(session.sessionToken), analysisBudgetProvider)
    }
    val savedGuidance = remember(session.projectId, session.sessionToken) {
        SavedPhotoGuidanceSession(session, analysisBudgetProvider, onAcceptedMeasurement = { photo, features ->
            val metadata = photo.metadata.captureMetadata
            val metrics = features.savedQuality
            if (metadata != null && metrics != null && metadata.logicalCameraId.value != null &&
                metadata.captureDeviceId.value != null && metadata.issues.isEmpty()) {
                retainedThresholds.recordAccepted(
                    com.realityforge.scanner.capture.analysis.CaptureAnalysisSessionId(photo.sessionToken),
                    features.originalSha256,
                    com.realityforge.scanner.capture.metadata.CaptureMetadataCodec.opticalIdentitySha256(metadata),
                    maxOf(features.width, features.height), metrics)
            }
        })
    }
    var guidanceObservation by remember(session.projectId, session.sessionToken) { mutableStateOf<GuidanceObservation?>(null) }
    DisposableEffect(savedGuidance, retainedThresholds) {
        onDispose { savedGuidance.close(); retainedThresholds.close() }
    }
    var captureDeviceNamespace by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        captureDeviceNamespace = withContext(Dispatchers.IO) {
            val preferences = context.getSharedPreferences("capture-device-identity-v1", Context.MODE_PRIVATE)
            synchronized(CAPTURE_DEVICE_NAMESPACE_LOCK) {
                runCatching {
                    preferences.getString("namespace", null) ?: UUID.randomUUID().toString().takeIf { value ->
                        preferences.edit().putString("namespace", value).commit()
                    }
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(state.expectedRevision, state.savedCount) {
        restoredExpectedRevision = state.expectedRevision
        restoredSavedCount = state.savedCount
    }
    LaunchedEffect(session.sessionToken) {
        withContext(Dispatchers.IO) {
            stagingStore.discardSessionsExcept(session.sessionToken)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close(CaptureCloseReason.DISPOSED) }
    }

    var hasRequestedPermission by rememberSaveable(session.sessionToken) { mutableStateOf(false) }
    var permissionObservation by remember(session.sessionToken) {
        mutableStateOf(observeCameraPermission(context, activity, hasRequestedPermission))
    }
    val permissionStatus = CameraPermissionPolicy.evaluate(permissionObservation)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasRequestedPermission = true
        permissionObservation = observeCameraPermission(context, activity, hasRequestedPermission)
    }

    var captureAdapter by remember { mutableStateOf<CameraXCaptureAdapter?>(null) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }
    var cameraBound by remember { mutableStateOf(false) }
    var cameraFeatures by remember { mutableStateOf<BoundCaptureFeatures?>(null) }
    var qualityObservation by remember { mutableStateOf<CaptureQualityObservation?>(null) }
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val currentCameraBound by rememberUpdatedState(cameraBound)
    val currentPermissionStatus by rememberUpdatedState(permissionStatus)

    DisposableEffect(lifecycleOwner, controller, activity, hasRequestedPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    if (
                        currentPermissionStatus == CameraPermissionStatus.GRANTED &&
                        currentCameraBound
                    ) {
                        controller.markReady()
                    }
                }

                Lifecycle.Event.ON_RESUME -> {
                    permissionObservation = observeCameraPermission(
                        context,
                        activity,
                        hasRequestedPermission,
                    )
                }

                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    qualityObservation = null
                    controller.markPaused()
                }
                Lifecycle.Event.ON_DESTROY -> controller.close(CaptureCloseReason.DISPOSED)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionStatus) {
        when (permissionStatus) {
            CameraPermissionStatus.GRANTED -> controller.markBinding()
            CameraPermissionStatus.CAMERA_UNAVAILABLE -> controller.markUnavailable(
                context.getString(R.string.a014_camera_unavailable),
            )
            else -> controller.markWaitingForPermission()
        }
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var targetRotation by rememberSaveable(session.sessionToken) {
        mutableStateOf(activity?.windowManager?.defaultDisplay?.rotation ?: AndroidSurface.ROTATION_0)
    }
    LaunchedEffect(
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        activity,
    ) {
        targetRotation = activity?.windowManager?.defaultDisplay?.rotation ?: targetRotation
    }
    DisposableEffect(context, permissionStatus, session.sessionToken) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val observedRotation = when (orientation) {
                    in 45 until 135 -> AndroidSurface.ROTATION_270
                    in 135 until 225 -> AndroidSurface.ROTATION_180
                    in 225 until 315 -> AndroidSurface.ROTATION_90
                    else -> AndroidSurface.ROTATION_0
                }
                if (targetRotation != observedRotation) targetRotation = observedRotation
            }
        }
        if (
            permissionStatus == CameraPermissionStatus.GRANTED &&
            orientationListener.canDetectOrientation()
        ) {
            orientationListener.enable()
        }
        onDispose { orientationListener.disable() }
    }
    SideEffect {
        previewUseCase?.targetRotation = targetRotation
        captureAdapter?.targetRotation = targetRotation
        cameraFeatures?.analysis?.targetRotation = targetRotation
    }

    val extensionUseCases = remember(extensions) {
        extensions.flatMap { it.useCases }.also { useCases ->
            check(useCases.distinct().size == useCases.size) {
                "A capture extension supplied the same CameraX use case more than once"
            }
        }
    }

    if (permissionStatus == CameraPermissionStatus.GRANTED && lifecycleStarted) {
        DisposableEffect(
            lifecycleOwner,
            previewView,
            session.sessionToken,
            extensionUseCases,
        ) {
            var disposed = false
            var provider: ProcessCameraProvider? = null
            var boundUseCases: List<UseCase> = emptyList()
            var features: BoundCaptureFeatures? = null
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (disposed) return@addListener
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            controller.markUnavailable(
                                context.getString(R.string.a014_camera_unavailable),
                            )
                            return@addListener
                        }
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                            )
                            .build()
                        val cameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                            .filter(cameraProvider.availableCameraInfos).first()
                        var ownedFeatures = runCatching {
                            BoundCaptureFeatures(session, cameraInfo, previewView, targetRotation,
                                mainExecutor, analysisBudgetProvider,
                                onQuality = { qualityObservation = it },
                                onFailure = { controller.reportNonFatal(it) },
                                continuity = opticsContinuity, thresholdProvider = retainedThresholds)
                        }.getOrElse { failure ->
                            controller.reportNonFatal(failure.message ?: context.getString(R.string.a014_analysis_unavailable))
                            null
                        }
                        features = ownedFeatures
                        val previewBuilder = Preview.Builder()
                            .setTargetRotation(targetRotation)
                            .setResolutionSelector(resolutionSelector)
                        val captureBuilder = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setJpegQuality(95)
                            .setTargetRotation(targetRotation)
                            .setResolutionSelector(resolutionSelector)
                        ownedFeatures?.configure(previewBuilder, captureBuilder)
                        val preview = previewBuilder.build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val imageCapture = captureBuilder.build()
                        val useCases = mutableListOf<UseCase>().apply {
                            add(preview)
                            add(imageCapture)
                            ownedFeatures?.let { add(it.analysis) }
                            addAll(extensionUseCases)
                        }
                        val camera = try {
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                *useCases.toTypedArray())
                        } catch (failure: Exception) {
                            // Some cameras cannot bind a concurrent analysis stream. Keep still
                            // capture available and report the unavailable live-analysis capability.
                            val optional = ownedFeatures ?: throw failure
                            cameraProvider.unbind(*useCases.toTypedArray())
                            useCases.remove(optional.analysis)
                            optional.close()
                            ownedFeatures = null
                            features = null
                            controller.reportNonFatal(context.getString(R.string.a014_analysis_unavailable))
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                *useCases.toTypedArray())
                        }
                        if (disposed) {
                            cameraProvider.unbind(*useCases.toTypedArray())
                            return@addListener
                        }
                        boundUseCases = useCases
                        previewUseCase = preview
                        captureAdapter = CameraXCaptureAdapter(
                            imageCapture = imageCapture,
                            executor = mainExecutor,
                            features = ownedFeatures,
                            namespace = { captureDeviceNamespace },
                        )
                        ownedFeatures?.bind(camera)
                        cameraFeatures = ownedFeatures
                        cameraBound = true
                        extensions.forEach { extension ->
                            runCatching { extension.onCameraBound(session, camera) }
                                .onFailure { failure ->
                                    controller.reportNonFatal(
                                        failure.message
                                            ?: context.getString(R.string.a014_camera_bind_failed),
                                    )
                                }
                        }
                        val zoom = camera.cameraControl.setZoomRatio(1f)
                        zoom.addListener(
                            {
                                runCatching { zoom.get() }.onFailure {
                                    controller.reportNonFatal(
                                        context.getString(R.string.a014_zoom_failed),
                                    )
                                }
                            },
                            mainExecutor,
                        )
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            controller.markReady()
                        } else {
                            controller.markPaused()
                        }
                    } catch (failure: Exception) {
                        features?.close()
                        if (boundUseCases.isNotEmpty()) provider?.unbind(*boundUseCases.toTypedArray())
                        cameraFeatures = null
                        cameraBound = false
                        captureAdapter = null
                        previewUseCase = null
                        controller.markUnavailable(
                            failure.message ?: context.getString(R.string.a014_camera_bind_failed),
                        )
                    }
                },
                mainExecutor,
            )
            onDispose {
                disposed = true
                features?.close()
                cameraFeatures = null
                qualityObservation = null
                cameraBound = false
                captureAdapter = null
                previewUseCase = null
                extensions.forEach { extension ->
                    runCatching { extension.onCameraUnbound(session.sessionToken) }
                }
                provider?.let { cameraProvider ->
                    if (boundUseCases.isNotEmpty()) {
                        runCatching { cameraProvider.unbind(*boundUseCases.toTypedArray()) }
                    }
                }
            }
        }
    }

    fun requestClose(reason: CaptureCloseReason) {
        controller.close(reason)
        currentOnClose(reason)
    }

    fun takePhoto() {
        val adapter = captureAdapter ?: return
        val attempt = controller.beginCapture() ?: return
        adapter.takePicture(
            attempt,
            object : CaptureAdapter.Callback {
                override fun onSaved(metadata: CaptureMetadataSnapshot) {
                    when (val decision = controller.onImageSaved(attempt, metadata)) {
                        is SavedImageDecision.Discarded -> Unit
                        is SavedImageDecision.Deliver -> {
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                val outcome = try {
                                    currentOnPhoto(decision.photo)
                                } catch (_: CancellationException) {
                                    CaptureCommitOutcome.Rejected(
                                        context.getString(R.string.a014_publication_cancelled),
                                    )
                                } catch (failure: Exception) {
                                    CaptureCommitOutcome.Rejected(
                                        failure.message
                                            ?: context.getString(R.string.a014_publication_failed),
                                    )
                                }
                                if (outcome is CaptureCommitOutcome.Accepted) {
                                    try {
                                        val observation = savedGuidance.acceptedPhoto(decision.photo, qualityObservation)
                                        if (controller.state.value.phase != CaptureSessionPhase.CLOSED) {
                                            guidanceObservation = observation
                                        }
                                    } catch (_: CancellationException) {
                                        // The original has already committed. Optional guidance cancellation
                                        // cannot turn that accepted save into a rejected photograph.
                                    } catch (_: LinkageError) {
                                        controller.reportNonFatal(context.getString(R.string.a017_guidance_unavailable))
                                    } catch (_: Exception) {
                                        controller.reportNonFatal(context.getString(R.string.a017_guidance_unavailable))
                                    }
                                }
                                withContext(NonCancellable) {
                                    controller.onCommit(decision.photo.captureToken, outcome)
                                }
                            }
                        }
                    }
                }

                override fun onError(message: String) {
                    controller.onCaptureError(
                        attempt,
                        message.ifBlank { context.getString(R.string.a014_capture_failed) },
                    )
                }
            },
        )
    }

    BackHandler { requestClose(CaptureCloseReason.USER_BACK) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.a014_capture_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.a014_capture_guidance))

            when (permissionStatus) {
                CameraPermissionStatus.GRANTED -> {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }

                CameraPermissionStatus.REQUESTABLE -> PermissionPanel(
                    message = stringResource(R.string.a014_import_remains_available),
                    action = stringResource(R.string.a014_permission_request),
                    onAction = {
                        hasRequestedPermission = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )

                CameraPermissionStatus.RATIONALE_REQUIRED -> PermissionPanel(
                    message = stringResource(R.string.a014_permission_rationale),
                    action = stringResource(R.string.a014_permission_retry),
                    onAction = {
                        hasRequestedPermission = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )

                CameraPermissionStatus.PERMANENTLY_DENIED -> PermissionPanel(
                    message = stringResource(R.string.a014_permission_permanent),
                    action = stringResource(R.string.a014_open_settings),
                    onAction = { context.openApplicationSettings() },
                )

                CameraPermissionStatus.CAMERA_UNAVAILABLE -> PermissionPanel(
                    message = stringResource(R.string.a014_camera_unavailable),
                )
            }

            val phaseMessage = when (state.phase) {
                CaptureSessionPhase.BINDING -> stringResource(R.string.a014_camera_binding)
                CaptureSessionPhase.PAUSED -> stringResource(R.string.a014_camera_paused)
                else -> null
            }
            phaseMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                stringResource(R.string.a014_saved_count, state.savedCount),
                style = MaterialTheme.typography.labelLarge,
            )
            qualityObservation?.takeIf { it.sessionId.value == session.sessionToken }?.let { observation ->
                Text(stringResource(when (observation.action) {
                    CaptureQualityActionCode.WAIT_FOR_EVIDENCE -> R.string.a014_quality_wait
                    CaptureQualityActionCode.READY_TO_CAPTURE -> R.string.a014_quality_ready
                    CaptureQualityActionCode.HOLD_STEADY -> R.string.a014_quality_steady
                    CaptureQualityActionCode.ADD_LIGHT -> R.string.a014_quality_light
                    CaptureQualityActionCode.REDUCE_LIGHT -> R.string.a014_quality_reduce_light
                    CaptureQualityActionCode.EVEN_OUT_LIGHTING -> R.string.a014_quality_even_light
                    CaptureQualityActionCode.REFOCUS_OR_REFRAME -> R.string.a014_quality_refocus
                }), style = MaterialTheme.typography.bodySmall)
            }
            guidanceObservation?.takeIf {
                it.session.projectId == session.projectId && it.session.sessionToken == session.sessionToken &&
                    it.session.revision == session.initialRevision
            }?.let { CaptureGuidancePanel(it) }
            cameraFeatures?.let { CaptureOpticsPanel(it, session.sessionToken) }
            sessionContent(state)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { requestClose(CaptureCloseReason.USER_BACK) }) {
                    Text(stringResource(R.string.a014_back))
                }
                Button(
                    enabled = captureAdapter != null && state.phase == CaptureSessionPhase.READY,
                    onClick = ::takePhoto,
                ) {
                    Text(
                        when (state.phase) {
                            CaptureSessionPhase.CAPTURING -> stringResource(R.string.a014_saving)
                            CaptureSessionPhase.COMMITTING -> stringResource(R.string.a014_committing)
                            else -> stringResource(R.string.a014_take_photo)
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.a014_capture_footer),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ColumnScope.PermissionPanel(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message)
        if (action != null && onAction != null) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private class CameraXCaptureAdapter(
    private val imageCapture: ImageCapture,
    private val executor: Executor,
    private val features: BoundCaptureFeatures?,
    private val namespace: () -> String?,
) : CaptureAdapter {
    var targetRotation: Int
        get() = imageCapture.targetRotation
        set(value) {
            imageCapture.targetRotation = value
        }

    override fun takePicture(attempt: com.realityforge.scanner.capture.session.CaptureAttempt, callback: CaptureAdapter.Callback) {
        val requestedRotation = imageCapture.targetRotation
        val afterFrame = features?.currentFrameNumber() ?: -1L
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(attempt.stagedFile).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val metadata = try {
                        features?.savedMetadata(requestedRotation, afterFrame, namespace()) ?: CaptureMetadataSnapshot(
                            targetRotation = requestedRotation,
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            fields = mapOf("captureSource" to "camerax"),
                        )
                    } catch (failure: Exception) {
                        callback.onError(failure.message ?: "Capture metadata could not be verified.")
                        return
                    }
                    callback.onSaved(metadata)
                }

                override fun onError(exception: ImageCaptureException) {
                    callback.onError(exception.message ?: "")
                }
            },
        )
    }
}

private fun observeCameraPermission(
    context: Context,
    activity: Activity?,
    hasRequestedBefore: Boolean,
): CameraPermissionObservation {
    val cameraAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    val shouldShowRationale = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } ?: false
    return CameraPermissionObservation(
        granted = granted,
        shouldShowRationale = shouldShowRationale,
        hasRequestedBefore = hasRequestedBefore,
        cameraAvailable = cameraAvailable,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private const val CAPTURE_STAGING_DIRECTORY = "capture-staging-v1"

private val CAPTURE_DEVICE_NAMESPACE_LOCK = Any()
