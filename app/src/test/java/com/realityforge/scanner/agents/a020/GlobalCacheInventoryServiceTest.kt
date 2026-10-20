package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.*
import com.realityforge.scanner.data.library.LibraryQuery
import com.realityforge.scanner.data.library.ProjectSummary
import com.realityforge.scanner.data.operations.ProjectOperationConflictException
import com.realityforge.scanner.data.operations.ProjectOperationKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlobalCacheInventoryServiceTest {
    @Test fun boundedGlobalPageMeasuresRealFilesAndKeepsBusyUnknownAndPartialBytesHonest() = runBlocking {
        withProject { base, _ ->
            val queries = mutableListOf<LibraryQuery>()
            val measuredIds = mutableListOf<String>()
            val projects = listOf("measured", "busy", "unavailable", "partial", "changed", "deleting")
                .map { summary(it, if (it == "deleting") "DELETING" else "READY") }
            val service = GlobalCacheInventoryService({ request -> queries += request; projects }, { id ->
                measuredIds += id
                when (id) {
                    "busy" -> throw ProjectOperationConflictException(id, ProjectOperationKind.CLEANUP, "Active job")
                    "unavailable" -> throw IOException("Unavailable private file tree")
                    "partial" -> files(base, id, maxFiles = 1)
                    "changed" -> files(base, id, revision = 4)
                    else -> files(base, id)
                }
            })
            val result = service.measurePage(pageSize = 6)
            assertEquals(7, queries.single().limit)
            assertFalse(result.hasNextPage)
            assertEquals(5, result.projects.size)
            assertFalse("deleting" in measuredIds)
            assertEquals(listOf(ProjectStorageMeasurement.MEASURED, ProjectStorageMeasurement.BUSY,
                ProjectStorageMeasurement.UNAVAILABLE, ProjectStorageMeasurement.PARTIAL,
                ProjectStorageMeasurement.CHANGED), result.projects.map { it.measurement })
            assertEquals(2, result.measuredProjectCount)
            assertEquals(1, result.completeProjectCount)
            assertEquals(18L, result.measuredSubtotalBytes)
            assertEquals(5L, result.reclaimableSubtotalBytes)
            assertEquals(8L, result.measuredCategoryBytes(CacheArtifactCategory.ORIGINAL))
            assertNull(result.projects[1].measuredBytes)
            assertNull(result.projects[2].measuredBytes)
            assertNull(result.projects[3].reclaimableBytes)
            assertNull(result.projects[4].measuredBytes)
            assertEquals("original", base.resolve("measured/original.image").toFile().readText(Charsets.UTF_8))
            assertEquals("cache", base.resolve("measured/feature.bin").toFile().readText(Charsets.UTF_8))
        }
    }

    @Test fun lookaheadIsNotMeasuredAndSubsequentPagesStayBounded() = runBlocking {
        withProject { base, _ ->
            val projects = (0..4).map { summary("project-$it") }
            val queries = mutableListOf<LibraryQuery>()
            val measured = mutableListOf<String>()
            val service = GlobalCacheInventoryService({ query ->
                queries += query
                projects.drop(query.offset).take(query.limit)
            }, { id -> measured += id; files(base, id) })
            val first = service.measurePage(pageSize = 2)
            assertTrue(first.hasNextPage)
            assertEquals(listOf("project-0", "project-1"), measured)
            val last = service.measurePage(offset = 4, pageSize = 2)
            assertFalse(last.hasNextPage)
            assertEquals(listOf("project-4"), last.projects.map { it.project.id })
            assertEquals(listOf(3, 3), queries.map { it.limit })
            assertEquals(listOf(0, 4), queries.map { it.offset })
            assertEquals(listOf("project-0", "project-1", "project-4"), measured)
        }
    }

    @Test fun cancellationStopsBeforeAnotherProjectAndPreservesTheCompletedProgressSnapshot() = runBlocking {
        withProject { base, _ ->
            var calls = 0
            var progress: GlobalStoragePage? = null
            val service = GlobalCacheInventoryService({ listOf(summary("first"), summary("unopened")) },
                { id -> calls++; files(base, id) })
            try {
                service.measurePage { snapshot ->
                    progress = snapshot
                    if (snapshot.finishedProjectCount == 1) throw CancellationException("Stop after one measured project")
                }
                fail("Measurement cancellation was swallowed")
            } catch (_: CancellationException) { }
            assertEquals(1, calls)
            assertEquals(1, requireNotNull(progress).measuredProjectCount)
            assertEquals(ProjectStorageMeasurement.PENDING, requireNotNull(progress).projects[1].measurement)
            assertEquals("original", base.resolve("first/original.image").toFile().readText(Charsets.UTF_8))
            assertFalse(Files.exists(base.resolve("unopened")))
        }
    }

    private fun summary(id: String, state: String = "READY") = ProjectSummary(id, id, 1_000, 3, state, 1, false)

    private fun files(base: Path, id: String, revision: Long = 3, maxFiles: Int = 100): Pair<CacheInventory, CleanupPlan> {
        val directory = Files.createDirectories(base.resolve(id))
        val original = write(directory, "original.image", "original")
        val cache = write(directory, "feature.bin", "cache")
        val manifest = CacheArtifactManifest(1, "manifest-$id", id, revision, "synthetic-global-fixture",
            listOf(descriptor("original.image", CacheArtifactCategory.ORIGINAL, CacheArtifactState.CURRENT, original),
                descriptor("feature.bin", CacheArtifactCategory.FEATURE_CACHE, CacheArtifactState.STALE, cache, "feature-$id")))
        val inventory = CacheInventoryBuilder(maxFiles = maxFiles).build(directory, manifest,
            CacheProtectionSnapshot(id, 1), NOW)
        return inventory to planner().plan(inventory, NOW)
    }
}
