package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.data.deletion.CleanupBudget
import com.realityforge.scanner.data.deletion.DeletionBlocker
import com.realityforge.scanner.data.deletion.DeletionClock
import com.realityforge.scanner.data.deletion.DeletionFailureCode
import com.realityforge.scanner.data.deletion.DeletionIntent
import com.realityforge.scanner.data.deletion.DeletionPhase
import com.realityforge.scanner.data.deletion.DeletionRunResult
import com.realityforge.scanner.data.deletion.DeletionScope
import com.realityforge.scanner.data.deletion.DeletionService
import com.realityforge.scanner.data.deletion.DeletionTransition
import com.realityforge.scanner.data.deletion.OwnedDeletionTarget
import com.realityforge.scanner.data.deletion.PrivateFileCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class DeletionServiceTest {
    @Test
    fun confirmationCreatesTheTombstoneBeforeBoundedCleanupAndCompletion() = runBlocking {
        val sandbox = Files.createTempDirectory("agent022-service-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val project = Files.createDirectories(projects.resolve(projectId).resolve("originals"))
            repeat(5) { Files.write(project.resolve("$it.image"), ("private-$it").toByteArray()) }
            val externalSource = Files.write(sandbox.resolve("gallery-source.jpg"), ("external").toByteArray())
            val unrelatedId = UUID.randomUUID().toString()
            val unrelated = Files.createDirectories(projects.resolve(unrelatedId))
            Files.write(unrelated.resolve("keep.txt"), ("keep").toByteArray())

            val persistence = FakeDeletionPersistence(
                targetsFor = { listOf(OwnedDeletionTarget.ProjectTree(projectId)) },
            )
            val leases = FakeDeletionLeases()
            var now = 10L
            val service = DeletionService(
                persistence,
                leases,
                PrivateFileCleaner(projects),
                DeletionClock { now++ },
                Dispatchers.Unconfined,
            )
            val intent = DeletionIntent(
                operationId = UUID.randomUUID().toString(),
                scope = DeletionScope.Project(projectId),
                expectedRevision = 4,
                requestedAtEpochMillis = 1,
            )

            // Merely opening/dismissing confirmation performs no durable or file mutation.
            assertEquals(0, persistence.rows.size)
            assertTrue(Files.exists(projects.resolve(projectId)))

            var result = service.confirmAndRun(
                intent.confirm(2),
                CleanupBudget(maxEntries = 2, maxBytes = 32, maxVisited = 8),
            )
            assertTrue(result is DeletionRunResult.MoreWork)
            while (result !is DeletionRunResult.Completed) {
                result = service.retry(
                    intent.operationId,
                    CleanupBudget(maxEntries = 2, maxBytes = 32, maxVisited = 8),
                )
            }

            val completed = result.deletion
            assertEquals(DeletionPhase.COMPLETED, completed.phase)
            assertTrue(completed.attempts > 1)
            assertFalse(Files.exists(projects.resolve(projectId)))
            assertTrue(Files.exists(unrelated.resolve("keep.txt")))
            assertTrue("external source photo must never be deleted", Files.exists(externalSource))
            assertEquals(listOf(DeletionScope.Project(projectId)), persistence.finalizedScopes)
            assertEquals(leases.acquisitions, leases.releases)
            assertEquals("stage", persistence.events.first())
            assertEquals("finalize", persistence.events.last())
            assertTrue(persistence.events.contains("transition:${DeletionPhase.FILES_REMOVED}"))
            assertTrue(persistence.events.contains("transition:${DeletionPhase.FINALIZING_DATABASE}"))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun activeWorkKeepsAVisibleWaitingTombstoneAndRetryDrainsItLater() = runBlocking {
        val sandbox = Files.createTempDirectory("agent022-wait-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            Files.createDirectories(projects.resolve(projectId)).resolve("model.glb")
                .let { Files.write(it, ("model").toByteArray()) }
            val persistence = FakeDeletionPersistence(
                targetsFor = { listOf(OwnedDeletionTarget.ProjectTree(projectId)) },
            )
            val leases = FakeDeletionLeases().apply {
                blockers = setOf(DeletionBlocker.ACTIVE_EXPORT, DeletionBlocker.ACTIVE_PUBLICATION)
            }
            val service = DeletionService(
                persistence,
                leases,
                PrivateFileCleaner(projects),
                DeletionClock { 10 },
                Dispatchers.Unconfined,
            )
            val intent = DeletionIntent(
                UUID.randomUUID().toString(),
                DeletionScope.Project(projectId),
                0,
                1,
            )

            val waiting = service.confirmAndRun(intent.confirm(2))
            assertTrue(waiting is DeletionRunResult.Waiting)
            assertEquals(DeletionPhase.WAITING_FOR_ACTIVITY, waiting.deletion!!.phase)
            assertEquals(leases.blockers, waiting.deletion!!.blockers)
            assertTrue(Files.exists(projects.resolve(projectId)))
            assertTrue(persistence.finalizedScopes.isEmpty())

            leases.blockers = emptySet()
            val completed = service.retry(intent.operationId)
            assertTrue(completed is DeletionRunResult.Completed)
            assertFalse(Files.exists(projects.resolve(projectId)))
            assertEquals(2, leases.acquisitions)
            assertEquals(1, leases.releases)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun everyDatabaseFileBoundaryRecoversDeterministicallyAfterSimulatedProcessDeath() = runBlocking {
        for (boundary in CrashBoundary.values()) {
            val sandbox = Files.createTempDirectory("agent022-crash-${boundary.name.lowercase()}-")
            try {
                val projects = Files.createDirectories(sandbox.resolve("projects"))
                val projectId = UUID.randomUUID().toString()
                val project = Files.createDirectories(projects.resolve(projectId))
                Files.write(project.resolve("original.image"), ("private original").toByteArray())
                val persistence = FakeDeletionPersistence(
                    targetsFor = { listOf(OwnedDeletionTarget.ProjectTree(projectId)) },
                )
                boundary.install(persistence)
                val leases = FakeDeletionLeases()
                var now = 10L
                val service = DeletionService(
                    persistence,
                    leases,
                    PrivateFileCleaner(projects),
                    DeletionClock { now++ },
                    Dispatchers.Unconfined,
                )
                val intent = DeletionIntent(
                    UUID.randomUUID().toString(),
                    DeletionScope.Project(projectId),
                    3,
                    1,
                )

                try {
                    service.confirmAndRun(intent.confirm(2))
                    fail("$boundary did not simulate process death")
                } catch (_: SimulatedProcessDeath) {
                    // A committed tombstone is the recovery source of truth.
                }

                service.recoverPending(limit = 8)
                val completed = service.retry(intent.operationId)
                assertTrue("$boundary did not recover: $completed", completed is DeletionRunResult.Completed)
                assertEquals(DeletionPhase.COMPLETED, completed.deletion!!.phase)
                assertFalse("$boundary left private files", Files.exists(projects.resolve(projectId)))
                assertEquals("$boundary finalized more than once", 1, persistence.finalizedScopes.size)
            } finally {
                sandbox.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun cleanupFailureNeverFinalizesAndARealRetryCanSucceed() = runBlocking {
        val sandbox = Files.createTempDirectory("agent022-failure-")
        try {
            val projectsPath = sandbox.resolve("projects")
            Files.write(projectsPath, ("not a directory").toByteArray())
            val projectId = UUID.randomUUID().toString()
            val persistence = FakeDeletionPersistence(
                targetsFor = { listOf(OwnedDeletionTarget.ProjectTree(projectId)) },
            )
            val leases = FakeDeletionLeases()
            val service = DeletionService(
                persistence,
                leases,
                PrivateFileCleaner(projectsPath),
                DeletionClock { 10 },
                Dispatchers.Unconfined,
            )
            val intent = DeletionIntent(
                UUID.randomUUID().toString(),
                DeletionScope.Project(projectId),
                0,
                1,
            )

            val failed = service.confirmAndRun(intent.confirm(2))
            assertTrue(failed is DeletionRunResult.RetryableFailure)
            assertEquals(DeletionFailureCode.ROOT_NOT_DIRECTORY, failed.deletion!!.failure!!.code)
            assertTrue(persistence.finalizedScopes.isEmpty())

            Files.delete(projectsPath)
            Files.createDirectories(projectsPath.resolve(projectId))
            Files.write(projectsPath.resolve(projectId).resolve("private.bin"), ("private").toByteArray())
            val completed = service.retry(intent.operationId)
            assertTrue(completed is DeletionRunResult.Completed)
            assertFalse(Files.exists(projectsPath.resolve(projectId)))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private enum class CrashBoundary {
        AFTER_TOMBSTONE_COMMIT,
        AFTER_CLEANING_STATE_COMMIT,
        AFTER_FILE_UNLINK_BEFORE_PROGRESS_COMMIT,
        AFTER_FILES_REMOVED_COMMIT,
        AFTER_FINALIZING_STATE_COMMIT,
        AFTER_COMPLETION_COMMIT;

        fun install(persistence: FakeDeletionPersistence) {
            when (this) {
                AFTER_TOMBSTONE_COMMIT -> persistence.afterStage = {
                    throw SimulatedProcessDeath(name)
                }

                AFTER_CLEANING_STATE_COMMIT -> persistence.afterTransition = { _, next ->
                    if (next.phase == DeletionPhase.CLEANING_FILES) {
                        persistence.afterTransition = null
                        throw SimulatedProcessDeath(name)
                    }
                }

                AFTER_FILE_UNLINK_BEFORE_PROGRESS_COMMIT -> persistence.beforeTransition = { transition ->
                    if (transition is DeletionTransition.CleanupProgress) {
                        persistence.beforeTransition = null
                        throw SimulatedProcessDeath(name)
                    }
                }

                AFTER_FILES_REMOVED_COMMIT -> persistence.afterTransition = { _, next ->
                    if (next.phase == DeletionPhase.FILES_REMOVED) {
                        persistence.afterTransition = null
                        throw SimulatedProcessDeath(name)
                    }
                }

                AFTER_FINALIZING_STATE_COMMIT -> persistence.afterTransition = { _, next ->
                    if (next.phase == DeletionPhase.FINALIZING_DATABASE) {
                        persistence.afterTransition = null
                        throw SimulatedProcessDeath(name)
                    }
                }

                AFTER_COMPLETION_COMMIT -> persistence.afterFinalize = {
                    persistence.afterFinalize = null
                    throw SimulatedProcessDeath(name)
                }
            }
        }
    }
}
