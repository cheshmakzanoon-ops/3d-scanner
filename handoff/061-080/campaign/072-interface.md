package com.realityforge.scanner.ui.library

import com.realityforge.scanner.data.ScanProject
import java.util.Locale

enum class LibrarySort { NEWEST, OLDEST, NAME }
enum class LibraryFilter { ALL, RESULTS, NEEDS_ATTENTION }
data class LibraryProject(val id: String, val name: String, val createdAt: Long, val revision: Long,
    val state: String, val message: String, val hasCurrentResult: Boolean, val photoCount: Int? = null,
    val representation: String? = null) {
    companion object {
        fun from(project: ScanProject) = LibraryProject(project.id, project.name, project.createdAt,
            project.revision, project.state, project.message,
            project.resultKey != null && project.resultRevision == project.revision)
    }
}
data class LibraryUiState(val projects: List<LibraryProject>, val loading: Boolean = false,
    val error: String? = null, val busy: Boolean = false, val canRename: Boolean = false,
    val canDuplicate: Boolean = false, val canRestoreArchive: Boolean = false)
sealed interface LibraryAction {
    data class Create(val name: String) : LibraryAction
    data class Open(val projectId: String) : LibraryAction
    data class Rename(val projectId: String, val revision: Long, val name: String) : LibraryAction
    data class Duplicate(val projectId: String, val revision: Long) : LibraryAction
    data class Delete(val projectId: String, val revision: Long) : LibraryAction
    data object RestoreArchive : LibraryAction
    data object Retry : LibraryAction
}
object LibraryQuery {
    fun validName(value: String): Boolean = value.trim().isNotEmpty() && value.length <= 80 && value.none { it.isISOControl() }
    fun apply(projects: List<LibraryProject>, query: String, sort: LibrarySort, filter: LibraryFilter): List<LibraryProject> {
        val normalized = query.trim().take(200).lowercase(Locale.ROOT)
        val filtered = projects.filter { project ->
            project.name.lowercase(Locale.ROOT).contains(normalized) && when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.RESULTS -> project.hasCurrentResult
                LibraryFilter.NEEDS_ATTENTION -> project.state in setOf("INTERRUPTED", "FAILED", "ERROR")
            }
        }
        return when (sort) {
            LibrarySort.NEWEST -> filtered.sortedWith(compareByDescending<LibraryProject> { it.createdAt }.thenBy { it.id })
            LibrarySort.OLDEST -> filtered.sortedWith(compareBy<LibraryProject> { it.createdAt }.thenBy { it.id })
            LibrarySort.NAME -> filtered.sortedWith(compareBy<LibraryProject> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
        }
    }
}
