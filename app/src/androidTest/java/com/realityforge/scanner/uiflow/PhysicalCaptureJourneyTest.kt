package com.realityforge.scanner.uiflow

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Requires an operator-positioned, authorized nonprivate physical scene; never seeds/imports a photo. */
@RunWith(AndroidJUnit4::class)
class PhysicalCaptureJourneyTest {
    @Test fun capturesWithRealCameraAndReopensTheSameOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("rfPhysicalCapture") == "true") { "Explicit physical capture invocation required" }
        require(args.getString("rfPhysicalSceneAuthorized") == "true") {
            "Operator must position and authorize a nonprivate physical scene before this capture"
        }
        val hardwareEvidence = requireNotNull(args.getString("rfHardwareEvidenceId"))
        require(hardwareEvidence.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))) {
            "A bounded identified hardware/operator protocol receipt is required"
        }
        val runId = requireNotNull(args.getString("rfEvidenceRunId"))
        require(runId.matches(Regex("[a-f0-9]{32}")))
        val identitySha = requireNotNull(args.getString("rfDeviceIdentitySha256"))
        require(identitySha.matches(Regex("[a-f0-9]{64}")))
        val expectedFingerprint = requireNotNull(args.getString("rfExpectedDeviceFingerprint"))
        require(expectedFingerprint.isNotBlank() && expectedFingerprint.length <= 512)
        assertEquals("Actual device must match the operator/runner identity", expectedFingerprint, Build.FINGERPRINT)
        fun property(name: String): String {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val bytes = ByteArray(1025)
                val count = input.read(bytes)
                require(count in 0..1024) { "Unexpected device property size" }
                if (count == 0) "" else String(bytes, 0, count, Charsets.UTF_8).trim()
            }
        }
        assertEquals(Build.FINGERPRINT, property("ro.build.fingerprint"))
        assertNotEquals("Emulator camera cannot qualify physical capture", "1", property("ro.kernel.qemu"))
        assertNotEquals("Emulator camera cannot qualify physical capture", "1", property("ro.boot.qemu"))
        val identity = listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT, Build.DEVICE, Build.HARDWARE)
            .joinToString(" ").lowercase(Locale.ROOT)
        require(listOf("generic", "emulator", "goldfish", "ranchu", "cuttlefish", "sdk_gphone", "sdk_phone", "vbox")
            .none { it in identity }) { "Recognized emulator identity cannot qualify physical camera capture" }

        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        assertTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val cameras = requireNotNull(app.getSystemService(CameraManager::class.java))
        assertTrue("A real back-facing CameraX input is required", cameras.cameraIdList.any {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        })
        assertEquals("Runner must revoke CAMERA outside instrumentation before this scenario",
            PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA))

        val name = "Physical UI ${UUID.randomUUID()}"
        var ownedProjectId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = UiJourneyDriver()
                ui.startAtLibrary()
                ui.click("Create local project")
                ui.setText(name)
                ui.click("Create", "Save")
                ui.awaitText(name)
                val before = runBlocking { app.repository.dao.observeProjects().first().single { it.name == name } }
                ownedProjectId = before.id
                assertEquals(before.id, UUID.fromString(before.id).toString())
                assertEquals(0L, before.revision)
                assertTrue("A new physical capture project has no seeded originals",
                    runBlocking { app.repository.dao.photos(before.id).isEmpty() })
                assertNull(before.resultKey)

                ui.scrollToClick("Use camera", "Capture")
                ui.awaitText("Object capture")
                ui.click("Grant camera permission", "Allow camera")
                ui.click("While using the app", "While using this app", "Only this time", "Allow")
                ui.await { ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
                val captureStartedAt = System.currentTimeMillis()
                ui.awaitEnabledClick("Take photograph", timeout = 30000)
                // This is the real CameraX onImageSaved -> application import callback, never a VM action.
                ui.await(90000) { runBlocking {
                    app.repository.dao.project(before.id)?.revision == before.revision + 1 &&
                        app.repository.dao.photos(before.id).size == 1
                } }
                ui.awaitText(name)
                val captured = runBlocking { app.repository.dao.photos(before.id).single() }
                assertEquals(before.id, captured.projectId)
                assertTrue(captured.addedAt >= captureStartedAt)
                val original = app.repository.photoFile(captured)
                assertTrue(original.isFile)
                assertTrue(captured.bytes in 1..150L * 1024 * 1024)
                assertEquals(captured.bytes, original.length())
                original.inputStream().use { input ->
                    assertEquals(0xff, input.read()); assertEquals(0xd8, input.read()); assertEquals(0xff, input.read())
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(original.path, bounds)
                assertEquals("image/jpeg", bounds.outMimeType)
                assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
                assertEquals(captured.width, bounds.outWidth)
                assertEquals(captured.height, bounds.outHeight)
                val capturedHash = sha256(original)
                assertEquals(captured.sha256, capturedHash)

                // Recreate and leave/reopen through visible navigation; only independent DB/file assertions follow.
                scenario.recreate()
                ui.awaitText(name)
                ui.back()
                ui.awaitText("Create local project")
                ui.click(name)
                ui.awaitText(name)
                scenario.recreate()
                ui.awaitText(name)
                val after = runBlocking { requireNotNull(app.repository.dao.project(before.id)) }
                val reopened = runBlocking { app.repository.dao.photos(before.id).single() }
                assertEquals(name, after.name)
                assertEquals(before.revision + 1, after.revision)
                assertNull(after.resultRevision)
                assertNull(after.resultKey)
                assertEquals(captured, reopened)
                val reopenedHash = sha256(app.repository.photoFile(reopened))
                assertEquals(capturedHash, reopenedHash)

                // The safe library contains no photograph or live preview. Never export the captured JPEG.
                ui.back()
                ui.awaitText("Create local project")
                ui.screenshot("physical-capture-reopened-library")
                val evidence = JSONObject().put("schema_version", 1).put("run_id", runId)
                    .put("hardware_evidence_id", hardwareEvidence).put("device_fingerprint", Build.FINGERPRINT)
                    .put("device_identity_sha256", identitySha).put("project_id", before.id)
                    .put("revision_before", before.revision).put("revision_after", after.revision)
                    .put("photo_id", captured.id).put("photo_sha256", capturedHash)
                    .put("reopened_photo_sha256", reopenedHash).put("bytes", captured.bytes)
                    .put("width", captured.width).put("height", captured.height).put("synthetic", false)
                    .toString()
                require(evidence.length <= 4096)
                instrumentation.addResults(Bundle().apply { putString("rfPhysicalCaptureEvidence", evidence) })
            }
        } finally {
            // Remove only this exact test-owned project after collecting metadata; retain no captured scene copy.
            ownedProjectId?.let { id -> runBlocking {
                val project = app.repository.dao.project(id)
                if (project != null && project.name == name) app.repository.delete(id)
            } }
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            require(count <= 150L * 1024 * 1024)
            digest.update(buffer, 0, size)
        }
        require(count > 0)
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}
