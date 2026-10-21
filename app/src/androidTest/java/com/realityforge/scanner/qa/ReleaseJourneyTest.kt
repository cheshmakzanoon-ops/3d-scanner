package com.realityforge.scanner.qa

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.ui.PhotoGlView
import com.realityforge.scanner.ui.PhotographicModelViewer
import com.realityforge.scanner.viewer.PhotographicGlb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Actual app path; default assets are SYNTHETIC and cannot qualify physical quality. */
@RunWith(AndroidJUnit4::class)
class ReleaseJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun importCalibrateReconstructInspectExportReopenPreservesOriginals() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        val args = InstrumentationRegistry.getArguments()
        val expectedApk = args.getString("rf099_apk_sha256")
        if (expectedApk != null) {
            assertTrue(expectedApk.matches(Regex("[0-9a-f]{64}")))
            assertEquals("Installed APK must be the frozen candidate", expectedApk, hash(File(app.applicationInfo.sourceDir)))
        }
        val physicalDir = args.getString("rf099_physical_dir")
        if (physicalDir != null) assertTrue(physicalDir.matches(Regex("/data/local/tmp/rf099-[0-9a-f]{16}")))
        fun input(name: String): InputStream {
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            return if (physicalDir == null) instrumentation.context.assets.open("relief/$name")
            else android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $physicalDir/$name"))
        }
        val physicalManifest = if (physicalDir != null) input("manifest.json").bufferedReader().use { JSONObject(it.readText().also { text -> require(text.length < 200_000) }) } else null
        if (physicalManifest != null) {
            assertEquals("physical", physicalManifest.getString("kind"))
            assertFalse("Physical input cannot qualify on emulator", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        }
        val repository = app.repository
        val id = repository.create("099 isolated qualification ${UUID.randomUUID()}")
        val temporary = mutableListOf<File>()
        try {
            val photoCount = physicalManifest?.getJSONArray("photos")?.length() ?: 3
            require(photoCount in 3..12) { "Current candidate admits only3–12; larger supported profiles require owner integration" }
            for (index in 0 until photoCount) {
                val item = physicalManifest?.getJSONArray("photos")?.getJSONObject(index)
                val name = item?.getString("filename") ?: "view-0${index+1}.jpg"
                val file = File.createTempFile("rf099-input-", ".jpg", app.cacheDir).also { temporary.add(it) }
                input(name).use { source -> file.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = source.read(buffer); if (n < 0) break; total += n; require(total <= 42L*1024*1024); target.write(buffer, 0, n) }
                } }
                if (item != null) assertEquals(item.getString("sha256"), hash(file))
                assertTrue(repository.importPhoto(id, Uri.fromFile(file)))
                assertFalse("Identical input must not mutate project", repository.importPhoto(id, Uri.fromFile(file)))
            }
            if (physicalManifest != null) {
                val calibration = input("calibration.json").bufferedReader().use { it.readText().also { text -> require(text.length < 10_000) } }
                repository.writeAtomic(LensCalibration.file(repository, id), calibration)
                assertNotNull(LensCalibration.read(repository, id))
            } else {
                val meta = input("calibration.json").bufferedReader().use { JSONObject(it.readText()) }
                assertTrue(meta.getBoolean("synthetic"))
                val k = meta.getJSONArray("cameras").getJSONObject(0).getJSONArray("K")
                val profile = LensProfile(meta.getInt("width"), meta.getInt("height"), k.getJSONArray(0).getDouble(0), k.getJSONArray(1).getDouble(1), k.getJSONArray(0).getDouble(2), k.getJSONArray(1).getDouble(2), 0.0,0.0,0.0,0.0,0.0,0.0,10,null)
                repository.writeAtomic(LensCalibration.file(repository, id), LensCalibration.json(profile))
            }
            val originals = repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ScannerViewModel
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[ScannerViewModel::class.java]; model.select(id); model.reconstruct(32.88) }
                val deadline = SystemClock.elapsedRealtime() + 180_000
                while (model.busy.value && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertFalse("Candidate exceeded reconstruction timeout", model.busy.value)
                val result = requireNotNull(model.preview.value) { "Candidate reconstruction failed: ${model.notice.value}" }
                val key = result.getString("artifactKey")
                val artifact = repository.artifact(id, key, "textured.glb")
                val loaded = PhotographicGlb.read(artifact)
                assertTrue(loaded.indices.isNotEmpty())
                assertEquals(key, requireNotNull(ProjectRepository(app, repository.dao).currentPreview(id)).getString("artifactKey"))
                scenario.onActivity { activity -> activity.setContent { PhotographicModelViewer(artifact) {} } }
                awaitFrame()
                val destination = File.createTempFile("rf099-export-", ".glb", app.cacheDir).also { temporary.add(it) }
                scenario.onActivity { model.export(Uri.fromFile(destination), "textured.glb") }
                val exportDeadline = SystemClock.elapsedRealtime() + 30_000
                while (model.busy.value && SystemClock.elapsedRealtime() < exportDeadline) Thread.sleep(50)
                assertFalse(model.busy.value)
                assertEquals(hash(artifact), hash(destination))
                for (photo in repository.dao.photos(id)) assertEquals(originals[photo.id], hash(repository.photoFile(photo)))
                repository.dao.invalidate(id)
                assertNull("Input revision invalidates completed preview", repository.currentPreview(id))
                val receipt = JSONObject().put("schema",1).put("kind",if (physicalManifest == null) "synthetic" else "physical")
                    .put("source_sha",args.getString("rf099_source_sha", "unbound"))
                    .put("artifact_sha256",hash(File(app.applicationInfo.sourceDir)))
                    .put("photo_count",photoCount).put("triangle_count",loaded.indices.size/3)
                    .put("originals_preserved",true).put("export_sha256",hash(destination))
                    .put("does_not_cover","camera capture, editing, upgrade, process death, offline radios, quality, resources, accessibility, signing or external beta")
                instrumentation.sendStatus(0, Bundle().apply { putString("rf099_evidence", receipt.toString()) })
            }
        } finally { temporary.forEach { it.delete() }; repository.delete(id) }
    }

    private fun awaitFrame() {
        fun find(view: View): PhotoGlView? {
            if (view is PhotoGlView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var found: PhotoGlView? = null
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) } }
            found?.let { assertNull(it.renderFailure); if (it.renderedFrames > 0) return }
            Thread.sleep(50)
        }
        throw AssertionError("Candidate produced no actual photographic GLES frame")
    }
}
