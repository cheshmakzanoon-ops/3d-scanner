package com.realityforge.scanner.agents.a020

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactDescriptor
import com.realityforge.scanner.data.cachegc.CacheArtifactManifest
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCleanupExclusion
import com.realityforge.scanner.data.cachegc.CacheCollector
import com.realityforge.scanner.data.cachegc.CacheInventoryBuilder
import com.realityforge.scanner.data.cachegc.CacheProtectionReason
import com.realityforge.scanner.data.cachegc.CacheProtectionSnapshot
import com.realityforge.scanner.data.cachegc.CacheRetentionPlanner
import com.realityforge.scanner.data.cachegc.CacheRetentionPolicy
import com.realityforge.scanner.data.cachegc.CleanupExecutionState
import com.realityforge.scanner.data.cachegc.FileCleanupJournalStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class CacheCollectorDeviceTest {
    @Test
    fun privateFilesystemCleanupPreservesProtectedBytesAndIsIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = context.cacheDir.toPath().resolve("agent-020-${System.nanoTime()}")
        val root = Files.createDirectories(base.resolve("project"))
        try {
            val original = "private original".toByteArray()
            val result = "completed result".toByteArray()
            val stale = "stale feature".toByteArray()
            Files.createDirectories(root.resolve("originals"))
            Files.createDirectories(root.resolve("results"))
            Files.createDirectories(root.resolve("features"))
            Files.write(root.resolve("originals/photo.image"), original)
            Files.write(root.resolve("results/current.glb"), result)
            Files.write(root.resolve("features/stale.bin"), stale)
            Files.setLastModifiedTime(root.resolve("features/stale.bin"), FileTime.fromMillis(1L))

            val manifest = CacheArtifactManifest(
                schemaVersion = 1,
                manifestId = "device-manifest-020",
                projectId = "device-project-020",
                projectRevision = 2,
                engineVersion = "device-test",
                artifacts = listOf(
                    descriptor("originals/photo.image", CacheArtifactCategory.ORIGINAL, CacheArtifactState.CURRENT, original),
                    descriptor("results/current.glb", CacheArtifactCategory.RESULT, CacheArtifactState.CURRENT, result, "result-current"),
                    descriptor("features/stale.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, stale, "feature-stale"),
                ),
            )
            val protection = CacheProtectionSnapshot(
                projectId = "device-project-020",
                generation = 1,
                exactPaths = mapOf(
                    "originals/photo.image" to setOf(CacheProtectionReason.ORIGINAL_INPUT),
                    "results/current.glb" to setOf(CacheProtectionReason.CURRENT_RESULT),
                ),
            )
            val inventory = CacheInventoryBuilder().build(root, manifest, protection, 10_000L)
            val plan = CacheRetentionPlanner(
                CacheRetentionPolicy(
                    staleFeatureGraceMillis = 0,
                    staleDepthGraceMillis = 0,
                    staleIntermediateGraceMillis = 0,
                    obsoleteResultGraceMillis = 0,
                    abandonedTemporaryGraceMillis = 0,
                    keepRecentDepthIdentities = 0,
                    keepRecentResultIdentities = 0,
                ),
            ).plan(inventory, 10_000L)
            assertEquals(listOf("features/stale.bin"), plan.targets.map { it.relativePath })

            val collector = CacheCollector(FileCleanupJournalStore(base.resolve("journals")))
            val first = collector.execute(root, plan, FixedExclusion(protection))
            assertEquals(CleanupExecutionState.COMPLETED, first.state)
            assertFalse(Files.exists(root.resolve("features/stale.bin")))
            assertArrayEquals(original, Files.readAllBytes(root.resolve("originals/photo.image")))
            assertArrayEquals(result, Files.readAllBytes(root.resolve("results/current.glb")))

            val repeated = collector.execute(root, plan, FixedExclusion(protection))
            assertEquals(CleanupExecutionState.COMPLETED, repeated.state)
            assertTrue(repeated.deletedPaths.isEmpty())
            assertEquals(listOf("features/stale.bin"), repeated.reconciledMissingPaths)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun descriptor(
        path: String,
        category: CacheArtifactCategory,
        state: CacheArtifactState,
        bytes: ByteArray,
        identity: String? = null,
    ) = CacheArtifactDescriptor(
        relativePath = path,
        category = category,
        state = state,
        identity = identity,
        expectedBytes = bytes.size.toLong(),
        expectedSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

    private class FixedExclusion(
        private val protection: CacheProtectionSnapshot,
    ) : CacheCleanupExclusion {
        override val projectId: String = protection.projectId
        override fun snapshot(): CacheProtectionSnapshot = protection
        override fun close() = Unit
    }
}
