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
    private val recovery = viewModelScope.async(Dispatchers.IO) { repository.dao.recoverInterrupted() }

    fun create(name: String) { if (busy.value) return; viewModelScope.launch {
        recovery.await()
        runCatching { repository.create(name) }.onSuccess { select(it) }.onFailure { notice.value = it.message }
    } }
    fun select(id: String?) {
        selectedId.value = id; preview.value = null
        calibrationStatus.value = if (id == null) "" else "Reading lens profile..."
        if (id != null) viewModelScope.launch(Dispatchers.IO) {
            val calibration = runCatching { LensCalibration.read(repository, id) }
            if (selectedId.value == id) calibrationStatus.value = calibration.fold(
                { p -> if (p == null) "No calibration: estimated pinhole" else
                    "Calibrated: ${p.acceptedViews} board views · RMS ${"%.2f".format(p.reprojectionRmsPixels)} px" },
                { "Calibration unreadable: ${it.message}; recalibrate this project" }
            )
            val project = repository.dao.project(id)
            val revision = project?.resultRevision
            if (project != null && revision != null && revision == project.revision) {
                val json = runCatching { JSONObject(repository.artifact(id, requireNotNull(project.resultKey), "json").readText()) }.getOrNull()
                if (selectedId.value == id) preview.value = json
            }
        }
    }
    fun import(uris: List<Uri>) {
        val id = selectedId.value ?: return
        if (busy.value || uris.isEmpty()) return
        busy.value = true
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
        val id = selectedId.value ?: return
        if (busy.value) { file.delete(); return }
        busy.value = true
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
        if (busy.value || uris.isEmpty()) return
        busy.value = true
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
                notice.value = "Calibration cancelled; prior lens profile remains unchanged."
            } catch (e: Exception) { notice.value = "Calibration rejected: ${e.message}" }
            finally { busy.value = false; progress.value = null }
        }
    }
    fun clearLensCalibration() {
        val id = selectedId.value ?: return
        if (busy.value) return
        busy.value = true
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
        if (busy.value) return
        busy.value = true
        task = viewModelScope.launch {
            var sourceRevision: Long? = null
            try {
                recovery.await()
                val project = requireNotNull(repository.dao.project(id)) { "Project no longer exists." }
                sourceRevision = project.revision
                preview.value = null
                repository.dao.state(id, project.revision, "PROCESSING", "Building sparse multi-camera geometry with local lens model.")
                val output = SparsePipeline(repository).run(id, focal35) { progress.value = it }
                ensureActive()
                check(repository.dao.complete(id, project.revision, output.getString("artifactKey"), "${output.getInt("pointCount")} sparse points; ${output.optInt("registeredCameras", 2)} cameras; not a surface mesh.") == 1) { "Result became stale." }
                if (selectedId.value == id) preview.value = output
                notice.value = "Sparse preview saved. Dense reconstruction and textures remain future milestones."
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
        if (busy.value) return
        val id = selectedId.value ?: return
        viewModelScope.launch {
            runCatching { repository.delete(id) }.onSuccess { select(null) }.onFailure { notice.value = it.message }
        }
    }
    fun export(uri: Uri, extension: String) {
        val id = selectedId.value ?: return
        if (busy.value || extension !in setOf("glb", "ply")) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = requireNotNull(repository.dao.project(id))
                val revision = requireNotNull(project.resultRevision) { "No current preview to export." }
                check(revision == project.revision)
                val file = repository.artifact(id, requireNotNull(project.resultKey), extension)
                file.inputStream().use { input -> requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { input.copyTo(it) } }
                notice.value = "Exported sparse $extension points. The chosen destination is controlled by your document provider."
            } catch (e: Exception) { notice.value = e.message }
        }
    }
}
