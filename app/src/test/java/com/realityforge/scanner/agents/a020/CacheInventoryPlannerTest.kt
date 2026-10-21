package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactDescriptor
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCollector
import com.realityforge.scanner.data.cachegc.CacheInventoryBuilder
import com.realityforge.scanner.data.cachegc.CacheProtectionReason
import com.realityforge.scanner.data.cachegc.CacheProtectionSnapshot
import com.realityforge.scanner.data.cachegc.CleanupExecutionState
import com.realityforge.scanner.data.cachegc.FileCleanupJournalStore
import com.realityforge.scanner.data.cachegc.InMemoryCleanupJournalStore
import com.realityforge.scanner.data.cachegc.InventoryIssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class CacheInventoryPlannerTest {
    @Test
    fun exactPlanPreservesOriginalsReferencesRetainedResultsAndUnknownFiles() = withProject { base, root ->
        val original = write(root, "originals/photo.image", "original")
        val active = write(root, "features/active.bin", "active")
        val stale = write(root, "features/stale.bin", "stale")
        val depthNew = write(root, "depth/new/ref.bin", "depth-new")
        val depthOld = write(root, "depth/old/ref.bin", "depth-old")
        val current = write(root, "results/current.glb", "current")
        val retained = write(root, "results/retained.glb", "retained")
        val obsolete = write(root, "results/obsolete.glb", "obsolete")
        val partial = write(root, "staging/old.partial", "partial")
        val mismatch = write(root, "features/mismatch.bin", "changed")
        val unknown = write(root, "vendor/unknown.dat", "unknown")
        listOf(
            "originals/photo.image", "features/active.bin", "features/stale.bin",
            "depth/old/ref.bin", "results/current.glb", "results/obsolete.glb",
            "staging/old.partial", "features/mismatch.bin", "vendor/unknown.dat",
        ).forEach { setAge(root, it, 10) }
        setAge(root, "depth/new/ref.bin", 2)
        setAge(root, "results/retained.glb", 2)

        val artifacts = manifest(
            descriptor("originals/photo.image", CacheArtifactCategory.ORIGINAL, CacheArtifactState.CURRENT, original),
            descriptor("features/active.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, active, "active"),
            descriptor("features/stale.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, stale, "stale"),
            descriptor("depth/new/ref.bin", CacheArtifactCategory.DEPTH_CHECKPOINT, CacheArtifactState.STALE, depthNew, "depth-new"),
            descriptor("depth/old/ref.bin", CacheArtifactCategory.DEPTH_CHECKPOINT, CacheArtifactState.STALE, depthOld, "depth-old"),
            descriptor("results/current.glb", CacheArtifactCategory.RESULT, CacheArtifactState.CURRENT, current, "current", 3),
            descriptor("results/retained.glb", CacheArtifactCategory.RESULT, CacheArtifactState.OBSOLETE, retained, "retained", 2),
            descriptor("results/obsolete.glb", CacheArtifactCategory.RESULT, CacheArtifactState.OBSOLETE, obsolete, "obsolete", 1),
            descriptor("staging/old.partial", CacheArtifactCategory.TEMPORARY, CacheArtifactState.ABANDONED, partial, "old-attempt"),
            CacheArtifactDescriptor(
                relativePath = "features/mismatch.bin",
                category = CacheArtifactCategory.FEATURE_CACHE,
                state = CacheArtifactState.STALE,
                identity = "mismatch",
                expectedBytes = mismatch.size.toLong() + 1,
                expectedSha256 = sha256(mismatch),
            ),
        )
        val protection = CacheProtectionSnapshot(
            projectId = PROJECT,
            generation = 9,
            exactPaths = mapOf(
                "originals/photo.image" to setOf(CacheProtectionReason.ORIGINAL_INPUT),
                "features/active.bin" to setOf(CacheProtectionReason.ACTIVE_JOB),
                "results/current.glb" to setOf(CacheProtectionReason.CURRENT_RESULT),
            ),
        )
        val inventory = CacheInventoryBuilder().build(root, artifacts, protection, NOW)
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.UNDECLARED_FILE_PRESERVED })
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.MANIFEST_SIZE_MISMATCH })
        val plan = planner().plan(inventory, NOW)
        assertEquals(
            setOf("features/stale.bin", "depth/old/ref.bin", "results/obsolete.glb", "staging/old.partial"),
            plan.targets.map { it.relativePath }.toSet(),
        )

        val originalHash = sha256(original)
        val currentHash = sha256(current)
        val retainedHash = sha256(retained)
        val unknownHash = sha256(unknown)
        val result = CacheCollector(FileCleanupJournalStore(base.resolve("journals")))
            .execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.COMPLETED, result.state)
        plan.targets.forEach { assertFalse(Files.exists(root.resolve(it.relativePath))) }
        assertEquals(originalHash, sha256(Files.readAllBytes(root.resolve("originals/photo.image"))))
        assertEquals(currentHash, sha256(Files.readAllBytes(root.resolve("results/current.glb"))))
        assertEquals(retainedHash, sha256(Files.readAllBytes(root.resolve("results/retained.glb"))))
        assertEquals(unknownHash, sha256(Files.readAllBytes(root.resolve("vendor/unknown.dat"))))
        assertTrue(Files.exists(root.resolve("features/active.bin")))
        assertTrue(Files.exists(root.resolve("depth/new/ref.bin")))
        assertTrue(Files.exists(root.resolve("features/mismatch.bin")))

        val repeated = CacheCollector(InMemoryCleanupJournalStore())
            .execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.COMPLETED, repeated.state)
        assertEquals(plan.targets.map { it.relativePath }.toSet(), repeated.reconciledMissingPaths.toSet())
        assertTrue(repeated.deletedPaths.isEmpty())
    }

    @Test
    fun incompleteInventoryDisablesDeletionRatherThanGuessingRetention() = withProject { _, root ->
        val a = write(root, "features/a.bin", "a")
        val b = write(root, "features/b.bin", "b")
        setAge(root, "features/a.bin", 10)
        setAge(root, "features/b.bin", 10)
        val protection = CacheProtectionSnapshot(PROJECT, 12)
        val inventory = CacheInventoryBuilder(maxFiles = 1).build(
            root,
            manifest(
                descriptor("features/a.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, a, "a"),
                descriptor("features/b.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, b, "b"),
            ),
            protection,
            NOW,
        )
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.FILE_COUNT_LIMIT })
        assertTrue(planner().plan(inventory, NOW).targets.isEmpty())
        assertTrue(Files.exists(root.resolve("features/a.bin")))
        assertTrue(Files.exists(root.resolve("features/b.bin")))
    }

    @Test
    fun traversalSymlinkAndUnknownContentNeverBecomeDeletionCandidates() = withProject { _, root ->
        try {
            CacheArtifactDescriptor("../escape", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE)
            fail("Traversal path should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        write(root, "vendor/unowned.bin", "unknown")
        val outside = Files.createTempFile(root.parent, "outside", ".bin")
        val link = root.resolve("features/link.bin")
        Files.createDirectories(link.parent)
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return@withProject
        }
        val declaredLink = CacheArtifactDescriptor(
            relativePath = "features/link.bin",
            category = CacheArtifactCategory.FEATURE_CACHE,
            state = CacheArtifactState.STALE,
            identity = "link",
            expectedBytes = Files.size(outside),
        )
        val inventory = CacheInventoryBuilder().build(
            root,
            manifest(declaredLink),
            CacheProtectionSnapshot(PROJECT, 2),
            NOW,
        )
        assertTrue(planner().plan(inventory, NOW).targets.isEmpty())
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.SYMBOLIC_LINK_PRESERVED })
        assertTrue(inventory.issues.any { it.code == InventoryIssueCode.UNDECLARED_FILE_PRESERVED })
        assertTrue(Files.exists(outside))
    }
}
