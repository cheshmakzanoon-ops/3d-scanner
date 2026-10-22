package com.realityforge.scanner.agents.a019

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.library.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the actual Room transactions and real private copies when device CI is available. */
@RunWith(AndroidJUnit4::class)
class RoomProjectLibraryDeviceTest {
    @Test fun independentCopyPublishesAtomicallyAndSourceProvenanceSurvivesDatabaseReopenAndSourceDeletion() = runBlocking {
        withFixture { database, root, name ->
            val source = insertSource(database, root)
            val port = RoomProjectLibraryPort(database, root)
            val invisible = UUID.randomUUID().toString()
            assertTrue(port.reserveDuplicate(source.id, source.revision, invisible, "invisible"))
            assertNull(database.dao().project(invisible))
            assertFalse(port.summaries(LibraryQuery()).any { it.id == invisible })
            // An invisible reservation may not publish rows that have no verified original copy.
            val missingFiles = database.dao().photos(source.id).map { it.copy(id = UUID.randomUUID().toString(),
                projectId = invisible, captureSessionId = null, captureSequence = null) }
            val acceptedMissing = runCatching { port.publishDuplicate(invisible, source.id, source.revision, missingFiles) }
            assertFalse(acceptedMissing.getOrDefault(false))
            assertNull(database.dao().project(invisible))
            port.abortDuplicate(invisible)
            port.recoverAbandoned()
            val result = ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0).duplicate(source.id, source.revision, "Independent")
            val originals = database.dao().photos(source.id)
            val copied = database.dao().photos(result)
            val duplicate = requireNotNull(database.dao().project(result))
            assertNull(duplicate.resultKey)
            assertNull(duplicate.resultRevision)
            assertFalse(port.summaries(LibraryQuery()).single { it.id == result }.hasCurrentResult)
            assertEquals(originals.map { it.sha256 }, copied.map { it.sha256 })
            assertTrue(copied.all { it.captureSessionId == null && it.captureSequence == null })
            assertTrue(copied.none { copy -> originals.any { original -> original.id == copy.id } })
            val reservation = requireNotNull(database.libraryDao().reservation(result))
            assertEquals("PUBLISHED", reservation.phase)
            val provenance = JSONObject(requireNotNull(reservation.photoProvenanceJson))
            assertEquals(source.id, provenance.getString("sourceProjectId"))
            assertEquals("PRESERVED_FROM_ORIGINAL", provenance.getString("photoMetadataPolicy"))
            assertEquals("NOT_COPIED_REQUIRES_REVALIDATION", provenance.getString("projectCalibrationPolicy"))
            assertEquals("NOT_REUSED_NEW_PROJECT_IDENTITY", provenance.getString("derivedResultPolicy"))
            assertFalse(File(File(root, result), "camera-calibration-v1.json").exists())
            assertEquals(originals.size, provenance.getJSONArray("photos").length())
            database.dao().deleteProject(source.id)
            File(root, source.id).deleteRecursively()
            database.close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reopened = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            try {
                assertEquals("PUBLISHED", reopened.libraryDao().reservation(result)?.phase)
                assertEquals(copied.map { it.sha256 }, copied.map {
                    hash(File(File(File(root, result), "originals"), it.filename))
                })
                assertEquals(copied.size, reopened.dao().photos(result).size)
            } finally { reopened.close() }
        }
    }

    @Test fun publicationRejectsOriginalIdentitiesThatRepositoryCannotReopen() = runBlocking {
        withFixture { database, root, _ ->
            val source = insertSource(database, root)
            val port = RoomProjectLibraryPort(database, root)
            val sourcePhotos = database.dao().photos(source.id)
            for (invalidId in listOf(false, true)) {
                val target = UUID.randomUUID().toString()
                assertTrue(port.reserveDuplicate(source.id, source.revision, target, "invalid identity"))
                val originals = File(File(root, target), "originals").apply { mkdirs() }
                val proposal = sourcePhotos.mapIndexed { index, photo ->
                    val id = if (invalidId) "not-a-uuid-$index" else UUID.randomUUID().toString()
                    val filename = if (invalidId) "$id.image" else "mismatched-$index.image"
                    File(File(File(root, source.id), "originals"), photo.filename)
                        .copyTo(File(originals, filename))
                    photo.copy(id = id, projectId = target, filename = filename,
                        captureSessionId = null, captureSequence = null)
                }
                assertFalse(runCatching {
                    port.publishDuplicate(target, source.id, source.revision, proposal)
                }.getOrDefault(false))
                assertNull(database.dao().project(target))
                assertTrue(database.dao().photos(target).isEmpty())
                assertEquals("STAGED", database.libraryDao().reservation(target)?.phase)
            }
            assertEquals(2, port.recoverAbandoned())
            assertEquals(sourcePhotos, database.dao().photos(source.id))
        }
    }

    @Test fun unicodeSearchSortPaginationAndStaleCommandsAgreeWithCanonicalQueryContract() = runBlocking {
        withFixture { database, root, _ ->
            val names = listOf("ÉCLAIR", "éclair", "İstanbul", "Istanbul", "ΩΜΕΓΑ", "ωμέγα", "😀 scan", "\uFF21 scan")
            val projects = names.mapIndexed { index, name -> ScanProject(UUID.randomUUID().toString(), name,
                1_000L + index, revision = 7, state = "READY").also { database.dao().insert(it) } }
            val port = RoomProjectLibraryPort(database, root)
            val summaries = projects.map { ProjectSummary(it.id, it.name, it.createdAt, it.revision, it.state, 0, false) }
            for (order in LibraryOrder.entries) {
                val query = LibraryQuery(order = order, limit = 3, offset = 2)
                assertEquals(LibrarySelection.page(summaries, query), port.summaries(query))
            }
            for (search in listOf("éClAiR", "İSTANBUL", "istanbul", "ΩΜΕ")) {
                val query = LibraryQuery(search = search, order = LibraryOrder.NAME_ASC)
                assertEquals(LibrarySelection.page(summaries, query), port.summaries(query))
            }
            val source = projects.first()
            assertFalse(port.rename(source.id, source.revision - 1, "stale"))
            assertFalse(port.reserveDuplicate(source.id, source.revision - 1, UUID.randomUUID().toString(), "stale"))
            assertTrue(port.rename(source.id, source.revision, "ΝΕΟ Όνομα"))
            assertEquals(source.id, port.summaries(LibraryQuery(search = "νεο")).single().id)
            assertEquals(source.revision, database.dao().project(source.id)?.revision)
        }
    }

    @Test fun abandonedReservationRecoveryOwnsOnlyItsBoundedStagingAndPreservesUnknownEntries() = runBlocking {
        withFixture { database, root, _ ->
            val source = insertSource(database, root)
            val port = RoomProjectLibraryPort(database, root)
            val abandoned = UUID.randomUUID().toString()
            assertTrue(port.reserveDuplicate(source.id, source.revision, abandoned, "abandoned"))
            val originals = File(File(root, abandoned), "originals").apply { mkdirs() }
            File(originals, "partial-copy.image").writeBytes(byteArrayOf(1, 2, 3))
            val unrelated = File(root, "unrelated-marker").apply { writeText("retain me") }
            assertEquals(1, port.recoverAbandoned())
            assertFalse(File(root, abandoned).exists())
            assertEquals("retain me", unrelated.readText())
            assertEquals(0, port.recoverAbandoned())
            val suspicious = UUID.randomUUID().toString()
            assertTrue(port.reserveDuplicate(source.id, source.revision, suspicious, "unexpected"))
            val folder = File(root, suspicious).apply { mkdirs() }
            val unknown = File(folder, "unknown.bin").apply { writeText("unrecognized") }
            assertTrue(runCatching { port.recoverAbandoned() }.isFailure)
            assertEquals("unrecognized", unknown.readText())
            assertEquals("retain me", unrelated.readText())
            assertNotNull(database.dao().project(source.id))
        }
    }

    private suspend fun insertSource(database: ScanDatabase, root: File): ScanProject {
        val project = ScanProject(UUID.randomUUID().toString(), "Source", 1_000, revision = 7,
            resultRevision = 7, resultKey = "source-engine-specific-result")
        database.dao().insert(project)
        val originals = File(File(root, project.id), "originals").apply { mkdirs() }
        for (index in 0..1) {
            val file = File(originals, "original-$index.image").apply { writeBytes(ByteArray(1024 + index) { (it + index * 43).toByte() }) }
            database.dao().insert(ScanPhoto(UUID.randomUUID().toString(), project.id, file.name, hash(file), file.length(),
                32, 32, null, 1.0, 0.0, 0.0, 1_000L + index, selectionOrder = index.toLong()))
        }
        return project
    }

    private suspend fun withFixture(block: suspend (ScanDatabase, File, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "library-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "library-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        try { block(database, root, name) }
        finally { database.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
