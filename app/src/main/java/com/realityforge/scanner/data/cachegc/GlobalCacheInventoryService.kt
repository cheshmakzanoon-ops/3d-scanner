package com.realityforge.scanner.data.cachegc

import com.realityforge.scanner.data.library.LibraryOrder
import com.realityforge.scanner.data.library.LibraryQuery
import com.realityforge.scanner.data.library.ProjectSummary
import com.realityforge.scanner.data.operations.ProjectOperationConflictException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ProjectStorageMeasurement { PENDING, MEASURED, PARTIAL, BUSY, CHANGED, UNAVAILABLE }

/** Compact summary only. Per-file inventories and cleanup capabilities are never retained here. */
data class GlobalProjectStorage(
    val project: ProjectSummary,
    val measurement: ProjectStorageMeasurement = ProjectStorageMeasurement.PENDING,
    val measuredBytes: Long? = null,
    val bytesByCategory: Map<CacheArtifactCategory, Long> = emptyMap(),
    val reclaimableBytes: Long? = null,
    val issueCount: Int = 0,
)

data class GlobalStoragePage(
    val offset: Int,
    val pageSize: Int,
    val hasNextPage: Boolean,
    val projects: List<GlobalProjectStorage>,
) {
    val measuredProjectCount: Int get() = projects.count { it.measuredBytes != null }
    val completeProjectCount: Int get() = projects.count { it.measurement == ProjectStorageMeasurement.MEASURED }
    val finishedProjectCount: Int get() = projects.count { it.measurement != ProjectStorageMeasurement.PENDING }
    val measuredSubtotalBytes: Long? get() = sumMeasured { it.measuredBytes }
    val reclaimableSubtotalBytes: Long? get() = sumMeasured { it.reclaimableBytes }
    fun measuredCategoryBytes(category: CacheArtifactCategory): Long? = sumMeasured {
        if (it.measuredBytes == null) null else it.bytesByCategory[category] ?: 0L
    }

    private fun sumMeasured(value: (GlobalProjectStorage) -> Long?): Long? {
        if (projects.isEmpty()) return 0
        val known = projects.mapNotNull(value)
        if (known.isEmpty()) return null
        return try { known.fold(0L, Math::addExact) } catch (_: ArithmeticException) { null }
    }
}

/**
 * Read-only global library navigation, bounded to one small page and one project inventory at a
 * time. Each project is measured through the actual C6/007/009/023-aware project service. Totals
 * describe measured files on this page; busy, changed or unreadable projects are never zero bytes.
 */
class GlobalCacheInventoryService(
    private val query: suspend (LibraryQuery) -> List<ProjectSummary>,
    private val inventory: suspend (String) -> Pair<CacheInventory, CleanupPlan>,
) {
    suspend fun measurePage(offset: Int = 0, pageSize: Int = DEFAULT_PAGE_SIZE,
        onProgress: suspend (GlobalStoragePage) -> Unit = {}): GlobalStoragePage = withContext(Dispatchers.IO) {
        require(pageSize in 1..50 && offset in 0..Int.MAX_VALUE - pageSize - 1)
        coroutineContext.ensureActive()
        val candidates = query(LibraryQuery(order = LibraryOrder.NEWEST, offset = offset, limit = pageSize + 1))
        check(candidates.size <= pageSize + 1 && candidates.map { it.id }.distinct().size == candidates.size) {
            "Project library returned an invalid page."
        }
        val projects = candidates.take(pageSize).filterNot { it.state in DELETING }.map { GlobalProjectStorage(it) }.toMutableList()
        fun snapshot() = GlobalStoragePage(offset, pageSize, candidates.size > pageSize,
            Collections.unmodifiableList(projects.toList()))
        coroutineContext.ensureActive()
        onProgress(snapshot())
        for (index in projects.indices) {
            coroutineContext.ensureActive()
            val source = projects[index].project
            val measured = try {
                val (files, plan) = inventory(source.id)
                if (files.projectId != source.id || files.projectRevision != source.revision ||
                    plan.projectId != files.projectId || plan.projectRevision != files.projectRevision ||
                    plan.manifestId != files.manifestId || plan.referenceGeneration != files.referenceGeneration) {
                    GlobalProjectStorage(source, ProjectStorageMeasurement.CHANGED)
                } else {
                    val partial = files.issues.any { it.code in INCOMPLETE }
                    GlobalProjectStorage(source,
                        if (partial) ProjectStorageMeasurement.PARTIAL else ProjectStorageMeasurement.MEASURED,
                        files.totalBytes, Collections.unmodifiableMap(files.bytesByCategory.toMap()),
                        if (partial) null else plan.reclaimableBytes, files.issues.size)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ProjectOperationConflictException) { GlobalProjectStorage(source, ProjectStorageMeasurement.BUSY) }
            catch (_: Exception) { GlobalProjectStorage(source, ProjectStorageMeasurement.UNAVAILABLE) }
            coroutineContext.ensureActive()
            projects[index] = measured
            onProgress(snapshot())
        }
        snapshot()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 25
        private val DELETING = setOf("DELETING", "DELETING_PHOTO", "DELETED")
        private val INCOMPLETE = setOf(InventoryIssueCode.DIRECTORY_DEPTH_LIMIT, InventoryIssueCode.FILE_COUNT_LIMIT,
            InventoryIssueCode.BYTE_ACCOUNTING_OVERFLOW, InventoryIssueCode.READ_FAILURE)
    }
}
