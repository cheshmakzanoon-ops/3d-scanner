package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCleanupCancellation
import com.realityforge.scanner.data.cachegc.CacheCleanupExclusion
import com.realityforge.scanner.data.cachegc.CacheCollector
import com.realityforge.scanner.data.cachegc.CacheInventoryBuilder
import com.realityforge.scanner.data.cachegc.CacheProtectionReason
import com.realityforge.scanner.data.cachegc.CacheProtectionSnapshot
import com.realityforge.scanner.data.cachegc.CleanupExecutionState
import com.realityforge.scanner.data.cachegc.CleanupSkipCode
import com.realityforge.scanner.data.cachegc.InMemoryCleanupJournalStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class CacheCollectorRecoveryTest {
    @Test
    fun cancellationPersistsProgressAndResumeDeletesOnlyRemainingFiles() = withProject { _, root ->
        val artifacts = (1..3).map { index ->
            val path = "features/stale-$index.bin"
            val bytes = write(root, path, "feature-$index")
            setAge(root, path, 10)
            descriptor(path, CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, bytes, "feature-$index", includeHash = false)
        }
        val protection = CacheProtectionSnapshot(PROJECT, 3)
        val plan = planner().plan(
            CacheInventoryBuilder().build(root, manifest(*artifacts.toTypedArray()), protection, NOW),
            NOW,
        )
        val journal = InMemoryCleanupJournalStore()
        var checks = 0
        val first = CacheCollector(journal).execute(
            root,
            plan,
            FixedExclusion(protection),
            CacheCleanupCancellation { ++checks >= 3 },
        )
        assertEquals(CleanupExecutionState.CANCELLED, first.state)
        assertEquals(1, first.deletedPaths.size)

        val resumed = CacheCollector(journal).execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.COMPLETED, resumed.state)
        assertEquals(2, resumed.deletedPaths.size)
        artifacts.forEach { assertFalse(Files.exists(root.resolve(it.relativePath))) }
    }

    @Test
    fun crashAfterDeleteBeforeJournalIsReconciled() = withProject { _, root ->
        val bytes = write(root, "features/stale.bin", "stale")
        setAge(root, "features/stale.bin", 10)
        val protection = CacheProtectionSnapshot(PROJECT, 4)
        val plan = planner().plan(
            CacheInventoryBuilder().build(
                root,
                manifest(descriptor("features/stale.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, bytes, "stale", includeHash = false)),
                protection,
                NOW,
            ),
            NOW,
        )
        val journal = FailFirstSaveJournalStore()
        val first = CacheCollector(journal).execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.PARTIAL_FAILURE, first.state)
        assertFalse(Files.exists(root.resolve("features/stale.bin")))
        assertTrue(first.failures.any { it.operation == "save_journal" })

        val second = CacheCollector(journal).execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.COMPLETED, second.state)
        assertEquals(listOf("features/stale.bin"), second.reconciledMissingPaths)
    }

    @Test
    fun concurrentPublicationOrMetadataChangeForcesReplanAndPreservesFile() = withProject { _, root ->
        val bytes = write(root, "features/stale.bin", "stale")
        setAge(root, "features/stale.bin", 10)
        val initial = CacheProtectionSnapshot(PROJECT, 5)
        val plan = planner().plan(
            CacheInventoryBuilder().build(
                root,
                manifest(descriptor("features/stale.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, bytes, "stale", includeHash = false)),
                initial,
                NOW,
            ),
            NOW,
        )
        val changing = object : CacheCleanupExclusion {
            var calls = 0
            override val projectId: String = PROJECT
            override fun snapshot(): CacheProtectionSnapshot {
                calls++
                return if (calls >= 3) {
                    initial.copy(exactPaths = mapOf(
                        "features/stale.bin" to setOf(CacheProtectionReason.ACTIVE_PUBLICATION),
                    ))
                } else {
                    initial
                }
            }
            override fun close() = Unit
        }
        val protected = CacheCollector(InMemoryCleanupJournalStore()).execute(root, plan, changing)
        assertEquals(CleanupExecutionState.REPLAN_REQUIRED, protected.state)
        assertEquals(CleanupSkipCode.NEW_PROTECTION, protected.skipped.single().code)
        assertTrue(Files.exists(root.resolve("features/stale.bin")))

        val replacement = "replacement".toByteArray()
        Files.write(root.resolve("features/stale.bin"), replacement)
        Files.setLastModifiedTime(root.resolve("features/stale.bin"), FileTime.fromMillis(NOW - 1))
        val changed = CacheCollector(InMemoryCleanupJournalStore()).execute(root, plan, FixedExclusion(initial))
        assertEquals(CleanupExecutionState.REPLAN_REQUIRED, changed.state)
        assertEquals(CleanupSkipCode.PATH_CHANGED, changed.skipped.single().code)
        assertArrayEquals(replacement, Files.readAllBytes(root.resolve("features/stale.bin")))
    }
}
