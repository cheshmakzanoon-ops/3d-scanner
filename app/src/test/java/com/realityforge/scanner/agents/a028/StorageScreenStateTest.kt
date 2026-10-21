package com.realityforge.scanner.agents.a028

import com.realityforge.scanner.agents.a020.*
import com.realityforge.scanner.data.cachegc.*
import com.realityforge.scanner.data.storagebudget.StorageAccounting
import com.realityforge.scanner.ui.storage.StorageScreenState
import org.junit.Assert.*
import org.junit.Test

class StorageScreenStateTest {
    @Test fun measuredFilesystemTotalsAndCleanupScopeRemainExactInScreenState() = withProject { _, root ->
        val original = write(root, "originals/photo.image", "preserved-original")
        val stale = write(root, "features/stale.bin", "discardable-feature-bytes")
        setAge(root, "features/stale.bin", 10)
        val inventory = CacheInventoryBuilder().build(root, manifest(
            descriptor("originals/photo.image", CacheArtifactCategory.ORIGINAL, CacheArtifactState.CURRENT, original),
            descriptor("features/stale.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, stale, "old-features")),
            CacheProtectionSnapshot(PROJECT, 2), NOW)
        val plan = planner().plan(inventory, NOW)
        val state = StorageScreenState(inventory, plan, measuredAvailableBytes = 12345, measuring = false)
        assertEquals((original.size + stale.size).toLong(), state.inventory!!.totalBytes)
        assertEquals(original.size.toLong(), state.inventory.bytesByCategory[CacheArtifactCategory.ORIGINAL])
        assertEquals(stale.size.toLong(), state.plan!!.reclaimableBytes)
        assertEquals(listOf("features/stale.bin"), state.plan.targets.map { it.relativePath })
        assertTrue(state.canClean)
        assertFalse(state.copy(projectBusy = true).canClean)
        assertFalse(state.copy(cleaning = true).canClean)
        assertFalse(state.copy(measuring = true).canClean)
        assertFalse(state.copy(plan = plan.copy(rootIdentity = "0".repeat(64))).canClean)
        assertFalse(state.copy(plan = plan.copy(projectRevision = inventory.projectRevision + 1)).canClean)
        assertFalse(state.copy(plan = plan.copy(referenceGeneration = inventory.referenceGeneration + 1)).canClean)
        assertEquals(sha256(original), sha256(root.resolve("originals/photo.image").toFile().readBytes()))
    }

    @Test fun unknownMeasurementsAndBoundedIncompleteInventoryNeverEnableCleanup() = withProject { _, root ->
        val waiting = StorageScreenState()
        assertNull(waiting.inventory)
        assertNull(waiting.measuredAvailableBytes)
        assertTrue(waiting.measuring)
        assertFalse(waiting.canClean)
        val a = write(root, "features/a", "a")
        val b = write(root, "features/b", "b")
        val inventory = CacheInventoryBuilder(maxFiles = 1).build(root, manifest(
            descriptor("features/a", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, a, "a"),
            descriptor("features/b", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, b, "b")),
            CacheProtectionSnapshot(PROJECT, 2), NOW)
        val state = StorageScreenState(inventory, planner().plan(inventory, NOW), measuring = false)
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.FILE_COUNT_LIMIT })
        assertFalse(state.canClean)
    }

    @Test fun failedOperationBudgetRetainsItsObservedFactsWithoutPromotingReclaimableSpace() {
        val failure = StorageAccounting.decide(100, 50, listOf(10), reclaimableBytes = 1000, safetyBytes = 5)
        val state = StorageScreenState(measuring = false, budgetFailure = failure, measuredAvailableBytes = null)
        assertFalse(failure.admitted)
        assertEquals(50L, state.budgetFailure!!.measuredAvailableBytes)
        assertEquals(65L, state.budgetFailure.shortfallBytes)
        assertNull(state.measuredAvailableBytes)
        assertFalse(state.canClean)
    }
}
