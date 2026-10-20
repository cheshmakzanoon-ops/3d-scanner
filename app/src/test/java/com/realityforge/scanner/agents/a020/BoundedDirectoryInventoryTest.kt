package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedDirectoryInventoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun directoryFanoutConsumesTheEntryBudgetAndDisablesDeletion() {
        val root = temporary.newFolder()
        repeat(20) { File(root, "directory-$it").mkdir() }
        val cache = File(root, "cache.bin").apply { writeText("stale") }
        val manifest = CacheArtifactManifest(1, "fanout", "project", 2, "test",
            listOf(CacheArtifactDescriptor(cache.name, CacheArtifactCategory.FEATURE_CACHE,
                CacheArtifactState.STALE, expectedBytes = cache.length())))
        val inventory = CacheInventoryBuilder(maxFiles = 5).build(root.toPath(), manifest,
            CacheProtectionSnapshot("project", 1))
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.FILE_COUNT_LIMIT })
        assertTrue(inventory.entries.size <= 5)
        val plan = CacheRetentionPlanner(CacheRetentionPolicy(staleFeatureGraceMillis = 0)).plan(inventory)
        assertEquals(0L, plan.reclaimableBytes)
        assertTrue(cache.exists())
    }
}
