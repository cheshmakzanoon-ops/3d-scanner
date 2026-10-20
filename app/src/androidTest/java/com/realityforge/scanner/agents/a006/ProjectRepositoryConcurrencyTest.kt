package com.realityforge.scanner.agents.a006

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.operations.ProjectAccessException
import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectJobIdentity
import com.realityforge.scanner.data.operations.ProjectOperationConflictException
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectRepositoryConcurrencyTest {
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
