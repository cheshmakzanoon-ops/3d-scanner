package com.realityforge.scanner.agents.a006

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.operations.ProjectAccessException
import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectJobIdentity
import com.realityforge.scanner.data.operations.ProjectOperationConflictException
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.data.operations.ProjectRecoveryAdmission
import com.realityforge.scanner.data.operations.ProjectRecoveryRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import android.content.ContextWrapper

@RunWith(AndroidJUnit4::class)
class ProjectRepositoryConcurrencyTest {
    @Test fun corruptProjectRecoveryIsIsolatedRetainsOriginalsAndCanRetryOrDelete() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(base.cacheDir, "agent006-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getApplicationContext() = this
        }
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val repository = ProjectRepository(context, database.dao(), database = database)
            val broken = repository.create("recovery-broken")
            val healthy = repository.create("recovery-healthy")
            val bytes = "original bytes preserved across a failed project recovery".toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val photoId = UUID.randomUUID().toString()
            val photo = ScanPhoto(photoId, broken, "$photoId.image", hash, bytes.size.toLong(),
                640, 480, 26.0, 1.0, 0.0, 0.0, 1L)
            repository.photoFile(photo).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            database.dao().addPhotoExpected(photo, 0)
            val key = "sparse-m16-r1-${"a".repeat(16)}"
            database.dao().complete(broken, 1, key, "Previously completed diagnostic result.")
            val journal = File(repository.directory(broken), "import-${UUID.randomUUID()}-0.journal")
                .apply { writeText("{incomplete original publication") }

            repository.recoverStorage()
            val blocked = requireNotNull(database.dao().project(broken))
            assertEquals(ProjectRecoveryAdmission.STATE, blocked.state)
            assertEquals(1L, blocked.revision)
            assertEquals(1L, blocked.resultRevision)
            assertEquals(key, blocked.resultKey)
            assertEquals(ProjectRecoveryAdmission.MESSAGE, blocked.message)
            assertFalse(blocked.message.contains(files.absolutePath))
            assertTrue(repository.photoFile(photo).readBytes().contentEquals(bytes))
            assertEquals(healthy, repository.inputSnapshot(healthy).projectId)
            try { repository.inputSnapshot(broken); fail("Unrecovered inputs were admitted") }
            catch (_: ProjectRecoveryRequiredException) { }
            try { repository.dao.invalidate(broken); fail("Compatibility DAO bypassed recovery admission") }
            catch (_: ProjectRecoveryRequiredException) { }
            // A failed old worker's late status update must not erase the recovery marker.
            assertEquals(0, repository.dao.state(broken, 1, "FAILED", "late worker message"))
            repository.withProjectAccess(ProjectOperationRequest.create(broken,
                ProjectOperationKind.READ_CURRENT_RESULT, 1)) { assertEquals(key, database.dao().project(broken)?.resultKey) }

            assertTrue(journal.delete())
            assertTrue(repository.recoverProjectStorage(broken))
            assertEquals("SPARSE_READY", database.dao().project(broken)?.state)
            assertEquals(hash, repository.inputSnapshot(broken).photos.single().sha256)

            journal.writeText("{another interrupted publication")
            assertFalse(repository.recoverProjectStorage(broken))
            repository.delete(broken)
            assertNull(database.dao().project(broken))
            assertEquals(healthy, repository.inputSnapshot(healthy).projectId)
            repository.delete(healthy)
        } finally { database.close(); files.deleteRecursively() }
    }

    @Test fun oneProjectReadBlocksMutationWhileAnotherProjectRemainsIndependent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repository = ProjectRepository(context, database.dao())
        val first = repository.create("lease-first")
        val second = repository.create("lease-second")
        val firstRevision = requireNotNull(repository.dao.project(first)).revision
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) {
            repository.withProjectAccess(
                ProjectOperationRequest.create(
                    first,
                    ProjectOperationKind.READ_INPUTS,
                    firstRevision,
                ),
            ) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            repository.dao.invalidate(first)
            fail("Input mutation bypassed an active project read reservation")
        } catch (_: ProjectOperationConflictException) {
            // Expected.
        }
        repository.dao.invalidate(second)
        assertEquals(1L, requireNotNull(repository.dao.project(second)).revision)
        assertNotNull(repository.dao.project(first))
        release.complete(Unit)
        reader.await()
        repository.dao.invalidate(first)
        assertEquals(1L, requireNotNull(repository.dao.project(first)).revision)
        repository.delete(first)
        repository.delete(second)
        database.close()
    }

    @Test fun staleBoundRequestIsRejectedAfterRevisionAdvances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repository = ProjectRepository(context, database.dao())
        val project = repository.create("stale-bound-request")
        repository.dao.invalidate(project)
        try {
            repository.withProjectAccess(
                ProjectOperationRequest.create(project, ProjectOperationKind.READ_INPUTS, 0),
            ) { fail("Stale operation reached its body") }
            fail("Stale revision was accepted")
        } catch (_: IllegalStateException) {
            // Typed stale revision derives from IllegalStateException.
        }
        repository.delete(project)
        database.close()
    }

    @Test fun reconstructionLeaseCanReadItsBoundSnapshotWithoutNestingAndSessionExpires() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repository = ProjectRepository(context, database.dao())
        val foreignRepository = ProjectRepository(context, database.dao())
        val project = repository.create("session-bound-snapshot")
        val revision = requireNotNull(repository.dao.project(project)).revision
        var escapedSession: ProjectAccessSession? = null
        val snapshot = repository.withProjectAccess(
            ProjectOperationRequest.create(
                projectId = project,
                kind = ProjectOperationKind.RECONSTRUCT,
                expectedRevision = revision,
                job = ProjectJobIdentity(
                    jobId = UUID.randomUUID().toString(),
                    attemptToken = UUID.randomUUID().toString(),
                ),
            ),
        ) { session ->
            escapedSession = session
            assertNull(repository.currentResult(session))
            try {
                foreignRepository.inputSnapshot(session)
                fail("A repository accepted another repository's active session")
            } catch (_: ProjectAccessException) {
                // Expected owner-token validation failure.
            }
            repository.inputSnapshot(session)
        }
        assertEquals(project, snapshot.projectId)
        assertEquals(revision, snapshot.revision)
        assertEquals(0, snapshot.photos.size)

        try {
            repository.inputSnapshot(requireNotNull(escapedSession))
            fail("A session escaped its reservation lifetime")
        } catch (_: ProjectAccessException) {
            // Expected.
        }

        repository.delete(project)
        database.close()
    }
}
