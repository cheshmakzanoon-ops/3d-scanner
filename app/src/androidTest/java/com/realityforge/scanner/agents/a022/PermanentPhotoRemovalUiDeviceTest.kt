package com.realityforge.scanner.agents.a022

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.application.PhotoRemovalRequest
import com.realityforge.scanner.application.ProjectCommandAdapter
import com.realityforge.scanner.application.ScannerCompositionRoot
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.deletion.DeletionIntent
import com.realityforge.scanner.data.deletion.DeletionPhase
import com.realityforge.scanner.data.deletion.DeletionScope
import com.realityforge.scanner.data.deletion.RoomDeletionPersistence
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.ui.PermanentPhotoRemovalButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Actual Compose -> ViewModel -> command adapter -> C6/Room deletion. No fake deletion backend. */
@RunWith(AndroidJUnit4::class)
class PermanentPhotoRemovalUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: RealityForgeApplication
    private lateinit var root: ScannerCompositionRoot
    private lateinit var model: ScannerViewModel
    private lateinit var temporary: File
    private lateinit var project: ScanProject
    private lateinit var target: ScanPhoto
    private lateinit var retained: ScanPhoto
    private lateinit var other: ScanPhoto
    private val projects = mutableListOf<String>()
    private val externalSources = linkedMapOf<File, String>()

    @Before fun prepareActualProjectAndComponent(): Unit = runBlocking {
        app = compose.activity.application as RealityForgeApplication
        root = app.compositionRoot
        root.execution.awaitRecovery()
        temporary = File(app.cacheDir, "a022-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val projectId = root.repository.create("Permanent removal UI fixture").also { projects += it }
        import(projectId, 17)
        import(projectId, 29)
        project = requireNotNull(root.repository.dao.project(projectId))
        val rows = root.repository.dao.photos(projectId)
        target = rows[0]
        retained = rows[1]
        val otherId = root.repository.create("Unrelated removal sentinel").also { projects += it }
        import(otherId, 43)
        other = root.repository.dao.photos(otherId).single()

        compose.runOnUiThread {
            // Reuse the ViewModel created by MainActivity; the real application composition stays installed.
            model = ViewModelProvider(compose.activity)[ScannerViewModel::class.java]
            model.select(project.id)
            val commands = ProjectCommandAdapter(root, compose.activity)
            // The activity already installs content in onCreate; replace it through the activity API.
            compose.activity.setContent {
                MaterialTheme {
                    val selected by model.selectedProject.collectAsStateWithLifecycle()
                    val photos by model.photos.collectAsStateWithLifecycle()
                    val busy by model.busy.collectAsStateWithLifecycle()
                    val displayed = photos.singleOrNull { it.id == target.id }
                    val current = selected
                    if (current?.id == project.id && displayed != null) {
                        PermanentPhotoRemovalButton(model, commands, displayed,
                            ProjectRevision(current.id, current.revision), enabled = !busy)
                    } else Text("Original no longer listed")
                }
            }
        }
        compose.waitUntil(10_000) {
            model.selectedProject.value?.id == project.id && model.photos.value.any { it.id == target.id } &&
                !model.busy.value
        }
        compose.onNodeWithText("Remove original").assertIsDisplayed().assertIsEnabled()
    }

    @After fun removeOnlyOwnedFixtures() = runBlocking {
        if (::model.isInitialized) {
            compose.waitUntil(10_000) { !model.busy.value }
            compose.runOnUiThread {
                model.select(null)
                compose.activity.setContent { MaterialTheme { Text("Removal fixture finished") } }
            }
        }
        try {
            if (::root.isInitialized) projects.asReversed().forEach { root.repository.delete(it) }
        } finally {
            if (::temporary.isInitialized) check(temporary.deleteRecursively())
        }
    }

    @Test fun cancelDoesNotChangeAnyOriginalRevisionOrDeletionRecord() = runBlocking {
        compose.onNodeWithText("Remove original").performClick()
        compose.onNodeWithText("Permanently remove this original?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Permanently remove this original?").assertDoesNotExist()
        assertEquals(project, root.repository.dao.project(project.id))
        assertEquals(listOf(target, retained), root.repository.dao.photos(project.id))
        assertEquals(target.sha256, hash(root.repository.photoFile(target)))
        assertNull(RoomDeletionPersistence(root.database.deletionRequestDao()).pendingProject(project.id))
        assertOtherOriginalsPreserved()
    }

    @Test fun confirmationDeletesExactlyTheDisplayedPhotoAndAdvancesTheRealRevisionOnce() = runBlocking {
        compose.onNodeWithText("Remove original").performClick()
        compose.onNodeWithText("Remove permanently").performClick()
        waitForCompletedRemoval()
        assertFalse(root.repository.photoFile(target).exists())
        assertCompletedRemoval()
    }

    @Test fun sameConfirmationRetriesAPersistedTombstoneAfterOriginalBytesWereRemoved() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A real conflicting C6 reader produces a real retryable command failure before any deletion.
        val reader = launch(Dispatchers.IO) {
            root.repository.withProjectAccess(ProjectOperationRequest.create(project.id,
                ProjectOperationKind.READ_INPUTS, project.revision)) {
                admitted.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(10_000) { admitted.await() }
            compose.onNodeWithText("Remove original").performClick()
            compose.runOnIdle { model.notice.value = null }
            compose.onNodeWithText("Remove permanently").performClick()
            compose.waitUntil(10_000) { !model.busy.value && model.notice.value != null }
            compose.onNodeWithText("Retry removal").assertIsDisplayed().assertIsEnabled()
            assertEquals(project.revision, root.repository.dao.project(project.id)?.revision)
            assertEquals(target.sha256, hash(root.repository.photoFile(target)))
        } finally {
            release.complete(Unit)
            reader.cancelAndJoin()
        }

        val confirmation = PhotoRemovalRequest.bind(ProjectRevision(project.id, project.revision), target)
        val operation = UUID.randomUUID().toString()
        val persistence = RoomDeletionPersistence(root.database.deletionRequestDao())
        // Controlled durable crash-boundary image, not a claim of an externally killed Android process.
        persistence.stageConfirmed(DeletionIntent(operation, DeletionScope.Photo(project.id, target.id),
            project.revision, 1, target.sha256, confirmation.pendingRemovalRequestId).confirm(1))
        assertTrue(root.repository.photoFile(target).delete())
        compose.waitUntil(10_000) { model.selectedProject.value?.state == "DELETING_PHOTO" }
        assertTrue(confirmation.matches(ProjectRevision(project.id, project.revision),
            root.repository.dao.photos(project.id).single { it.id == target.id }))
        compose.onNodeWithText("Retry removal").assertIsEnabled().performClick()
        waitForCompletedRemoval()
        assertEquals(DeletionPhase.COMPLETED, persistence.load(operation)?.phase)
        assertNull(persistence.pendingProject(project.id))
        assertCompletedRemoval()
    }

    private fun waitForCompletedRemoval() {
        compose.waitUntil(10_000) {
            !model.busy.value && model.selectedProject.value?.revision == project.revision + 1 &&
                model.photos.value.none { it.id == target.id }
        }
        compose.onNodeWithText("Original no longer listed").assertIsDisplayed()
    }

    private suspend fun assertCompletedRemoval() {
        val current = requireNotNull(root.repository.dao.project(project.id))
        assertEquals(project.revision + 1, current.revision)
        assertEquals(project.curationVersion + 1, current.curationVersion)
        assertEquals("READY", current.state)
        val remaining = root.repository.dao.photos(project.id)
        assertEquals(listOf(retained.id), remaining.map { it.id })
        assertEquals(0L, remaining.single().selectionOrder)
        assertOtherOriginalsPreserved()
    }

    private suspend fun assertOtherOriginalsPreserved() {
        assertEquals(retained.sha256, hash(root.repository.photoFile(retained)))
        assertEquals(listOf(other), root.repository.dao.photos(other.projectId))
        assertEquals(other.sha256, hash(root.repository.photoFile(other)))
        externalSources.forEach { (file, expected) -> assertEquals(expected, hash(file)) }
    }

    private suspend fun import(projectId: String, seed: Int) {
        val file = File(temporary, "synthetic-$seed.jpg")
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(seed)
            bitmap.setPixels(IntArray(160 * 120) { -0x1000000 or random.nextInt(0x1000000) },
                0, 160, 0, 0, 160, 120)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it)) }
        } finally { bitmap.recycle() }
        externalSources[file] = hash(file)
        assertTrue(root.repository.importPhoto(projectId, Uri.fromFile(file)))
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
