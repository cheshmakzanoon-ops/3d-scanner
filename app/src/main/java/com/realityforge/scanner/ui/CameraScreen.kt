package com.realityforge.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.UUID

@Composable
fun CameraScreen(onClose: () -> Unit, onPhoto: (File) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var taking by remember { mutableStateOf(false) }
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    if (permission) DisposableEffect(owner, view) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                val p = future.get(); provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95).build()
                val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera.cameraControl.setZoomRatio(1f)
                capture = imageCapture
            } catch (e: Exception) { error = e.message ?: "Camera unavailable. Photo import is still supported." }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; capture = null; provider?.unbindAll() }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Object capture", style = MaterialTheme.typography.headlineSmall)
            Text("Keep the object still. Move the camera between shots. Avoid changing lenses or zoom.")
            if (permission) AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
            else Column(Modifier.weight(1f)) {
                Text("Camera access is optional; you can also import existing photographs.")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !taking, onClick = onClose) { Text("Back") }
                Button(enabled = capture != null && !taking, onClick = {
                    val imageCapture = capture ?: return@Button
                    taking = true
                    val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
                    imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { taking = false; currentOnPhoto(file) }
                        override fun onError(exception: ImageCaptureException) { file.delete(); taking = false; error = exception.message }
                    })
                }) { Text(if (taking) "Saving…" else "Take photograph") }
            }
            Text("M01: JPEG capture. RAW, locked exposure and live coverage guidance remain planned.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
