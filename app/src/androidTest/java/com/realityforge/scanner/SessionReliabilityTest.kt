package com.realityforge.scanner

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import com.realityforge.scanner.ui.PhotoGlView
import com.realityforge.scanner.ui.PhotographicModelViewer
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.qa.SharedFaultDocumentProvider as FaultDocumentProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Exercises the real Android app/controller. Relief pictures are SYNTHETIC, not physical capture evidence. */
@RunWith(AndroidJUnit4::class)
class SessionReliabilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun syntheticPhotographsCompleteTheActualImportReconstructReopenExportPath() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        val repository = app.repository
        val id = repository.create("Synthetic complete session ${UUID.randomUUID()}")
        val temporary = mutableListOf<File>()
        var primaryFailure: Throwable? = null
        try {
            for (n in 1..3) {
                val photo = File.createTempFile("relief-$n-", ".jpg", app.cacheDir).also { temporary.add(it) }
                instrumentation.context.assets.open("relief/view-0$n.jpg").use { input -> photo.outputStream().use { input.copyTo(it) } }
                assertTrue(repository.importPhoto(id, Uri.fromFile(photo)))
                assertFalse(repository.importPhoto(id, Uri.fromFile(photo)))
            }
            val meta = instrumentation.context.assets.open("relief/calibration.json").bufferedReader().use { JSONObject(it.readText()) }
            assertTrue("Fixture provenance must remain explicit", meta.getBoolean("synthetic"))
            val k = meta.getJSONArray("cameras").getJSONObject(0).getJSONArray("K")
            // Exact synthetic camera intrinsics, NOT a fabricated real checkerboard measurement.
            val profile = LensProfile(meta.getInt("width"),meta.getInt("height"),
                k.getJSONArray(0).getDouble(0),k.getJSONArray(1).getDouble(1),
                k.getJSONArray(0).getDouble(2),k.getJSONArray(1).getDouble(2),
                0.0,0.0,0.0,0.0,0.0,0.0,10,null)
            repository.writeAtomic(LensCalibration.file(repository,id),LensCalibration.json(profile))
            val original = repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ScannerViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[ScannerViewModel::class.java]
                    model.select(id)
                    model.reconstruct(32.88)
                }
                awaitIdle(model,180000)
                val result = requireNotNull(model.preview.value) { "Full pipeline failed: ${model.notice.value}" }
                assertTrue(result.toString().take(500), result.getInt("pointCount") >= 30)
                val texture = result.optJSONObject("texturePreview")
                assertNotNull("Texture generation failed: ${result.optString("textureFailure")} / ${result.optString("denseFailure")}", texture)
                val key = result.getString("artifactKey")
                val artifact = repository.artifact(id,key,"textured.glb")
                val modelData = PhotographicGlb.read(artifact)
                assertEquals(requireNotNull(texture).getInt("triangleCount")*3,modelData.indices.size)
                assertTrue(modelData.indices.size >= 120)
                // Independent repository reopening from completed database metadata, not live JSON.
                val reopened = requireNotNull(ProjectRepository(app,repository.dao).currentPreview(id))
                assertEquals(key,reopened.getString("artifactKey"))
                assertEquals(result.getInt("pointCount"),reopened.getInt("pointCount"))
                val destination = FaultDocumentProvider.createUri(app)
                try {
                    scenario.onActivity {
                        val requestId = requireNotNull(model.beginArtifactExportRequest("textured.glb"))
                        model.completeArtifactExport(requestId, destination)
                    }
                    awaitIdle(model,30000)
                    assertEquals("SAF export must preserve exact model bytes",hash(artifact),FaultDocumentProvider.hash(app,destination))
                } finally { FaultDocumentProvider.cleanup(app,destination) }
                for (photo in repository.dao.photos(id)) assertEquals(original[photo.id],hash(repository.photoFile(photo)))
                android.util.Log.i("RealityForgeSessionTest","Synthetic full session: ${result.getInt("pointCount")} sparse points, ${modelData.indices.size/3} photo-textured triangles; hashes preserved")
                // Busy operations must reject deletion and selection, not invalidate the live project.
                scenario.onActivity {
                    model.busy.value = true
                    model.deleteSelected(); model.select(null)
                    assertEquals(id,model.selectedId.value)
                    model.busy.value = false
                }
                assertNotNull(repository.dao.project(id))
                scenario.onActivity { activity -> activity.setContent { PhotographicModelViewer(artifact) {} } }
                awaitPhotographicFrame()
                // AGP removes the test app after instrumentation. Capture with the test-only
                // shell identity into /data/local/tmp, not app-private storage that disappears.
                val capture = instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/realityforge-m12-session.png")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(capture).use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) { /* Drain completion, no shell operators. */ }
                }
                val verify = instrumentation.uiAutomation.executeShellCommand(
                    "head -c 8 /data/local/tmp/realityforge-m12-session.png")
                val signature = android.os.ParcelFileDescriptor.AutoCloseInputStream(verify).use { it.readBytes() }
                assertArrayEquals("Emulator screenshot capture failed",
                    byteArrayOf(137.toByte(),80,78,71,13,10,26,10), signature)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupPreservingFailure(primaryFailure,
                {
                    val execution = app.compositionRoot.execution
                    val job = execution.current.value
                    // Activity closure does not stop service-owned reconstruction. Cancel only
                    // this fixture's job and join it before requesting normal leased deletion.
                    if (job?.input?.projectId == id) {
                        if (job.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.RUNNING ||
                            job.state == com.realityforge.scanner.data.jobs.ReconstructionJobState.QUEUED)
                            execution.cancel(job.jobId)
                        kotlinx.coroutines.withTimeout(30_000) { execution.awaitWorkerStopped() }
                    }
                },
                { temporary.forEach { it.delete() } },
                { repository.delete(id) },
            )
        }
    }

    @Test fun completedPreviewRejectsMismatchedFractionalOrStaleRevision() = runBlocking {
        withRepository { repository, id ->
            val key = "sparse-m10-r0-0123456789abcdef"
            val file = repository.artifact(id,key,"json")
            val valid = JSONObject().put("artifactKey",key).put("revision",0)
            repository.writeAtomic(file,valid.toString())
            assertEquals(1,repository.dao.complete(id,0,key,"Synthetic metadata contract"))
            assertNotNull(repository.currentPreview(id))
            for (invalid in listOf(
                JSONObject(valid.toString()).put("revision",0.5),
                JSONObject(valid.toString()).put("revision","0"),
                JSONObject(valid.toString()).put("artifactKey","sparse-m10-r0-ffffffffffffffff"))) {
                repository.writeAtomic(file,invalid.toString())
                expectRejected { repository.currentPreview(id) }
            }
            repository.writeAtomic(file,valid.toString())
            repository.dao.invalidate(id)
            assertNull(repository.currentPreview(id))
            assertEquals(0,repository.dao.complete(id,0,key,"Must not publish outdated result"))
        }
    }
    @Test fun oversizedDeeplyNestedAndTruncatedPreviewFailWithoutLosingOriginals() = runBlocking {
        withRepository { repository,id ->
            val key = "sparse-m10-r0-0123456789abcdef"
            val file = repository.artifact(id,key,"json")
            repository.dao.complete(id,0,key,"Synthetic metadata contract")
            RandomAccessFile(file,"rw").use { it.setLength(ProjectRepository.MAX_PREVIEW_BYTES+1) }
            expectRejected { repository.currentPreview(id) }
            repository.writeAtomic(file,"[".repeat(100)+"]".repeat(100))
            expectRejected { repository.currentPreview(id) }
            repository.writeAtomic(file,"{\"revision\":0")
            expectRejected { repository.currentPreview(id) }
            assertNotNull(repository.dao.project(id))
        }
    }
    @Test fun captureExportRejectsRevisionChangeBeforeWritingItsCompletionManifest() = runBlocking {
        withRepository { repository,id ->
            val temporary = mutableListOf<File>()
            try {
                for (n in 1..3) {
                    val photo = File.createTempFile("session-change-", ".jpg",instrumentation.targetContext.cacheDir).also { temporary.add(it) }
                    instrumentation.context.assets.open("relief/view-0$n.jpg").use { input -> photo.outputStream().use { input.copyTo(it) } }
                    assertTrue(repository.importPhoto(id,Uri.fromFile(photo)))
                }
                var changed = false
                val target = object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (!changed) { changed = true; runBlocking { repository.dao.invalidate(id) } }
                        super.write(b,off,len)
                    }
                }
                expectRejected { repository.exportCaptureBundle(id,target) }
                assertTrue(changed)
                for (photo in repository.dao.photos(id)) assertEquals(photo.sha256,hash(repository.photoFile(photo)))
            } finally { temporary.forEach { it.delete() } }
        }
    }
    private suspend fun withRepository(block: suspend (ProjectRepository,String) -> Unit) {
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build()
        val repository = ProjectRepository(context,db.dao())
        val id = repository.create("Synthetic reliability metadata")
        var primaryFailure: Throwable? = null
        try { block(repository,id) }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally { cleanupPreservingFailure(primaryFailure, { repository.delete(id) }, { db.close() }) }
    }
    /** Cleanup diagnostics must never replace the pipeline failure or bypass a live lease. */
    private suspend fun cleanupPreservingFailure(primary: Throwable?, vararg actions: suspend () -> Unit) {
        var failure = primary
        for (action in actions) try { action() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        if (primary == null) failure?.let { throw it }
    }

    @Test fun cleanupPreservesOriginalTimeoutAndStillRunsRemainingActions() = runBlocking {
        val timeout = AssertionError("Original pipeline timeout")
        val lease = IllegalStateException("Project lease remains active")
        var lastCleanupRan = false
        cleanupPreservingFailure(timeout, { throw lease }, { lastCleanupRan = true })
        assertSame(lease, timeout.suppressed.single())
        assertTrue(lastCleanupRan)
        try {
            cleanupPreservingFailure(null, { throw lease })
            fail("Cleanup-only failure was swallowed")
        } catch (actual: IllegalStateException) { assertSame(lease, actual) }
    }

    private suspend fun expectRejected(block: suspend () -> Any?) {
        try { block(); fail("Invalid or stale session was accepted") }
        catch (expected: Exception) { /* A controlled validation error, not an Error/OOM. */ }
    }
    private fun awaitPhotographicFrame() {
        fun find(view: View): PhotoGlView? {
            if (view is PhotoGlView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime()+30000
        while (SystemClock.elapsedRealtime()<deadline) {
            var gl: PhotoGlView? = null
            instrumentation.runOnMainSync { gl = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) } }
            gl?.let { assertNull(it.renderFailure); if (it.renderedFrames>0) return }
            Thread.sleep(50)
        }
        throw AssertionError("Actual reconstructed model did not produce a photographic GPU frame")
    }
    private fun awaitIdle(model: ScannerViewModel, maxMillis: Long) {
        val deadline = SystemClock.elapsedRealtime()+maxMillis
        while (model.busy.value && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        val execution = (instrumentation.targetContext.applicationContext as RealityForgeApplication).compositionRoot.execution
        val latest = execution.progress.value?.progress
        val feature = latest?.featureWork
        val pair = latest?.pairWork
        assertFalse("Operation exceeded test deadline: ${model.progress.value}; featureWork=$feature; pairWork=$pair",model.busy.value)
    }
}
