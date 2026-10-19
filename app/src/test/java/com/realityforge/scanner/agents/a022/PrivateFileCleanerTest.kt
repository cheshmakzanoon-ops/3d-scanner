package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.data.deletion.CleanupBudget
import com.realityforge.scanner.data.deletion.CleanupOutcome
import com.realityforge.scanner.data.deletion.DeletionFailureCode
import com.realityforge.scanner.data.deletion.OwnedDeletionTarget
import com.realityforge.scanner.data.deletion.PrivateFileCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException

class PrivateFileCleanerTest {
    @Test
    fun boundedRetriesRemoveOnlyTheOwnedProjectAndNeverFollowSymlinks() {
        val sandbox = Files.createTempDirectory("agent022-cleaner-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val unrelatedId = UUID.randomUUID().toString()
            val project = Files.createDirectories(projects.resolve(projectId).resolve("originals"))
            repeat(5) { Files.write(project.resolve("$it.image"), ByteArray(it + 1) { 7 }) }
            val unrelated = Files.createDirectories(projects.resolve(unrelatedId))
            Files.write(unrelated.resolve("keep.txt"), ("keep").toByteArray())
            val external = Files.createDirectories(sandbox.resolve("external"))
            val externalFile = Files.write(external.resolve("outside.txt"), ("outside").toByteArray())
            try {
                Files.createSymbolicLink(projects.resolve(projectId).resolve("external-link"), external)
            } catch (unsupported: UnsupportedOperationException) {
                assumeNoException(unsupported)
            }

            val cleaner = PrivateFileCleaner(projects)
            val target = listOf(OwnedDeletionTarget.ProjectTree(projectId))
            var passes = 0
            var removed = 0L
            while (true) {
                passes++
                check(passes < 20) { "bounded cleanup did not converge" }
                when (val result = cleaner.clean(target, CleanupBudget(maxEntries = 2, maxBytes = 3, maxVisited = 4))) {
                    is CleanupOutcome.Complete -> {
                        removed += result.delta.entriesRemoved
                        break
                    }
                    is CleanupOutcome.MoreWork -> removed += result.delta.entriesRemoved
                    is CleanupOutcome.RetryableFailure -> error(result.failure)
                }
            }

            assertTrue("multiple bounded passes expected", passes > 1)
            assertTrue("files/directories should have been removed", removed >= 7)
            assertFalse(Files.exists(projects.resolve(projectId)))
            assertTrue(Files.exists(unrelated.resolve("keep.txt")))
            assertTrue("symlink target must remain", Files.exists(externalFile))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun intermediateSymlinkAndTraversalLikeTargetsCannotEscapePrivateRoot() {
        val sandbox = Files.createTempDirectory("agent022-symlink-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val project = Files.createDirectories(projects.resolve(projectId))
            val external = Files.createDirectories(sandbox.resolve("external"))
            val secret = Files.write(external.resolve("secret.txt"), ("preserve").toByteArray())
            try {
                Files.createSymbolicLink(project.resolve("alias"), external)
            } catch (unsupported: UnsupportedOperationException) {
                assumeNoException(unsupported)
            }

            val outcome = PrivateFileCleaner(projects).clean(
                listOf(OwnedDeletionTarget.ProjectRelativeNode(projectId, "alias/secret.txt")),
            )
            assertTrue(outcome is CleanupOutcome.RetryableFailure)
            assertEquals(
                DeletionFailureCode.PATH_COMPONENT_SYMLINK,
                (outcome as CleanupOutcome.RetryableFailure).failure.code,
            )
            assertTrue(Files.exists(secret))

            assertThrows(IllegalArgumentException::class.java) {
                OwnedDeletionTarget.ProjectRelativeNode(projectId, "../external/secret.txt")
            }
            assertThrows(IllegalArgumentException::class.java) {
                OwnedDeletionTarget.ProjectRelativeNode(projectId, "/absolute/path")
            }
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun retainedOrSharedFilesRemainWhenTheyAreNotInTheImmutableTargetSnapshot() {
        val sandbox = Files.createTempDirectory("agent022-retained-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val originals = Files.createDirectories(projects.resolve(projectId).resolve("originals"))
            val removed = Files.write(originals.resolve("remove.image"), ("remove").toByteArray())
            val retained = Files.write(originals.resolve("retained.image"), ("retain").toByteArray())

            val result = PrivateFileCleaner(projects).clean(
                listOf(OwnedDeletionTarget.ProjectRelativeNode(projectId, "originals/remove.image")),
            )
            assertTrue(result is CleanupOutcome.Complete)
            assertFalse(Files.exists(removed))
            assertTrue(Files.exists(retained))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationPropagatesAndARepeatedPassFinishesIdempotently() {
        val sandbox = Files.createTempDirectory("agent022-cancel-")
        try {
            val projects = Files.createDirectories(sandbox.resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val project = Files.createDirectories(projects.resolve(projectId))
            repeat(4) { Files.write(project.resolve("$it.tmp"), ("$it").toByteArray()) }
            val cleaner = PrivateFileCleaner(projects)
            var checkpoints = 0
            assertThrows(CancellationException::class.java) {
                cleaner.clean(listOf(OwnedDeletionTarget.ProjectTree(projectId))) {
                    checkpoints++
                    if (checkpoints == 5) throw CancellationException("test cancellation")
                }
            }
            assertTrue(Files.exists(projects.resolve(projectId)))
            val resumed = cleaner.clean(listOf(OwnedDeletionTarget.ProjectTree(projectId)))
            assertTrue(resumed is CleanupOutcome.Complete)
            assertFalse(Files.exists(projects.resolve(projectId)))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}
