package com.realityforge.scanner.data.library

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Pure, deterministic library selection. Database owners may implement these predicates in SQL. */
enum class LibraryOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }
data class LibraryQuery(val search: String = "", val states: Set<String> = emptySet(),
    val order: LibraryOrder = LibraryOrder.NEWEST, val offset: Int = 0, val limit: Int = 50) {
    init { require(offset >= 0 && limit in 1..100) { "Library page must contain 1..100 records." } }
}
data class ProjectSummary(val id: String, val name: String, val createdAt: Long, val revision: Long,
    val state: String, val photoCount: Int, val hasCurrentResult: Boolean)

object LibrarySelection {
    fun validatedName(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 80 &&
            trimmed.none { Character.isISOControl(it) }) { "Name must contain 1..80 Unicode characters without controls." }
        return trimmed
    }
    fun page(projects: List<ProjectSummary>, query: LibraryQuery): List<ProjectSummary> {
        val search = query.search.trim().lowercase(java.util.Locale.ROOT)
        val filtered = projects.asSequence().filter {
            (query.states.isEmpty() || it.state in query.states) &&
                (search.isEmpty() || it.name.lowercase(java.util.Locale.ROOT).contains(search))
        }.toList()
        val sorted = when (query.order) {
            LibraryOrder.NEWEST -> filtered.sortedWith(compareByDescending<ProjectSummary> { it.createdAt }.thenBy { it.id })
            LibraryOrder.OLDEST -> filtered.sortedWith(compareBy<ProjectSummary> { it.createdAt }.thenBy { it.id })
            LibraryOrder.NAME_ASC -> filtered.sortedWith(compareBy<ProjectSummary> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
            LibraryOrder.NAME_DESC -> filtered.sortedWith(compareByDescending<ProjectSummary> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
        }
        return sorted.drop(query.offset).take(query.limit)
    }
}

/**
 * Persistence boundary assigned to Agent 005/006. Each mutation MUST compare revision atomically.
 * reserveDuplicate allocates a NEW project ID but does not expose a completed/visible project.
 * publishDuplicate atomically installs the fully copied photo rows and a READY project; derived
 * results are intentionally not carried forward because their identity contains the source ID.
 */
interface ProjectLibraryPort {
    suspend fun summaries(query: LibraryQuery): List<ProjectSummary>
    suspend fun rename(id: String, expectedRevision: Long, name: String): Boolean
    suspend fun snapshot(id: String): Pair<ScanProject, List<ScanPhoto>>?
    suspend fun reserveDuplicate(sourceId: String, sourceRevision: Long, newId: String, name: String): Boolean
    suspend fun publishDuplicate(newId: String, sourceId: String, sourceRevision: Long,
        photos: List<ScanPhoto>): Boolean
    suspend fun abortDuplicate(newId: String)
}

sealed class DuplicateEvent {
    data class Copying(val copiedBytes: Long, val totalBytes: Long) : DuplicateEvent()
    data class Completed(val projectId: String) : DuplicateEvent()
}
class InsufficientDuplicateStorage : IllegalStateException("Insufficient space for independent original copies.")
class ProjectLibraryService(private val port: ProjectLibraryPort, private val projectRoot: File) {
    suspend fun query(request: LibraryQuery): List<ProjectSummary> = withContext(Dispatchers.IO) {
        port.summaries(request)
    }

    suspend fun rename(id: String, expectedRevision: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        requireUuid(id)
        require(expectedRevision >= 0)
        port.rename(id, expectedRevision, LibrarySelection.validatedName(name))
    }

    /**
     * Fail-closed duplication: all originals are copied and SHA-256 verified before publication.
     * A cancelled or disk-full copy does not install DB records. Only independent paths and IDs
     * are published; completed geometry is NOT reused without a new artifact-identity contract.
     * A persistence adapter must journal reservation/publication and recover abandoned staging.
     */
    suspend fun duplicate(id: String, expectedRevision: Long, name: String,
        onProgress: suspend (DuplicateEvent.Copying) -> Unit = {}): String = withContext(Dispatchers.IO) {
        requireUuid(id)
        val newName = LibrarySelection.validatedName(name)
        val (source, photos) = requireNotNull(port.snapshot(id)) { "Source project not found." }
        check(source.revision == expectedRevision) { "Source project changed." }
        require(photos.size <= 250) { "Duplicate exceeds supported photo count." }
        val total = photos.sumOf { require(it.bytes in 1..150L * 1024 * 1024); it.bytes }
        val newId = UUID.randomUUID().toString()
        val folder = File(projectRoot, newId)
        check(!folder.exists()) { "Destination already exists." }
        // Reserve at least 512 MiB headroom to avoid knowingly exhausting application storage.
        if (projectRoot.usableSpace < total + 512L * 1024 * 1024) throw InsufficientDuplicateStorage()
        check(port.reserveDuplicate(id, expectedRevision, newId, newName)) { "Source changed during reservation." }
        var published = false
        try {
            val originals = File(folder, "originals")
            check(originals.mkdirs()) { "Cannot stage duplicate originals." }
            val records = mutableListOf<ScanPhoto>()
            var copied = 0L
            for (photo in photos) {
                coroutineContext.ensureActive()
                val filename = UUID.randomUUID().toString() + ".image"
                val destination = File(originals, filename)
                val sourceFile = File(File(projectRoot, id), "originals/${photo.filename}")
                check(sourceFile.isFile && sourceFile.length() == photo.bytes) { "Source original missing or changed." }
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                sourceFile.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            length += count
                            check(length <= photo.bytes) { "Source original grew during duplication." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            onProgress(DuplicateEvent.Copying(copied, total))
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(length == photo.bytes && hash == photo.sha256) { "Source original hash mismatch." }
                records += photo.copy(id = UUID.randomUUID().toString(), projectId = newId, filename = filename)
            }
            coroutineContext.ensureActive()
            // Publication must be atomic with respect to visible DB state.
            check(port.publishDuplicate(newId, id, expectedRevision, records)) { "Source changed before publication." }
            published = true
            newId
        } finally {
            if (!published) {
                // Recovery must also handle a process killed before finally executes.
                try { port.abortDuplicate(newId) } finally { folder.deleteRecursively() }
            }
        }
    }
    private fun requireUuid(id: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Invalid project identifier."
        }
    }
}
