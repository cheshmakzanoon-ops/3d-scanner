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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realityforge.scanner.viewer.PhotographicAsset
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.viewer.PhotographicRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opens only a locally selected, current project artifact; never resolves an external URI. */
@Composable
fun PhotographicModelViewer(file: File, onClose: () -> Unit) {
    var asset by remember(file.path) { mutableStateOf<PhotographicAsset?>(null) }
    var failure by remember(file.path) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val supportsGl = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }
    LaunchedEffect(file.path) {
        try {
            check(supportsGl) { "OpenGL ES 3 is unavailable. The vertex-color preview and GLB export remain available." }
            asset = withContext(Dispatchers.IO) { PhotographicGlb.read(file) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure = e.message ?: "Cannot open this photographic model." }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Photographic model", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text("Unlit embedded photograph · drag to orbit · pinch to zoom. Missing surfaces remain open.", style = MaterialTheme.typography.bodySmall)
                val loaded = asset
                val message = failure
                if (message != null) {
                    Text("Preview unavailable: $message", color = MaterialTheme.colorScheme.error)
                    Text("Original photographs and exported geometry have not been changed.")
                } else if (loaded == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Validating local geometry and embedded PNG…")
                } else {
                    PhotoGlSurface(loaded, Modifier.weight(1f).fillMaxWidth()) { failure = it }
                    Text("${loaded.indices.size / 3} textured triangles · ${loaded.imageWidth} × ${loaded.imageHeight} atlas · arbitrary scale", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PhotoGlSurface(asset: PhotographicAsset, modifier: Modifier, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onFailure)
    val surface = remember(asset, context) { PhotoGlView(context, asset) { callback(it) } }
    DisposableEffect(surface, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surface.onPause()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // Non-preserved EGL context owns/releases all GPU resources on pause/detach.
            surface.dispose()
        }
    }
    Column(modifier) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxWidth().weight(1f))
        TextButton(onClick = { surface.resetView() }) { Text("Reset view") }
    }
}

internal class PhotoGlView(context: Context, asset: PhotographicAsset, fail: (String) -> Unit) : GLSurfaceView(context) {
    private var disposed = false
    internal val renderedFrames: Int get() = painter.framesDrawn
    internal val renderFailure: String? get() = painter.failure
    private val painter = PhotographicRenderer(asset) { message -> post { if (!disposed) fail(message) } }
    private var lastX = 0f
    private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            queueEvent { painter.magnify(factor) }; requestRender(); return true
        }
    })
    init {
        contentDescription = "Photographic 3D model. Drag to rotate and pinch to zoom."
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = false
        setRenderer(painter)
        renderMode = RENDERMODE_WHEN_DIRTY
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
                    queueEvent { painter.orbit(dx, dy) }; requestRender()
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
