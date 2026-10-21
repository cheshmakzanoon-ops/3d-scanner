package com.realityforge.scanner

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.BoundExternalRequestCodec
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.domain.RequestId
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.LensProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic metadata fixtures exercise presentation only; no reconstruction or quality claim. */
@RunWith(AndroidJUnit4::class)
class PresentationRecoveryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RealityForgeApplication

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(20)
    }

    private suspend fun fixture(test: suspend (String, Long, String, File) -> Unit) {
        val repository = app.repository
        app.compositionRoot.execution.awaitRecovery()
        val id = repository.create("Synthetic presentation ${UUID.randomUUID()}")
        val photo = File.createTempFile("presentation-", ".jpg", app.cacheDir)
        var primary: Throwable? = null
        try {
            instrumentation.context.assets.open("relief/view-01.jpg").use { input ->
                photo.outputStream().use { input.copyTo(it) }
            }
            assertTrue(repository.importPhoto(id, Uri.fromFile(photo)))
            val revision = requireNotNull(repository.dao.project(id)).revision
            val key = "sparse-m10-r$revision-0123456789abcdef"
            val json = JSONObject().put("schema", 1).put("revision", revision).put("artifactKey", key)
                .put("representation", "sparse_points").put("scale", "arbitrary")
                .put("pointCount", 1).put("registeredCameras", 2)
                .put("points", JSONArray("[[0,0,1,128,128,128]]"))
            repository.writeAtomic(repository.artifact(id, key, "json"), json.toString())
            assertEquals(1, repository.dao.complete(id, revision, key, "Synthetic presentation fixture"))
            assertNotNull(repository.currentPreview(id))
            test(id, revision, key, photo)
        } catch (failure: Throwable) { primary = failure; throw failure
        } finally {
            withContext(NonCancellable) {
                cleanup(primary, { repository.delete(id) }, { check(!photo.exists() || photo.delete()) })
            }
        }
    }

    private suspend fun cleanup(primary: Throwable?, vararg actions: suspend () -> Unit) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try { action() } catch (failure: Throwable) {
                if (primary != null) { if (failure !== primary) primary.addSuppressed(failure) }
                else if (cleanupFailure == null) cleanupFailure = failure
                else if (failure !== cleanupFailure) cleanupFailure!!.addSuppressed(failure)
            }
        }
        if (primary == null) cleanupFailure?.let { throw it }
    }

    private suspend fun model(handle: SavedStateHandle, test: suspend (ScannerViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: ScannerViewModel
        instrumentation.runOnMainSync {
            model = ScannerViewModel(app, handle)
            store.put("presentation-test", model)
        }
        val scopeJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
        var primary: Throwable? = null
        try { test(model) } catch (failure: Throwable) { primary = failure; throw failure
        } finally {
            withContext(NonCancellable) {
                cleanup(primary, { instrumentation.runOnMainSync { store.clear() } },
                    { withTimeout(15_000) { scopeJob.join() } })
            }
        }
    }

    @Test fun duplicateAndRejectedOnlyImportsRestoreUnchangedPreview() = runBlocking {
        fixture { id, revision, key, photo ->
            model(SavedStateHandle(mapOf("scanner.selected_project" to id))) { model ->
                await { model.preview.value?.optString("artifactKey") == key }
                val bad = File.createTempFile("invalid-presentation-", ".jpg", app.cacheDir)
                try {
                    bad.writeText("This is not a photograph")
                    for (input in listOf(photo, bad)) {
                        instrumentation.runOnMainSync {
                            val request = requireNotNull(model.beginImportRequest())
                            model.completeImport(request, listOf(Uri.fromFile(input)))
                        }
                        await { !model.busy.value && model.preview.value?.optString("artifactKey") == key }
                        assertEquals(revision, requireNotNull(app.repository.dao.project(id)).revision)
                        assertNotNull(app.repository.currentPreview(id))
                        instrumentation.runOnMainSync {
                            val export = requireNotNull(model.beginArtifactExportRequest("json"))
                            model.cancelPendingRequest(export)
                        }
                    }
                } finally { bad.delete() }
            }
        }
    }

    @Test fun restoredCalibrationRefreshPreservesPendingPickerAndItsCallback() = runBlocking {
        fixture { id, revision, key, photo ->
            val profile = LensProfile(1280, 720, 1000.0, 1000.0, 640.0, 360.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.1, 10, null)
            app.repository.writeAtomic(LensCalibration.file(app.repository, id), LensCalibration.json(profile))
            assertNotNull(LensCalibration.read(app.repository, id))
            val requestId = UUID.randomUUID().toString()
            val request = BoundExternalRequest.ImportPhotos(RequestId(requestId), ProjectRevision(id, revision),
                17, System.currentTimeMillis())
            val encoded = arrayListOf(BoundExternalRequestCodec.encode(request))
            val handle = SavedStateHandle(mapOf("scanner.selected_project" to id,
                "scanner.selection_generation" to 17L, "scanner.pending_requests.v1" to encoded))
            model(handle) { model ->
                await { model.calibrationStatus.value.startsWith("Calibrated:") &&
                    model.preview.value?.optString("artifactKey") == key }
                assertEquals(17L, requireNotNull(handle.get<Long>("scanner.selection_generation")))
                assertEquals(encoded, handle.get<ArrayList<String>>("scanner.pending_requests.v1"))
                instrumentation.runOnMainSync { model.completeImport(requestId, listOf(Uri.fromFile(photo))) }
                await { !model.busy.value && model.preview.value?.optString("artifactKey") == key &&
                    model.notice.value?.contains("1 duplicates") == true }
                assertTrue(handle.get<ArrayList<String>>("scanner.pending_requests.v1").orEmpty().isEmpty())
                assertEquals(revision, requireNotNull(app.repository.dao.project(id)).revision)
            }
        }
    }

    @Test fun importedNewPhotoNeverRestoresPreviousRevisionPreview() = runBlocking {
        fixture { id, revision, key, _ ->
            model(SavedStateHandle(mapOf("scanner.selected_project" to id))) { model ->
                await { model.preview.value?.optString("artifactKey") == key }
                val second = File.createTempFile("new-presentation-", ".jpg", app.cacheDir)
                try {
                    instrumentation.context.assets.open("relief/view-02.jpg").use { input ->
                        second.outputStream().use { input.copyTo(it) }
                    }
                    instrumentation.runOnMainSync {
                        model.completeImport(requireNotNull(model.beginImportRequest()), listOf(Uri.fromFile(second)))
                    }
                    await { !model.busy.value && model.selectedProject.value?.revision == revision + 1 &&
                        model.calibrationStatus.value == "No calibration: estimated pinhole" }
                    assertNull(model.preview.value)
                    assertNull(app.repository.currentPreview(id))
                } finally { second.delete() }
            }
        }
    }
    @Test fun deletedSelectedProjectClearsBothPreviewAndCalibration() = runBlocking {
        fixture { id, _, key, _ ->
            val profile = LensProfile(1280, 720, 1000.0, 1000.0, 640.0, 360.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.1, 10, null)
            app.repository.writeAtomic(LensCalibration.file(app.repository, id), LensCalibration.json(profile))
            val handle = SavedStateHandle(mapOf("scanner.selected_project" to id, "scanner.selection_generation" to 23L))
            model(handle) { model ->
                await { model.preview.value?.optString("artifactKey") == key &&
                    model.calibrationStatus.value.startsWith("Calibrated:") }
                app.repository.delete(id)
                await { model.selectedProject.value == null && model.calibrationStatus.value == "Selected project is unavailable." }
                assertNull(model.preview.value)
                await { model.scannerState.value.preview == com.realityforge.scanner.domain.PreviewState.None &&
                    model.scannerState.value.calibration == com.realityforge.scanner.domain.CalibrationState.None }
                assertEquals(23L, requireNotNull(handle.get<Long>("scanner.selection_generation")))
            }
        }
    }

    @Test fun missingRestoredProjectDoesNotRemainLoadingOrCalibrated() = runBlocking {
        app.compositionRoot.execution.awaitRecovery()
        val handle = SavedStateHandle(mapOf("scanner.selected_project" to UUID.randomUUID().toString()))
        model(handle) { model ->
            await { model.calibrationStatus.value == "Selected project is unavailable." }
            assertNull(model.preview.value)
            await { model.scannerState.value.preview == com.realityforge.scanner.domain.PreviewState.None &&
                model.scannerState.value.calibration == com.realityforge.scanner.domain.CalibrationState.None }
        }
    }

}
