package com.realityforge.scanner.data.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.Locale
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Kept after publication so an independent copy retains its source provenance. */
@Entity(tableName = "project_duplicate_reservations")
data class ProjectDuplicateReservation(
    @PrimaryKey val newId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val name: String,
    val createdAt: Long,
    val phase: String,
    val photoProvenanceJson: String? = null,
)

@Dao
interface ProjectLibraryDao {
    @Query("""SELECT p.id, p.name, p.createdAt, p.revision, p.state,
        (SELECT COUNT(*) FROM photos WHERE projectId=p.id) AS photoCount,
        (p.resultKey IS NOT NULL AND p.resultRevision=p.revision) AS hasCurrentResult
        FROM projects p
        WHERE instr(p.libraryNameKey, :search) > 0
          AND (:allStates OR p.state IN (:states))
          AND p.state NOT IN ('DELETING', 'DELETED', 'DELETING_PHOTO')
        ORDER BY
          CASE WHEN :order='NEWEST' THEN p.createdAt END DESC,
          CASE WHEN :order='OLDEST' THEN p.createdAt END ASC,
          CASE WHEN :order='NAME_ASC' THEN p.libraryNameKey END ASC,
          CASE WHEN :order='NAME_DESC' THEN p.libraryNameKey END DESC,
          p.id ASC
        LIMIT :limit OFFSET :offset""")
    suspend fun summaries(search: String, allStates: Boolean, states: List<String>, order: String,
        limit: Int, offset: Int): List<ProjectSummary>

    @Query("""UPDATE projects SET name=:name, libraryNameKey=:nameKey WHERE id=:id AND revision=:revision
        AND state NOT IN ('DELETING','DELETED','DELETING_PHOTO')""")
    suspend fun renameExpected(id: String, revision: Long, name: String, nameKey: String): Int

    @Insert
    suspend fun reserve(value: ProjectDuplicateReservation)

    @Query("SELECT * FROM project_duplicate_reservations WHERE newId=:id")
    suspend fun reservation(id: String): ProjectDuplicateReservation?

    @Query("""UPDATE project_duplicate_reservations SET phase='PUBLISHED',photoProvenanceJson=:provenance
        WHERE newId=:id AND phase='STAGED'""")
    suspend fun published(id: String, provenance: String): Int

    @Query("UPDATE project_duplicate_reservations SET phase='ABORTED' WHERE newId=:id AND phase='STAGED'")
    suspend fun aborted(id: String): Int

    @Query("""SELECT * FROM project_duplicate_reservations WHERE phase IN ('STAGED','ABORTED')
        ORDER BY createdAt,newId LIMIT 100""")
    suspend fun abandoned(): List<ProjectDuplicateReservation>

    @Query("DELETE FROM project_duplicate_reservations WHERE newId=:id AND phase='ABORTED'")
    suspend fun forgetAborted(id: String): Int
}

/** Call mutations while holding the repository's LIBRARY_COMMAND project reservation. */
class RoomProjectLibraryPort(
    private val database: ScanDatabase,
    private val projectRoot: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ProjectLibraryPort {
    private val library get() = database.libraryDao()

    override suspend fun summaries(query: LibraryQuery): List<ProjectSummary> = library.summaries(
        query.search.trim().lowercase(Locale.ROOT), query.states.isEmpty(), query.states.toList().ifEmpty { listOf("") },
        query.order.name, query.limit, query.offset,
    )

    override suspend fun rename(id: String, expectedRevision: Long, name: String): Boolean =
        database.withTransaction {
            val validated = LibrarySelection.validatedName(name)
            library.renameExpected(id, expectedRevision, validated, validated.lowercase(Locale.ROOT)) == 1
        }

    override suspend fun snapshot(id: String): Pair<ScanProject, List<ScanPhoto>>? = database.withTransaction {
        val source = database.dao().project(id) ?: return@withTransaction null
        if (source.state in setOf("DELETING", "DELETED", "DELETING_PHOTO")) return@withTransaction null
        source to database.dao().photos(id)
    }

    override suspend fun reserveDuplicate(sourceId: String, sourceRevision: Long, newId: String,
        name: String): Boolean = database.withTransaction {
        requireUuid(newId)
        val source = database.dao().project(sourceId) ?: return@withTransaction false
        if (source.revision != sourceRevision || source.state in setOf("DELETING", "DELETED", "DELETING_PHOTO") ||
            database.dao().project(newId) != null || library.reservation(newId) != null) return@withTransaction false
        library.reserve(ProjectDuplicateReservation(newId, sourceId, sourceRevision,
            LibrarySelection.validatedName(name), clockMillis(), "STAGED"))
        true
    }

    override suspend fun publishDuplicate(newId: String, sourceId: String, sourceRevision: Long,
        photos: List<ScanPhoto>): Boolean = withContext(Dispatchers.IO) {
      if (!validateStagedOriginals(newId, photos)) return@withContext false
      database.withTransaction {
        val reservation = library.reservation(newId) ?: return@withTransaction false
        if (reservation.sourceId != sourceId || reservation.sourceRevision != sourceRevision ||
            reservation.phase != "STAGED" || database.dao().project(newId) != null) return@withTransaction false
        val source = database.dao().project(sourceId) ?: return@withTransaction false
        if (source.revision != sourceRevision || source.state in setOf("DELETING", "DELETED", "DELETING_PHOTO")) return@withTransaction false
        val originals = database.dao().photos(sourceId)
        if (photos.size != originals.size || photos.size > 250) return@withTransaction false
        val byHash = originals.associateBy { it.sha256 }
        require(photos.map { it.sha256 }.toSet() == byHash.keys && photos.map { it.id }.toSet().size == photos.size)
        val provenance = JSONArray()
        photos.forEach { photo ->
            val original = requireNotNull(byHash[photo.sha256])
            require(photo.projectId == newId && photo.id != original.id && photo.captureSessionId == null &&
                photo.captureSequence == null && photo.bytes == original.bytes)
            provenance.put(JSONObject().put("sourcePhotoId", original.id).put("photoId", photo.id)
                .put("sha256", photo.sha256))
        }
        database.dao().restoreArchiveProject(ScanProject(newId, reservation.name, reservation.createdAt,
            revision = if (photos.isEmpty()) 0 else 1,
            state = if (photos.isEmpty()) "NEW" else "READY",
            message = "Independent copy of ${source.name}; originals and selection preserved."), photos)
        check(library.published(newId, JSONObject().put("schemaVersion", 1)
            .put("sourceProjectId", sourceId).put("sourceRevision", sourceRevision)
            .put("photos", provenance).toString()) == 1)
        true
      }
    }

    private suspend fun validateStagedOriginals(newId: String, photos: List<ScanPhoto>): Boolean {
        require(photos.size <= 250 && photos.map { it.filename }.distinct().size == photos.size)
        val originals = File(ownedFolder(newId), "originals")
        if (!Files.isDirectory(originals.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            originals.canonicalFile.parentFile != ownedFolder(newId)) return false
        return try {
            for (photo in photos) {
                coroutineContext.ensureActive()
                require(photo.projectId == newId && photo.bytes in 1..150L * 1024 * 1024 &&
                    photo.filename.matches(Regex("[a-zA-Z0-9_-]+\\.image")))
                val file = File(originals, photo.filename)
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    file.canonicalFile.parentFile != originals || file.length() != photo.bytes) return false
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { stream ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) error("Staged original stream stopped making progress.")
                        bytes = Math.addExact(bytes, count.toLong())
                        if (bytes > photo.bytes) return false
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (bytes != photo.bytes || hash != photo.sha256) return false
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { false }
    }

    override suspend fun abortDuplicate(newId: String) = database.withTransaction {
        // An ambiguous successful transaction must never authorize removal of committed originals.
        check(database.dao().project(newId) == null) { "Duplicate is already published; originals retained." }
        val record = library.reservation(newId)
        check(record != null && record.phase != "PUBLISHED") { "Duplicate reservation cannot be safely aborted." }
        library.aborted(newId)
        Unit
    }

    /** Run once during startup recovery, before accepting any new duplication command. */
    suspend fun recoverAbandoned(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        while (true) {
            val batch = library.abandoned()
            if (batch.isEmpty()) break
            for (record in batch) {
                abortDuplicate(record.newId)
                val folder = ownedFolder(record.newId)
                if (folder.exists()) {
                    // Staging has a bounded shape: one originals directory and at most 250 files.
                    // Fail closed on unexpected entries; never follow symlinks during recovery.
                    val entries = Files.newDirectoryStream(folder.toPath()).use { paths ->
                        paths.asSequence().take(2).map { it.toFile() }.toList()
                    }
                    check(entries.size <= 1 && entries.all { it.name == "originals" &&
                        Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
                    entries.forEach { originals ->
                        val files = Files.newDirectoryStream(originals.toPath()).use { paths ->
                            paths.asSequence().take(251).map { it.toFile() }.toList()
                        }
                        check(files.size <= 250 && files.all {
                            it.name.matches(Regex("[a-zA-Z0-9_-]+\\.image")) &&
                                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
                        }) { "Unexpected duplicate staging contents; retained for diagnosis." }
                        files.forEach { check(it.delete()) { "Cannot remove abandoned duplicate original." } }
                        check(originals.delete())
                    }
                    check(folder.delete())
                }
                check(library.forgetAborted(record.newId) == 1)
                recovered++
            }
        }
        recovered
    }

    private fun ownedFolder(id: String): File {
        requireUuid(id)
        check(!Files.isSymbolicLink(projectRoot.toPath()))
        val root = projectRoot.canonicalFile
        return File(root, id).also {
            check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root)
        }
    }

    private fun requireUuid(id: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
    }
}
