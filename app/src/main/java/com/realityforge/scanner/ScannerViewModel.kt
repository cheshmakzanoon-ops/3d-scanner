package com.realityforge.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.SparsePipeline
import com.realityforge.scanner.engine.LensCalibration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as RealityForgeApplication).repository
    val selectedId = MutableStateFlow<String?>(null)
    val projects = repository.dao.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photos = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.dao.observePhotos(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notice = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow<SparsePipeline.Progress?>(null)
    val preview = MutableStateFlow<JSONObject?>(null)
    val calibrationStatus = MutableStateFlow("No calibration: estimated pinhole")
    private var task: Job? = null
    private var previewTask: Job? = null
    private var selectionGeneration = 0L

    /** Called from UI actions before any suspension, so exports/mutations cannot overlap. */
    private fun reserveOperation(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        previewTask?.cancel()
        selectionGeneration++
        return true
    }
    private val recovery = viewModelScope.async(Dispatchers.IO) { repository.dao.recoverInterrupted() }

    fun create(name: String) {
        if (!reserveOperation()) return
        task = viewModelScope.launch {
            var created: String? = null
            try { recovery.await(); created = repository.create(name) }
            catch (e: CancellationException) { notice.value = "Project creation interrupted." }
            catch (e: Exception) { notice.value = e.message }
            finally { busy.value = false }
            created?.let { select(it) }
        }
    }
    fun select(id: String?) {
        if (busy.value) return
        previewTask?.cancel()
        val generation = ++selectionGeneration
        selectedId.value = id; preview.value = null
        calibrationStatus.value = if (id == null) "" else "Reading lens profile..."
        if (id != null) previewTask = viewModelScope.launch {
            try {
                recovery.await()
                val loaded = withContext(Dispatchers.IO) {
                    val calibration = runCatching { LensCalibration.read(repository, id) }
                    val status = calibration.fold(
                        { p -> if (p == null) "No calibration: estimated pinhole" else
                            "Calibrated: ${p.acceptedViews} board views · RMS ${"%.2f".format(p.reprojectionRmsPixels)} px" },
                        { "Calibration unreadable: ${it.message}; recalibrate this project" }
                    )
                    status to repository.currentPreview(id)
                }
                ensureActive()
                // Main-thread publication cannot race another UI operation's reservation.
                if (selectedId.value == id && selectionGeneration == generation && !busy.value) {
                    calibrationStatus.value = loaded.first
                    preview.value = loaded.second
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (selectedId.value == id && selectionGeneration == generation) {
                    preview.value = null
                    notice.value = "Saved preview could not be opened: ${e.message}. Original photographs are unchanged."
                }
            }
        }
    }
    fun import(uris: List<Uri>) {
        val id = selectedId.value ?: return
        if (uris.isEmpty() || !reserveOperation()) return
        preview.value = null
        task = viewModelScope.launch {
            var imported = 0; var duplicates = 0; var rejected = 0
            var lastError = ""
            try {
                for ((index, uri) in uris.withIndex()) {
                    ensureActive()
                    progress.value = SparsePipeline.Progress("Importing photographs", index, uris.size)
                    try { if (repository.importPhoto(id, uri)) imported++ else duplicates++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { rejected++; lastError = e.message.orEmpty() }
                }
                preview.value = null
                notice.value = "$imported imported; $duplicates duplicates; $rejected rejected." + if (rejected > 0) " $lastError" else ""
            } catch (e: CancellationException) { notice.value = "Import interrupted. $imported completed photographs are safe." }
            finally { busy.value = false; progress.value = null }
        }
    }
    fun captureReady(file: File) {
        val id = selectedId.value
        if (id == null || !reserveOperation()) { file.delete(); return }
        preview.value = null
        task = viewModelScope.launch {
            try { repository.importPhoto(id, Uri.fromFile(file)); preview.value = null; notice.value = "Photograph saved locally." }
            catch (e: CancellationException) { notice.value = "Capture import interrupted." }
            catch (e: Exception) { notice.value = e.message }
            finally { file.delete(); busy.value = false }
        }
    }
    /** Calibrate the active project's lens from 10-40 on-device checkerboard images. */
    fun calibrateLens(uris: List<Uri>) {
        val id = selectedId.value ?: return
        if (uris.isEmpty() || !reserveOperation()) return
        task = viewModelScope.launch {
            try {
                recovery.await()
                val profile = withContext(Dispatchers.Default) {
                    LensCalibration.calculate(getApplication(), uris) { done, total ->
                        progress.value = SparsePipeline.Progress("Calibrating checkerboard lens", done, total)
                    }
                }
                ensureActive()
                // Invalidate previous sparse geometry BEFORE switching its optical model.
                withContext(NonCancellable) {
                    repository.dao.invalidate(id)
                    repository.writeAtomic(LensCalibration.file(repository, id), LensCalibration.json(profile))
                }
                if (selectedId.value == id) {
                    preview.value = null
                    calibrationStatus.value = "Calibrated: ${profile.acceptedViews} board views · RMS ${"%.2f".format(profile.reprojectionRmsPixels)} px"
                }
                notice.value = "Lens calibration saved locally; previous sparse results invalidated. Use the same lens/zoom/aspect for object photos."
            } catch (e: CancellationException) {
                notice.value = "Calibration interrupted. Reopen the project to check the saved lens profile."
            } catch (e: Exception) { notice.value = "Calibration rejected: ${e.message}" }
            finally { busy.value = false; progress.value = null }
        }
    }
    fun clearLensCalibration() {
        val id = selectedId.value ?: return
        if (!reserveOperation()) return
        task = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    repository.dao.invalidate(id)
                    val file = LensCalibration.file(repository, id)
                    check(!file.exists() || file.delete()) { "Cannot delete lens calibration" }
                }
                if (selectedId.value == id) { preview.value = null; calibrationStatus.value = "No calibration: estimated pinhole" }
                notice.value = "Lens calibration removed. New scans use estimated pinhole intrinsics."
            } catch (e: Exception) { notice.value = e.message }
            finally { busy.value = false }
        }
    }
    fun reconstruct(focal35: Double) {
        val id = selectedId.value ?: return
        if (!reserveOperation()) return
        task = viewModelScope.launch {
            var sourceRevision: Long? = null
            try {
                recovery.await()
                val project = requireNotNull(repository.dao.project(id)) { "Project no longer exists." }
                sourceRevision = project.revision
                preview.value = null
                repository.dao.state(id, project.revision, "PROCESSING", "Reconstructing sparse geometry and attempting CPU dense stereo preview.")
                val output = SparsePipeline(repository).run(id, focal35) { progress.value = it }
                ensureActive()
                check(repository.dao.complete(id, project.revision, output.getString("artifactKey"), "${output.getInt("pointCount")} sparse points; ${output.optJSONObject("densePreview")?.optInt("pointCount") ?: 0} dense points; ${output.optJSONObject("meshPreview")?.optInt("triangleCount") ?: 0} observed triangles.") == 1) { "Result became stale." }
                if (selectedId.value == id) preview.value = output
                notice.value = "Preview saved. M09 exports real photo-based UV textures when visibility is sufficient; geometry remains open and unscaled."
            } catch (e: CancellationException) {
                sourceRevision?.let { revision ->
                    withContext(NonCancellable) { repository.dao.state(id, revision, "INTERRUPTED", "Paused. Originals and feature caches are safe; rerun to continue from cached features.") }
                }
                notice.value = "Processing stopped at a safe boundary."
            } catch (e: Exception) {
                sourceRevision?.let { repository.dao.state(id, it, "FAILED", e.message.orEmpty().take(600)) }
                notice.value = e.message
            } catch (e: LinkageError) {
                sourceRevision?.let { repository.dao.state(id, it, "FAILED", "Native library load failed. This device/build needs verification.") }
                notice.value = "Native library load failed: ${e.message}"
            } finally { busy.value = false; progress.value = null }
        }
    }
    fun pause() { task?.cancel() }
    fun deleteSelected() {
        val id = selectedId.value ?: return
        if (!reserveOperation()) return
        task = viewModelScope.launch {
            var deleted = false
            try { repository.delete(id); deleted = true }
            catch (e: CancellationException) { notice.value = "Deletion interrupted; reopen the project library to check its state." }
            catch (e: Exception) { notice.value = e.message }
            finally { busy.value = false }
            if (deleted && selectedId.value == id) select(null)
        }
    }
    fun exportCaptureBundle(uri: Uri) {
        val id = selectedId.value ?: return
        if (!reserveOperation()) return
        task = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) {
                    "Cannot open destination."
                }
                val count = stream.use { repository.exportCaptureBundle(id, it) }
                notice.value = "Exported $count original photos and a local benchmark manifest. Keep the ZIP private."
            } catch (cancelled: CancellationException) {
                notice.value = "Capture export interrupted. Delete any incomplete destination ZIP."
            } catch (e: Exception) {
                notice.value = "Capture export failed: ${e.message}. Delete any incomplete destination ZIP."
            } finally { busy.value = false }
        }
    }
    fun export(uri: Uri, extension: String) {
        val id = selectedId.value ?: return
        if (extension !in setOf("glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png") || !reserveOperation()) return
        task = viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = requireNotNull(repository.dao.project(id))
                val revision = requireNotNull(project.resultRevision) { "No current preview to export." }
                check(revision == project.revision)
                val file = repository.artifact(id, requireNotNull(project.resultKey), extension)
                file.inputStream().use { input ->
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { destination ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                }
                val latest = repository.dao.project(id)
                check(latest != null && latest.revision == revision && latest.resultKey == project.resultKey) { "Project changed during export; discard the destination file." }
                notice.value = when(extension) {
                    "textured.glb" -> "Exported photo-textured GLB with embedded PNG and indexed UVs. Open surfaces, arbitrary scale."
                    "texture.png" -> "Exported actual rectified photographic atlas PNG."
                    "mesh.glb", "mesh.ply" -> "Exported verified open surface mesh ($extension), vertex colors and arbitrary scale."
                    else -> "Exported $extension point cloud. Your document provider controls the destination."
                }
            } catch (e: CancellationException) { notice.value = "Model export interrupted. Delete any incomplete destination file." }
            catch (e: Exception) { notice.value = "Model export failed: ${e.message}. Delete any incomplete destination file." }
            finally { busy.value = false }
        }
    }
}
