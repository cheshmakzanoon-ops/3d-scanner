package com.realityforge.scanner.agents.a022

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.deletion.CleanupBudget
import com.realityforge.scanner.data.deletion.CleanupOutcome
import com.realityforge.scanner.data.deletion.OwnedDeletionTarget
import com.realityforge.scanner.data.deletion.PrivateFileCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeletionRecoveryDeviceTest {
    @Test
    fun androidPrivateStorageCleanupIsBoundedAndDoesNotFollowSymlinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = context.cacheDir.resolve("agent022-${UUID.randomUUID()}")
        try {
            val projects = Files.createDirectories(sandbox.toPath().resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val unrelatedId = UUID.randomUUID().toString()
            val project = Files.createDirectories(projects.resolve(projectId).resolve("originals"))
            repeat(5) { Files.write(project.resolve("$it.image"), ByteArray(1024) { it.toByte() }) }
            val unrelated = Files.createDirectories(projects.resolve(unrelatedId))
            Files.write(unrelated.resolve("keep.txt"), "keep".toByteArray())
            val outside = Files.createDirectories(sandbox.toPath().resolve("outside"))
            val outsidePhoto = Files.write(outside.resolve("source.jpg"), "external source".toByteArray())
            Files.createSymbolicLink(projects.resolve(projectId).resolve("outside-link"), outside)

            val cleaner = PrivateFileCleaner(projects)
            val targets = listOf(OwnedDeletionTarget.ProjectTree(projectId))
            var passes = 0
            while (true) {
                passes++
                check(passes < 32) { "Bounded private cleanup did not converge." }
                when (val outcome = cleaner.clean(
                    targets,
                    CleanupBudget(maxEntries = 2, maxBytes = 1500, maxVisited = 8),
                )) {
                    is CleanupOutcome.Complete -> break
                    is CleanupOutcome.MoreWork -> Unit
                    is CleanupOutcome.RetryableFailure -> error(outcome.failure)
                }
            }

            assertTrue("The test must exercise resumable cleanup.", passes > 1)
            assertFalse(Files.exists(projects.resolve(projectId)))
            assertTrue(Files.exists(unrelated.resolve("keep.txt")))
            assertTrue("A symlink target outside the project must remain.", Files.exists(outsidePhoto))
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun photoRemovalTouchesOnlyTheStagedPrivateCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = context.cacheDir.resolve("agent022-photo-${UUID.randomUUID()}")
        try {
            val projects = Files.createDirectories(sandbox.toPath().resolve("projects"))
            val projectId = UUID.randomUUID().toString()
            val originals = Files.createDirectories(projects.resolve(projectId).resolve("originals"))
            val privateCopy = Files.write(originals.resolve("delete.image"), "private".toByteArray())
            val retainedPrivateCopy = Files.write(originals.resolve("retain.image"), "retained".toByteArray())
            val externalSource = Files.write(sandbox.toPath().resolve("source.jpg"), "external".toByteArray())

            val outcome = PrivateFileCleaner(projects).clean(
                listOf(
                    OwnedDeletionTarget.ProjectRelativeNode(
                        projectId,
                        "originals/delete.image",
                    ),
                ),
            )
            assertTrue(outcome is CleanupOutcome.Complete)
            assertFalse(Files.exists(privateCopy))
            assertTrue(Files.exists(retainedPrivateCopy))
            assertTrue(Files.exists(externalSource))
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
