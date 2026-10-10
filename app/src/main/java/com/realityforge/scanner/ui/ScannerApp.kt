package com.realityforge.scanner.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val palette = darkColorScheme(
    primary = Color(0xFF73D8C7), onPrimary = Color(0xFF002F29),
    secondary = Color(0xFFC6BEA6), background = Color(0xFF101A23),
    surface = Color(0xFF17252F), surfaceVariant = Color(0xFF233641),
    onSurface = Color(0xFFE6ECEF), onBackground = Color(0xFFE6ECEF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(model: ScannerViewModel) {
    val projects by model.projects.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val calibrationStatus by model.calibrationStatus.collectAsStateWithLifecycle()
    var camera by rememberSaveable { mutableStateOf(false) }
    var newProject by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var focal by rememberSaveable { mutableStateOf("26") }
    val snackbars = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }
    val checkerboardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.calibrateLens(it) }
    val exportGlb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/gltf-binary")) { uri -> uri?.let { model.export(it, "glb") } }
    val exportPly = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { model.export(it, "ply") } }
    val exportCapture = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.exportCaptureBundle(it) } }
    LaunchedEffect(notice) { notice?.let { snackbars.showSnackbar(it); model.notice.value = null } }
    BackHandler(enabled = selected != null || camera) { if (camera) camera = false else if (!busy) model.select(null) }
    MaterialTheme(colorScheme = palette) {
        if (camera) {
            CameraScreen(onClose = { camera = false }, onPhoto = { file -> camera = false; model.captureReady(file) })
        } else Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("RealityForge", fontWeight = FontWeight.Bold); Text("LOCAL PHOTOGRAMMETRY / M06", style = MaterialTheme.typography.labelSmall) } },
                    navigationIcon = { if (selected != null) TextButton(enabled = !busy, onClick = { model.select(null) }) { Text("Library") } },
                )
            },
            snackbarHost = { SnackbarHost(snackbars) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (selected == null) {
                    item {
                        Text("Keep the world.\nKeep it on your phone.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text("Photographs → verified camera geometry → sparse 3D preview.", color = MaterialTheme.colorScheme.secondary)
                    }
                    item {
                        InfoCard("Development build", "This milestone tests offline sparse multi-camera geometry. High-detail surfaces and photographic texturing are not available yet.")
                    }
                    item { Button(onClick = { newProject = true }, modifier = Modifier.fillMaxWidth()) { Text("Create local project") } }
                    if (projects.isEmpty()) item { Text("No scans yet. Start with a matte, textured, rigid object and two overlapping views.") }
                    items(projects, key = { it.id }) { project ->
                        Card(onClick = { model.select(project.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleLarge)
                                Text("${project.state.replace('_', ' ')} · revision ${project.revision}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text(project.message, style = MaterialTheme.typography.bodyMedium)
                                Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(project.createdAt)), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item { InfoCard("No account. No app networking.", "Original photographs, feature caches and results are saved in app-private storage. Uninstalling removes them; export important files first.") }
                } else {
                    val project = projects.find { it.id == selected }
                    item {
                        Text(project?.name ?: "Scan", style = MaterialTheme.typography.headlineMedium)
                        Text("${photos.size} photographs · originals preserved", color = MaterialTheme.colorScheme.secondary)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(enabled = !busy, onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")) }, modifier = Modifier.weight(1f)) { Text("Import photos") }
                            OutlinedButton(enabled = !busy, onClick = { camera = true }, modifier = Modifier.weight(1f)) { Text("Use camera") }
                        }
                    }
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Offline lens calibration", style = MaterialTheme.typography.titleMedium)
                                Text(calibrationStatus, style = MaterialTheme.typography.bodyMedium)
                                Text("Print the 10×7-square board (9×6 INNER corners). Photograph it 10–40 times using the SAME camera, zoom, aspect and orientation as your object photos; vary angle, distance and board position.")
                                Button(enabled = !busy, onClick = {
                                    checkerboardPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate this lens") }
                                if (calibrationStatus.startsWith("Calibrated:")) {
                                    OutlinedButton(enabled = !busy, onClick = { model.clearLensCalibration() }) { Text("Remove calibration") }
                                }
                                Text("Calibration improves geometry but does not provide absolute physical scale. Mismatched crops/lenses are rejected.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        InfoCard("Capture for geometry", "Keep the object still. Move sideways between overlapping views; do not just rotate the phone. Keep the same lens and avoid glossy, transparent or flat subjects. M01 has no object masking, so background points can appear.")
                    }
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Sparse reconstruction", style = MaterialTheme.typography.titleMedium)
                                Text("Examines up to 12 photographs, registers additional camera poses, and triangulates cross-view tracks—not a finished mesh.")
                                OutlinedTextField(value = focal, onValueChange = { focal = it.take(6) }, enabled = !busy,
                                    label = { Text("Fallback 35 mm-equivalent focal length") }, suffix = { Text("mm") },
                                    supportingText = { Text("Used only when EXIF lacks a value. 26 mm is an explicit assumption, not measured calibration.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(enabled = !busy && photos.size >= 2 && (focal.toDoubleOrNull()?.let { it in 10.0..200.0 } == true),
                                    onClick = { model.reconstruct(focal.toDouble()) }, modifier = Modifier.fillMaxWidth()) { Text("Build sparse 3D preview") }
                                if (busy) {
                                    val p = progress
                                    Text(p?.let { "${it.stage}: ${it.completed}/${it.total}" } ?: "Preparing…")
                                    if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.completed.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = { model.pause() }) { Text("Stop at safe boundary") }
                                    Text("Keep the app open. Leaving it stops this bounded preview; feature caches remain available.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    val result = preview
                    if (result != null) {
                        item {
                            Card {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("${result.optInt("pointCount")} reconstructed points", style = MaterialTheme.typography.titleLarge)
                                    Text("${result.optInt("registeredCameras", 2)} registered cameras · arbitrary scale · drag to orbit, pinch to zoom", style = MaterialTheme.typography.labelMedium)
                                    PointCloudViewer(result, Modifier.fillMaxWidth().height(310.dp))
                                    Text("Median parallax: ${"%.2f".format(result.optDouble("medianParallaxDegrees"))}°", color = MaterialTheme.colorScheme.primary)
                                    Text("M06 jointly refines registered camera poses and sparse points using a robust Schur solver with fixed anchor cameras. Geometry remains sparse—no triangles or textures.", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(enabled = !busy, onClick = { exportGlb.launch("realityforge-sparse.glb") }) { Text("Export GLB") }
                                        OutlinedButton(enabled = !busy, onClick = { exportPly.launch("realityforge-sparse.ply") }) { Text("Export PLY") }
                                    }
                                    Text("GLB contains POINTS, not triangles. PLY offers broad point-cloud inspection support.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(enabled = !busy && photos.size >= 3,
                            onClick = { exportCapture.launch("realityforge-capture.zip") },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Export original photos + calibration for benchmark")
                        }
                        Text("Creates a local ZIP at your chosen destination containing your original photos; treat it as private. The export does not upload anything from RealityForge.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    item { Text("Photographs", style = MaterialTheme.typography.titleLarge) }
                    items(photos, key = { it.id }) { photo -> PhotoRow(photo, model) }
                    item { InfoCard("Next engineering gate", "Collect calibrated real-object captures and compare to independent SfM, then implement CPU dense multi-view stereo and confidence-aware fusion.") }
                    item { TextButton(enabled = !busy, onClick = { deleteDialog = true }) { Text("Delete this local project", color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
        if (newProject) AlertDialog(
            onDismissRequest = { newProject = false }, title = { Text("New scan") },
            text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { model.create(name); name = ""; newProject = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newProject = false }) { Text("Cancel") } },
        )
        if (deleteDialog) AlertDialog(
            onDismissRequest = { deleteDialog = false }, title = { Text("Delete project and local originals?") },
            text = { Text("This removes the app's copies of your photos, caches and results. Photos outside this app are not deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { deleteDialog = false; model.deleteSelected() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
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
private fun PhotoRow(photo: ScanPhoto, model: ScannerViewModel) {
    val bitmap by produceState<Bitmap?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { runCatching { ImageLoader.upright(model.repository.photoFile(photo), 192) }.getOrNull() }
    }
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
}
