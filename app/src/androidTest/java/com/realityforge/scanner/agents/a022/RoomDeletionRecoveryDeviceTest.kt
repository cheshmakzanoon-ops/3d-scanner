package com.realityforge.scanner.agents.a022

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.*
import com.realityforge.scanner.data.deletion.*
import com.realityforge.scanner.data.operations.ProjectLeaseCoordinator
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Room + C6 + filesystem recovery, with controlled persisted boundary images rather than OS kill claims. */
@RunWith(AndroidJUnit4::class)
class RoomDeletionRecoveryDeviceTest {
    @Test fun photoRetryAfterFileRemovalReindexesCanonicalCurationAndPreservesCompletedArtifact() = runBlocking {
        withFixture { fixture ->
            val first = fixture.photos.first()
            val operation = UUID.randomUUID().toString()
            fixture.persistence.stageConfirmed(DeletionIntent(operation, DeletionScope.Photo(fixture.project, first.id), 3, 1,
                first.sha256, fixture.removalRequest).confirm(1))
            assertEquals("DELETING_PHOTO", fixture.database.dao().project(fixture.project)?.state)
            assertTrue(fixture.paths.photoFile(first).delete()) // persisted boundary: removed bytes, unfinalized Room row
            fixture.reopen()
            fixture.service.deletePhoto(fixture.project, first.id, 3, fixture.removalRequest, first.sha256)
            val project = requireNotNull(fixture.database.dao().project(fixture.project))
            assertEquals(4L, project.revision)
            assertEquals(2L, project.curationVersion)
            assertEquals("READY", project.state)
            assertEquals(3L, project.resultRevision)
            assertEquals(fixture.resultKey, project.resultKey)
            val remaining = fixture.database.dao().photos(fixture.project)
            assertEquals(fixture.photos.drop(1).map { it.id }, remaining.map { it.id })
            remaining.forEachIndexed { index, photo ->
                assertEquals(index.toLong(), photo.selectionOrder)
                val canonical = PhotoCurationRecordCodec.decode(requireNotNull(photo.curationRecord))
                assertEquals(photo.id, canonical.id)
                assertEquals(photo.sha256, canonical.sha256)
                assertEquals(index, canonical.position)
                assertEquals(photo.sha256, hash(fixture.paths.photoFile(photo)))
            }
            // Reads through the actual curation store must still succeed after canonical reindexing.
            assertEquals(2, requireNotNull(RoomPhotoCurationStore(fixture.database).read(fixture.project)).photos.size)
            assertEquals(DeletionPhase.COMPLETED, fixture.persistence.load(operation)?.phase)
            assertTrue(fixture.service.recoverPending().isEmpty())
            fixture.assertUnrelatedPreserved()
        }
    }

    @Test fun staleOriginalAndRemovalConfirmationCannotStageOrRemoveAnyBytes() = runBlocking {
        withFixture { fixture ->
            val first = fixture.photos.first()
            for ((request, sha) in listOf(fixture.removalRequest to "0".repeat(64), "wrong-removal-request" to first.sha256)) {
                try {
                    fixture.service.deletePhoto(fixture.project, first.id, 3, request, sha)
                    fail("Stale original/removal confirmation was accepted")
                } catch (_: IllegalArgumentException) { }
                assertNull(fixture.persistence.pendingProject(fixture.project))
                assertEquals(3L, fixture.database.dao().project(fixture.project)?.revision)
                assertEquals(first.sha256, hash(fixture.paths.photoFile(first)))
                assertEquals(3, fixture.database.dao().photos(fixture.project).size)
            }
            fixture.assertUnrelatedPreserved()
        }
    }

    @Test fun projectTombstoneSurvivesDatabaseReopenAndRemainsAfterProjectRowDeletion() = runBlocking {
        withFixture { fixture ->
            val operation = UUID.randomUUID().toString()
            fixture.persistence.stageConfirmed(DeletionIntent(operation, DeletionScope.Project(fixture.project), 3, 1).confirm(1))
            fixture.reopen()
            try {
                fixture.service.deleteConfirmed(fixture.project, 2)
                fail("A different confirmation revision resumed the project tombstone")
            } catch (_: IllegalArgumentException) { }
            assertEquals(DeletionPhase.TOMBSTONED, fixture.persistence.load(operation)?.phase)
            fixture.photos.forEach { assertEquals(it.sha256, hash(fixture.paths.photoFile(it))) }
            fixture.service.deleteConfirmed(fixture.project, 3)
            assertNull(fixture.database.dao().project(fixture.project))
            assertFalse(fixture.paths.projectDirectory(fixture.project, create = false).exists())
            assertEquals(DeletionPhase.COMPLETED, fixture.persistence.load(operation)?.phase)
            fixture.service.deleteConfirmed(fixture.project, 3)
            assertTrue(fixture.service.recoverPending().isEmpty())
            assertEquals(fixture.outsideHash, hash(fixture.outside))
            assertEquals(fixture.otherHash, hash(fixture.other))
        }
    }

    @Test fun staleWholeProjectConfirmationCannotAdoptANewerRevisionOrStageAnyDeletion() = runBlocking {
        withFixture { fixture ->
            val before = fixture.database.dao().project(fixture.project)
            try {
                fixture.service.deleteConfirmed(fixture.project, 2)
                fail("A stale confirmation was silently retargeted to the current revision")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, fixture.database.dao().project(fixture.project))
            assertNull(fixture.persistence.pendingProject(fixture.project))
            assertEquals(fixture.photos, fixture.database.dao().photos(fixture.project))
            fixture.photos.forEach { assertEquals(it.sha256, hash(fixture.paths.photoFile(it))) }
            fixture.assertUnrelatedPreserved()
        }
    }

    @Test fun wholeProjectConfirmationCannotResumeADifferentPendingPhotoRemoval() = runBlocking {
        withFixture { fixture ->
            val first = fixture.photos.first()
            val operation = UUID.randomUUID().toString()
            fixture.persistence.stageConfirmed(DeletionIntent(operation, DeletionScope.Photo(fixture.project, first.id),
                3, 1, first.sha256, fixture.removalRequest).confirm(1))
            try {
                fixture.service.deleteConfirmed(fixture.project, 3)
                fail("Whole-project confirmation resumed a differently scoped pending deletion")
            } catch (_: IllegalArgumentException) { }
            assertEquals(DeletionPhase.TOMBSTONED, fixture.persistence.load(operation)?.phase)
            assertEquals(3L, fixture.database.dao().project(fixture.project)?.revision)
            assertEquals(fixture.photos, fixture.database.dao().photos(fixture.project))
            fixture.photos.forEach { assertEquals(it.sha256, hash(fixture.paths.photoFile(it))) }
            fixture.assertUnrelatedPreserved()
        }
    }

    private class Fixture(val context: Context, val directory: File) {
        val name = "a022-room-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        val project = UUID.randomUUID().toString()
        val removalRequest = UUID.randomUUID().toString()
        val resultKey = "sparse-m19-r3-0123456789abcdef"
        val paths = ProjectPathPolicy(File(directory, "projects"))
        val outside = File(directory, "external-source.jpg").apply { writeText("external source fixture") }
        val outsideHash = hash(outside)
        val other = File(paths.projectDirectory(UUID.randomUUID().toString()), "retained.fixture").apply { writeText("other project") }
        val otherHash = hash(other)
        val result = paths.artifact(project, resultKey, "json").apply { writeText("retained completed bytes") }
        val resultHash = hash(result)
        val photos = (0..2).map { position ->
            val id = UUID.randomUUID().toString()
            val file = paths.newOriginalFiles(project, id).second.apply {
                writeBytes(ByteArray(4096 + position) { ((it * 31 + position * 113) and 255).toByte() })
            }
            val sha = hash(file)
            val record = if (position == 0) CuratedPhoto(id, sha, position, CuratedPhotoState.REMOVAL_PENDING,
                pendingRemoval = PendingPhotoRemoval(removalRequest, 1, PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST), RestorablePhotoState.INCLUDED))
                else CuratedPhoto(id, sha, position)
            ScanPhoto(id, project, file.name, sha, file.length(), 32, 32, null, 1.0, 0.0, 0.0, 1L + position,
                included = position != 0, selectionOrder = position.toLong(), curationRecord = PhotoCurationRecordCodec.encode(record))
        }
        val persistence get() = RoomDeletionPersistence(database.deletionRequestDao())
        val service get() = DurableProjectDeletionService(database.dao(), persistence, ProjectLeaseCoordinator.shared(paths), paths)

        suspend fun insert() {
            database.dao().insert(ScanProject(project, "Deletion recovery", 1, revision = 3, state = "READY",
                resultRevision = 3, resultKey = resultKey, curationVersion = 1))
            photos.forEach { database.dao().insert(it) }
        }
        fun reopen() {
            database.close()
            database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        }
        fun assertUnrelatedPreserved() {
            assertEquals(resultHash, hash(result))
            assertEquals(outsideHash, hash(outside))
            assertEquals(otherHash, hash(other))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "a022-recovery-${UUID.randomUUID()}").apply { check(mkdir()) }
        val fixture = Fixture(context, directory)
        try { fixture.insert(); block(fixture) } finally {
            fixture.database.close(); context.deleteDatabase(fixture.name); check(directory.deleteRecursively())
        }
    }

    companion object {
        private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
