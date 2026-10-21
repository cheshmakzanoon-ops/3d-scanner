package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCollector
import com.realityforge.scanner.data.cachegc.CacheInventoryBuilder
import com.realityforge.scanner.data.cachegc.CacheProtectionReason
import com.realityforge.scanner.data.cachegc.CacheProtectionSnapshot
import com.realityforge.scanner.data.cachegc.CacheReuseRejectionCode
import com.realityforge.scanner.data.cachegc.CacheReuseRequest
import com.realityforge.scanner.data.cachegc.CacheReuseValidation
import com.realityforge.scanner.data.cachegc.CacheReuseValidator
import com.realityforge.scanner.data.cachegc.CleanupExecutionState
import com.realityforge.scanner.data.cachegc.InMemoryCleanupJournalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CacheMultiRevisionTest {
    @Test
    fun cleanupPreservesOriginalAndResultAndOnlyDeletedStagesNeedRecomputation() = withProject { _, root ->
        val original = write(root, "originals/photo.image", "photo bytes")
        val retainedResult = write(root, "results/r1.glb", "retained result")
        val oldFeature = write(root, "features/r1.bin", "old feature")
        val oldDepth = write(root, "depth/r1/ref.bin", "old depth")
        val currentFeature = write(root, "features/r2.bin", "current feature")
        val currentDepth = write(root, "depth/r2/ref.bin", "current depth")
        listOf("features/r1.bin", "depth/r1/ref.bin").forEach { setAge(root, it, 10) }
        listOf("originals/photo.image", "results/r1.glb", "features/r2.bin", "depth/r2/ref.bin")
            .forEach { setAge(root, it, 1) }

        val artifacts = manifest(
            descriptor("originals/photo.image", CacheArtifactCategory.ORIGINAL, CacheArtifactState.CURRENT, original),
            descriptor("results/r1.glb", CacheArtifactCategory.RESULT, CacheArtifactState.OBSOLETE, retainedResult, "result-r1", 1),
            descriptor("features/r1.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, oldFeature, "feature-r1", 1),
            descriptor("depth/r1/ref.bin", CacheArtifactCategory.DEPTH_CHECKPOINT, CacheArtifactState.STALE, oldDepth, "depth-r1", 1),
            descriptor("features/r2.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.REUSABLE, currentFeature, "feature-r2", 2),
            descriptor("depth/r2/ref.bin", CacheArtifactCategory.DEPTH_CHECKPOINT, CacheArtifactState.REUSABLE, currentDepth, "depth-r2", 2),
        )
        val protection = CacheProtectionSnapshot(
            projectId = PROJECT,
            generation = 8,
            exactPaths = mapOf(
                "originals/photo.image" to setOf(CacheProtectionReason.ORIGINAL_INPUT),
                "results/r1.glb" to setOf(CacheProtectionReason.RETAINED_RESULT),
            ),
        )
        val originalHash = sha256(original)
        val resultHash = sha256(retainedResult)
        val plan = planner(keepDepth = 0, keepResults = 0).plan(
            CacheInventoryBuilder().build(root, artifacts, protection, NOW),
            NOW,
        )
        assertEquals(setOf("features/r1.bin", "depth/r1/ref.bin"), plan.targets.map { it.relativePath }.toSet())
        val cleanup = CacheCollector(InMemoryCleanupJournalStore()).execute(root, plan, FixedExclusion(protection))
        assertEquals(CleanupExecutionState.COMPLETED, cleanup.state)
        assertEquals(originalHash, sha256(Files.readAllBytes(root.resolve("originals/photo.image"))))
        assertEquals(resultHash, sha256(Files.readAllBytes(root.resolve("results/r1.glb"))))

        val validator = CacheReuseValidator()
        listOf("features/r2.bin", "depth/r2/ref.bin").forEach { path ->
            val request = CacheReuseRequest(PROJECT, artifacts.manifestId, artifacts.artifacts.first { it.relativePath == path })
            assertTrue(validator.validate(root, request) is CacheReuseValidation.Valid)
        }
        val oldAsReusable = artifacts.artifacts.first { it.relativePath == "features/r1.bin" }
            .copy(state = CacheArtifactState.REUSABLE)
        val missing = validator.validate(root, CacheReuseRequest(PROJECT, artifacts.manifestId, oldAsReusable))
            as CacheReuseValidation.Rejected
        assertEquals(CacheReuseRejectionCode.MISSING, missing.code)

        val recomputed = write(root, "features/r1.bin", "recomputed feature")
        val recomputedDescriptor = oldAsReusable.copy(
            expectedBytes = recomputed.size.toLong(),
            expectedSha256 = sha256(recomputed),
            identity = "feature-r1-recomputed",
        )
        assertTrue(
            validator.validate(root, CacheReuseRequest(PROJECT, "manifest-rerun", recomputedDescriptor))
                is CacheReuseValidation.Valid,
        )
        assertFalse(Files.exists(root.resolve("depth/r1/ref.bin")))
    }
}
