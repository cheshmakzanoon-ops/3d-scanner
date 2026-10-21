package com.realityforge.scanner.data.library

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.CuratedPhotoState
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.curation.RestorablePhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.coroutineContext

/** Pure, deterministic library selection. Database owners may implement these predicates in SQL. */
enum class LibraryOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }
data class LibraryQuery(val search: String = "", val states: Set<String> = emptySet(),
    val order: LibraryOrder = LibraryOrder.NEWEST, val offset: Int = 0, val limit: Int = 50) {
    init {
        require(offset >= 0 && limit in 1..100) { "Library page must contain 1..100 records." }
        require(search.length <= 512 && states.size <= 32) { "Library query exceeds its bounded size." }
    }
}
data class ProjectSummary(val id: String, val name: String, val createdAt: Long, val revision: Long,
    val state: String, val photoCount: Int, val hasCurrentResult: Boolean)

object LibrarySelection {
    fun validatedName(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 80 &&
            trimmed.none { Character.isISOControl(it) } && Charsets.UTF_8.newEncoder().canEncode(trimmed)) {
            "Name must contain 1..80 Unicode characters without controls."
        }
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
            LibraryOrder.NAME_ASC -> filtered.sortedWith(nameComparator(descending = false))
            LibraryOrder.NAME_DESC -> filtered.sortedWith(nameComparator(descending = true))
        }
        return sorted.drop(query.offset).take(query.limit)
    }

    // Unicode code-point order agrees with SQLite BINARY over the stored UTF-8 lowercase key.
    // Java's default UTF-16 order differs for supplementary characters after BMP surrogate range.
    private fun nameComparator(descending: Boolean) = Comparator<ProjectSummary> { first, second ->
        val a = first.name.lowercase(java.util.Locale.ROOT)
        val b = second.name.lowercase(java.util.Locale.ROOT)
        var left = 0
        var right = 0
        var comparison = 0
        while (left < a.length && right < b.length && comparison == 0) {
            val firstCode = a.codePointAt(left)
            val secondCode = b.codePointAt(right)
            comparison = firstCode.compareTo(secondCode)
            left += Character.charCount(firstCode)
            right += Character.charCount(secondCode)
        }
        if (comparison == 0) comparison = (a.length - left).compareTo(b.length - right)
        if (comparison == 0) first.id.compareTo(second.id) else if (descending) -comparison else comparison
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
class ProjectLibraryService(private val port: ProjectLibraryPort, private val projectRoot: File,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val freeSpaceHeadroomBytes: Long = 512L * 1024 * 1024) {
    init { require(freeSpaceHeadroomBytes >= 0) }
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
        require(expectedRevision >= 0)
        check(projectRoot.isDirectory && !Files.isSymbolicLink(projectRoot.toPath())) { "Invalid project storage root." }
        val canonicalRoot = projectRoot.canonicalFile
        val newName = LibrarySelection.validatedName(name)
        val (source, photos) = requireNotNull(port.snapshot(id)) { "Source project not found." }
        check(source.id == id && source.revision == expectedRevision) { "Source project changed." }
        require(photos.size <= 250) { "Duplicate exceeds supported photo count." }
        val total = photos.fold(0L) { size, photo ->
            require(photo.projectId == id) { "Photograph belongs to a different project." }
            require(photo.bytes in 1..150L * 1024 * 1024) { "Invalid original size." }
            require(photo.filename.matches(Regex("[a-zA-Z0-9_-]+\\.image"))) { "Unsafe original filename." }
            Math.addExact(size, photo.bytes)
        }
        val newId = UUID.randomUUID().toString()
        val folder = File(canonicalRoot, newId)
        check(!folder.exists()) { "Destination already exists." }
        // Reserve at least 512 MiB headroom to avoid knowingly exhausting application storage.
        if (availableBytes(projectRoot) < Math.addExact(total, freeSpaceHeadroomBytes)) throw InsufficientDuplicateStorage()
        var reserved = false
        var published = false
        try {
            withContext(NonCancellable) {
                check(port.reserveDuplicate(id, expectedRevision, newId, newName)) { "Source changed during reservation." }
                reserved = true
            }
            coroutineContext.ensureActive()
            val originals = File(folder, "originals")
            check(originals.mkdirs()) { "Cannot stage duplicate originals." }
            val records = mutableListOf<ScanPhoto>()
            var copied = 0L
            val progressStep = maxOf(65_536L, total / 256)
            var nextProgress = progressStep
            onProgress(DuplicateEvent.Copying(0, total))
            for ((position, photo) in photos.withIndex()) {
                coroutineContext.ensureActive()
                val newPhotoId = UUID.randomUUID().toString()
                val filename = "$newPhotoId.image"
                val destination = File(originals, filename)
                val sourceDirectory = File(File(canonicalRoot, id), "originals")
                val sourceFile = File(sourceDirectory, photo.filename)
                check(sourceFile.canonicalFile.parentFile == sourceDirectory.absoluteFile &&
                    sourceDirectory.canonicalFile.parentFile == File(canonicalRoot, id) &&
                    Files.isRegularFile(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    sourceFile.length() == photo.bytes) { "Source original missing, changed or outside its project." }
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                Files.newInputStream(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(count > 0) { "Original stream made no progress." }
                            length += count
                            check(length <= photo.bytes) { "Source original grew during duplication." }
                            if (availableBytes(projectRoot) < Math.addExact(freeSpaceHeadroomBytes, count.toLong())) {
                                throw InsufficientDuplicateStorage()
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            if (copied >= nextProgress || copied == total) {
                                onProgress(DuplicateEvent.Copying(copied, total))
                                nextProgress = copied + progressStep
                            }
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(length == photo.bytes && hash == photo.sha256) { "Source original hash mismatch." }
                val curation = photo.curationRecord?.let { record ->
                    val old = PhotoCurationRecordCodec.decode(record)
                    check(old.id == photo.id && old.sha256 == photo.sha256 && old.position == position &&
                        (old.state == CuratedPhotoState.INCLUDED) == photo.included) {
                        "Source photo curation is inconsistent."
                    }
                    val pending = old.pendingRemoval
                    // A new independent project does not inherit permission to delete a photo.
                    val restored = if (pending == null) old else old.copy(
                        state = if (pending.previousState == RestorablePhotoState.INCLUDED)
                            CuratedPhotoState.INCLUDED else CuratedPhotoState.EXCLUDED,
                        exclusion = pending.previousExclusion, pendingRemoval = null)
                    restored.copy(id = newPhotoId, position = position)
                }
                records += photo.copy(id = newPhotoId, projectId = newId, filename = filename,
                    included = curation?.let { it.state == CuratedPhotoState.INCLUDED } ?: photo.included,
                    selectionOrder = position.toLong(), captureSessionId = null, captureSequence = null,
                    curationRecord = curation?.let(PhotoCurationRecordCodec::encode))
            }
            coroutineContext.ensureActive()
            // Publication must be atomic with respect to visible DB state.
            withContext(NonCancellable) {
                check(port.publishDuplicate(newId, id, expectedRevision, records)) { "Source changed before publication." }
                published = true
            }
            newId
        } finally {
            if (reserved && !published) {
                // Recovery must also handle a process killed before finally executes.
                withContext(NonCancellable) {
                    // Let the journal owner confirm an uncommitted reservation before removing
                    // files. A failed/uncertain recovery remains for startup reconciliation.
                    port.abortDuplicate(newId)
                    discardUnpublishedFolder(folder)
                }
            }
        }
    }

    private fun discardUnpublishedFolder(folder: File) {
        if (!folder.exists()) return
        check(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile == projectRoot.canonicalFile)
        val entries = Files.newDirectoryStream(folder.toPath()).use { it.asSequence().take(2).toList() }
        check(entries.size <= 1 && entries.all {
            it.fileName.toString() == "originals" && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
        }) { "Unknown duplicate staging entries require recovery." }
        entries.forEach { originals ->
            val files = Files.newDirectoryStream(originals).use { it.asSequence().take(251).toList() }
            check(files.size <= 250 && files.all {
                it.fileName.toString().matches(Regex("[a-zA-Z0-9_-]+\\.image")) &&
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
            }) { "Unknown duplicate staging files require recovery." }
            files.forEach { Files.delete(it) }
            Files.delete(originals)
        }
        Files.delete(folder.toPath())
    }
    private fun requireUuid(id: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Invalid project identifier."
        }
    }
}
