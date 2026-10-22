package com.realityforge.scanner.qa

import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.SessionReliabilityTest
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Synthetic execution checks are separate from physical capture/backup qualification. */
@RunWith(AndroidJUnit4::class)
class OfflinePrivacyJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun packagedAppHasNoNetworkPermissionAndOptionalCamera() {
        val context = instrumentation.targetContext
        for (permission in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertEquals(permission, PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
        val cameraFeatures = info.reqFeatures.orEmpty().filter { it.name?.startsWith("android.hardware.camera") == true }
        assertTrue(cameraFeatures.isNotEmpty())
        assertTrue(cameraFeatures.all { it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED == 0 })
    }

    @Test fun deletingAProjectPreservesExternalOriginalsExportsAndOtherProjects() = runBlocking {
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repository = ProjectRepository(context, database.dao())
        val project = repository.create("Synthetic privacy deletion")
        val other = repository.create("Synthetic surviving project")
        val originals = mutableListOf<File>()
        val exported = File.createTempFile("privacy-user-export-", ".zip", context.cacheDir)
        try {
            for (n in 1..3) {
                val photo = File.createTempFile("privacy-source-", ".jpg", context.cacheDir)
                originals.add(photo)
                instrumentation.context.assets.open("relief/view-0$n.jpg").use { input ->
                    photo.outputStream().use { input.copyTo(it) }
                }
                assertTrue(repository.importPhoto(project, Uri.fromFile(photo)))
            }
            assertTrue(repository.importPhoto(other, Uri.fromFile(originals[0])))
            val before = originals.associateWith { hash(it) }
            exported.outputStream().use { assertEquals(3, repository.exportCaptureBundle(project, it)) }
            val exportHash = hash(exported)
            val otherPhotos = repository.dao.photos(other).associate { it.id to hash(repository.photoFile(it)) }
            val directory = repository.directory(project)
            repository.delete(project)
            assertNull(repository.dao.project(project))
            assertFalse(directory.exists())
            for ((file, digest) in before) assertEquals(digest, hash(file))
            assertEquals(exportHash, hash(exported))
            for (photo in repository.dao.photos(other)) assertEquals(otherPhotos[photo.id], hash(repository.photoFile(photo)))
        } finally {
            originals.forEach { it.delete() }
            exported.delete()
            // The assertion path already deleted project. Repository deletion is deliberately
            // revision-bound, not an idempotent cleanup API; do not mask success with Missing.
            try {
                if (repository.dao.project(project) != null) repository.delete(project)
            } finally {
                try { repository.delete(other) } finally { database.close() }
            }
        }
    }

    @Test fun explicitOfflineSyntheticImportReconstructionInspectionReopenExportJourney() {
        // Default instrumentation does not prove an offline run. The external controller
        // supplies the marker after independently checking network settings.
        assumeTrue("NOT_RUN: invoke offline_privacy/journey.py with radios disabled",
            InstrumentationRegistry.getArguments().getString("offlinePrivacy") == "true")
        val resolver = instrumentation.targetContext.contentResolver
        assertEquals(1, Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, -1))
        assertEquals(0, Settings.Global.getInt(resolver, "wifi_on", -1))
        assertEquals(0, Settings.Global.getInt(resolver, "mobile_data", -1))
        SessionReliabilityTest().syntheticPhotographsCompleteTheActualImportReconstructReopenExportPath()
    }
}
