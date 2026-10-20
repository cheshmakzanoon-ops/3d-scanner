package com.realityforge.scanner.agents.a006

import com.realityforge.scanner.data.operations.FileProjectLeaseStore
import com.realityforge.scanner.data.operations.ProjectAccessException
import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectJobIdentity
import com.realityforge.scanner.data.operations.ProjectOperationConflictException
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ProjectLeaseCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sharedReadersCoexistButMutationConflictsOnOneProject() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        val store = FileProjectLeaseStore(paths)
        val project = UUID.randomUUID().toString()
        val first = store.acquire(request(project, ProjectOperationKind.READ_INPUTS))
        val second = store.acquire(request(project, ProjectOperationKind.EXPORT_CAPTURE))
        assertEquals(2, store.activeCount(project))
        expectConflict { store.acquire(request(project, ProjectOperationKind.MUTATE_INPUTS)) }
        second.close()
        first.close()
        val mutation = store.acquire(request(project, ProjectOperationKind.MUTATE_INPUTS))
        assertEquals(1, store.activeCount(project))
        mutation.close()
        store.close()
    }

    @Test fun independentProjectsDoNotBlockEachOther() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        val store = FileProjectLeaseStore(paths)
        val firstProject = UUID.randomUUID().toString()
        val secondProject = UUID.randomUUID().toString()
        val first = store.acquire(request(firstProject, ProjectOperationKind.DELETE_PROJECT))
        val second = store.acquire(request(secondProject, ProjectOperationKind.MUTATE_INPUTS))
        assertEquals(1, store.activeCount(firstProject))
        assertEquals(1, store.activeCount(secondProject))
        second.close()
        first.close()
        store.close()
    }

    @Test fun duplicateOperationIdentityIsRejectedAndCloseIsIdempotent() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        val store = FileProjectLeaseStore(paths)
        val project = UUID.randomUUID().toString()
        val operation = UUID.randomUUID().toString()
        val request = ProjectOperationRequest(project, operation, ProjectOperationKind.READ_INPUTS, 0)
        val lease = store.acquire(request)
        expectConflict { store.acquire(request) }
        lease.close()
        lease.close()
        assertEquals(0, store.activeCount(project))
        store.close()
    }

    @Test fun anotherStoreCannotAcquireUntilProcessOwnerReleasesThenStaleReceiptIsReplaced() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        val project = UUID.randomUUID().toString()
        val firstOperation = UUID.randomUUID().toString()
        val firstStore = FileProjectLeaseStore(paths)
        firstStore.acquire(
            ProjectOperationRequest(project, firstOperation, ProjectOperationKind.DELETE_PROJECT, 4),
        )
        val restartedStore = FileProjectLeaseStore(paths)
        expectConflict {
            restartedStore.acquire(request(project, ProjectOperationKind.READ_CURRENT_RESULT, revision = 4))
        }

        // close() models the OS releasing every file lock when the process exits.
        firstStore.close()
        paths.leaseFile(project).writeText(
            "project=$project\nschema=1\nlease.0.operation=$firstOperation\nlease.0.kind=DELETE_PROJECT\n",
        )
        val nextOperation = UUID.randomUUID().toString()
        val recovered = restartedStore.acquire(
            ProjectOperationRequest(project, nextOperation, ProjectOperationKind.MUTATE_INPUTS, 4),
        )
        val receipt = paths.leaseFile(project).readText()
        assertTrue(receipt.contains("lease.0.operation=$nextOperation"))
        assertTrue(!receipt.contains("lease.0.operation=$firstOperation"))
        recovered.close()
        restartedStore.close()
    }

    @Test fun reconstructionAndPublicationRequireDurableJobAttemptIdentity() {
        val project = UUID.randomUUID().toString()
        val operation = UUID.randomUUID().toString()
        expectIllegal {
            ProjectOperationRequest(project, operation, ProjectOperationKind.RECONSTRUCT, 0)
        }
        val job = ProjectJobIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val request = ProjectOperationRequest(project, operation, ProjectOperationKind.PUBLISH_RESULT, 0, job)
        assertEquals(job, request.job)
    }

    @Test fun accessSessionIsBoundToItsRepositoryAndReservationLifetime() {
        val owner = Any()
        val session = ProjectAccessSession(
            request = request(UUID.randomUUID().toString(), ProjectOperationKind.READ_INPUTS, revision = 3),
            acquiredRevision = 3,
            acquiredAtEpochMillis = 10,
            ownerToken = owner,
        )
        session.requireActive(owner)
        expectAccessFailure { session.requireActive(Any()) }
        session.invalidate()
        expectAccessFailure { session.requireActive(owner) }
    }

    private fun request(
        project: String,
        kind: ProjectOperationKind,
        revision: Long = 0,
    ) = ProjectOperationRequest.create(project, kind, revision)

    private fun expectConflict(block: () -> Unit) {
        try {
            block()
            fail("Conflicting reservation was accepted")
        } catch (_: ProjectOperationConflictException) {
            // Expected typed conflict.
        }
    }

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Invalid operation request was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun expectAccessFailure(block: () -> Unit) {
        try {
            block()
            fail("Inactive or foreign access session was accepted")
        } catch (_: ProjectAccessException) {
            // Expected capability validation failure.
        }
    }
}
