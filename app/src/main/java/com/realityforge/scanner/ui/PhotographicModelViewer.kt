package com.realityforge.scanner.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.realityforge.scanner.R
import com.realityforge.scanner.ui.studio.*
import com.realityforge.scanner.viewer.RenderControls
import com.realityforge.scanner.viewer.StudioRenderSurface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realityforge.scanner.viewer.PhotographicAsset
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.viewer.loading.LoadedPhotographicModel
import com.realityforge.scanner.viewer.loading.PhotographicModelLoader
import com.realityforge.scanner.viewer.PhotographicRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opens only a locally selected, current project artifact; never resolves an external URI. */
@Composable
fun PhotographicModelViewer(file: File, onClose: () -> Unit) =
    PhotographicModelViewer(file,StudioFeatureActions(),null,onClose)

@Composable
fun PhotographicModelViewer(
    file: File,
    features: StudioFeatureActions = StudioFeatureActions(),
    surfaceFactory: ((Context, LoadedPhotographicModel, (String) -> Unit) -> StudioRenderSurface)? = null,
    onClose: () -> Unit,
) {
    var model by remember(file.path) { mutableStateOf<LoadedPhotographicModel?>(null) }
    val asset=model?.asset
    var failure by remember(file.path) { mutableStateOf<String?>(null) }
    var fallbackUsed by remember(file.path) { mutableStateOf(false) }
    val context = LocalContext.current
    val glUnavailable=stringResource(R.string.a070_gl_unavailable)
    val openFailure=stringResource(R.string.a070_open_failure)
    var pose by rememberStudioPose(file.canonicalPath)
    val supportsGl = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }
    LaunchedEffect(file.path) {
        try {
            check(supportsGl) { glUnavailable }
            model = PhotographicModelLoader.load(file)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure = e.message ?: openFailure }
    }
    DisposableEffect(model) { val owned=model; onDispose { owned?.close() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.a070_photo), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.a070_close)) }
                }
                Text(stringResource(R.string.a070_instructions), style = MaterialTheme.typography.bodySmall)
                val loaded = asset
                val message = failure
                if (message != null) {
                    Text(stringResource(R.string.a070_preview_failure,message), color = MaterialTheme.colorScheme.error,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    Text(stringResource(R.string.a070_unchanged))
                } else if (loaded == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.a070_loading))
                } else {
                    PhotoGlSurface(requireNotNull(model),pose,{pose=it},surfaceFactory,{fallbackUsed=true},Modifier.weight(1f).fillMaxWidth()) { failure=it }
                    if (fallbackUsed) Text(stringResource(R.string.a070_using_compatible_renderer),modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    StudioControls(pose,{ pose=it })
                    StudioFeatureControls(features)
                    val scale = stringResource(if (model?.summary?.units=="meters") R.string.a070_metric_scale else R.string.a070_unknown_scale)
                    Text(stringResource(R.string.a070_model_info,loaded.indices.size/3,loaded.imageWidth,loaded.imageHeight,scale), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PhotoGlSurface(
    model: LoadedPhotographicModel,
    pose: StudioPose,
    onPoseChange: (StudioPose)->Unit,
    factory: ((Context,LoadedPhotographicModel,(String)->Unit)->StudioRenderSurface)?,
    onFallback: () -> Unit,
    modifier: Modifier,
    onFailure: (String) -> Unit,
) {
    val context=LocalContext.current
    val asset=model.asset
    val owner=LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onFailure)
    val fallbackCallback by rememberUpdatedState(onFallback)
    val poseCallback by rememberUpdatedState(onPoseChange)
    val currentPose by rememberUpdatedState(pose)
    val label=stringResource(R.string.a070_photo_accessibility)
    var useFallback by remember(asset,factory) { mutableStateOf(false) }
    val candidate=remember(asset,context,factory,useFallback) {
        if (factory!=null && !useFallback) {
            try {
                factory.invoke(context,model) { useFallback=true; fallbackCallback() } to false
            } catch (_: Exception) { PhotoGlView(context,asset) { callback(it) } to true }
            catch (_: LinkageError) { PhotoGlView(context,asset) { callback(it) } to true }
        } else PhotoGlView(context,asset) { callback(it) } to false
    }
    val surface=candidate.first
    LaunchedEffect(candidate) { if(candidate.second) fallbackCallback() }
    SideEffect { (surface as? PhotoGlView)?.onInteract={ h,v,f -> poseCallback(currentPose.orbit(h,v).magnify(f)) } }
    LaunchedEffect(surface,pose) {
        if (surface is PhotoGlView) surface.applyPose(pose)
        else {
            val controls=surface.controls
            controls.reset(); controls.orbit(pose.yaw,pose.pitch); controls.magnify(pose.zoom); controls.pan(pose.panX,pose.panY)
        }
    }
    DisposableEffect(surface,owner) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> surface.resumeRendering()
                Lifecycle.Event.ON_PAUSE -> surface.pauseRendering()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surface.pauseRendering()
        onDispose { owner.lifecycle.removeObserver(observer); surface.close() }
    }
    AndroidView(factory={ surface.view },modifier=modifier.studioSemantics(label,pose,onPoseChange))
}

internal class PhotoGlView(context: Context, asset: PhotographicAsset, fail: (String) -> Unit) : GLSurfaceView(context), StudioRenderSurface {
    override val view: android.view.View get()=this
    override val controls: RenderControls = object: RenderControls {
        override fun reset() { queueEvent { painter.reset() }; requestRender() }
        override fun orbit(horizontalDegrees: Float,verticalDegrees: Float) { queueEvent { painter.orbit(horizontalDegrees,verticalDegrees) }; requestRender() }
        override fun magnify(factor: Float) { queueEvent { painter.magnify(factor) }; requestRender() }
        override fun pan(horizontal: Float,vertical: Float) { queueEvent { painter.pan(horizontal,vertical) }; requestRender() }
    }
    override fun resumeRendering() { if (!disposed) onResume() }
    override fun pauseRendering() { if (!disposed) onPause() }
    override fun close()=dispose()
    private var disposed = false
    internal val renderedFrames: Int get() = painter.framesDrawn
    internal val renderFailure: String? get() = painter.failure
    private val painter = PhotographicRenderer(asset) { message -> post { if (!disposed) fail(message) } }
    internal var onInteract: ((Float,Float,Float)->Unit)? = null
    private var lastX = 0f
    private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            onInteract?.invoke(0f,0f,factor) ?: run { queueEvent { painter.magnify(factor) }; requestRender() }; return true
        }
    })
    init {
        contentDescription = context.getString(R.string.a070_photo_accessibility)
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = false
        setRenderer(painter)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    fun applyPose(pose: StudioPose) {
        queueEvent {
            painter.reset(); painter.orbit(pose.yaw,pose.pitch); painter.magnify(pose.zoom); painter.pan(pose.panX,pose.panY)
        }
        requestRender()
    }
    fun resetView() { queueEvent { painter.reset() }; requestRender() }
    fun dispose() {
        if (disposed) return
        disposed = true
        queueEvent {
            if (android.opengl.EGL14.eglGetCurrentContext() != android.opengl.EGL14.EGL_NO_CONTEXT) painter.releaseGlResources()
        }
        onPause()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed) return false
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scale.isInProgress) {
                    val dx = (event.x - lastX) * .35f; val dy = (event.y - lastY) * .35f
                    onInteract?.invoke(dx,dy,1f) ?: run { queueEvent { painter.orbit(dx,dy) }; requestRender() }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
