package com.realityforge.scanner.hostintegration

import android.app.Application
import androidx.room.Room
import com.realityforge.scanner.capture.metadata.CaptureMetadata
import com.realityforge.scanner.capture.metadata.CaptureSource
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.importing.*
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.publication.JournaledOriginalPublicationPort
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * HOST integration: production import/journal/lease code, generated Room DAO, native SQLite.
 * Source bytes and inspector metadata are synthetic, not photographs or decoder evidence.
 * Excludes repository facade/storage budgets, provider permissions, app JNI/GPU and devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoomImportHostTest {
    @Test(timeout = 120_000) fun exactBytesDuplicateAndFixedDestinationPersistAcrossReopen() = runBlocking {
        withFixture { f ->
            val other = ProjectCreationServiceImpl(f.dao, f.paths).create("Other project")
            val result = f.import(sources = listOf("first", "first", "second"))
            assertEquals(2, result.importedCount)
            assertEquals(1, result.duplicateCount)
            assertEquals(0, result.pendingCount)
            assertEquals(2L, result.finalRevision)
            val photos = f.dao.photos(f.project)
            assertEquals(2, photos.size)
            assertTrue(f.dao.photos(other).isEmpty())
            assertEquals(listOf(sha(f.bytes("first")), sha(f.bytes("second"))), photos.map { it.sha256 })
            photos.forEachIndexed { i, photo -> assertArrayEquals(f.bytes(if (i == 0) "first" else "second"), f.paths.photoFile(photo).readBytes()) }
            f.assertClean()
            f.reopen()
            assertEquals(2L, f.dao.project(f.project)?.revision)
            assertEquals(photos, f.dao.photos(f.project))
            assertTrue(f.dao.photos(other).isEmpty())
        }
    }

    @Test(timeout = 120_000) fun publicationFaultsReconcileAgainstRealRoomAfterReopen() = runBlocking {
        for (fault in listOf("before-commit", "lost-commit-reply", "uncertain-readback")) withFixture { f ->
            val attempted = AtomicBoolean(false)
            val actual = f.dao
            val injected = object : ScanDao by actual {
                override suspend fun addPhotoExpected(photo: ScanPhoto, expectedRevision: Long): Long {
                    attempted.set(true)
                    if (fault == "lost-commit-reply") actual.addPhotoExpected(photo, expectedRevision)
                    throw IOException("Synthetic publication boundary: $fault")
                }
                override suspend fun photoByHash(projectId: String, sha256: String): ScanPhoto? {
                    if (attempted.get() && fault == "uncertain-readback") throw IOException("Synthetic unavailable readback")
                    return actual.photoByHash(projectId, sha256)
                }
            }
            val result = f.import(dao = injected)
            assertTrue(attempted.get())
            when (fault) {
                "before-commit" -> {
                    assertEquals("PUBLICATION_ABORTED", (result.photos.single().outcome as ImportPublicationResult.Rejected).code)
                    assertEquals(0, result.importedCount)
                    assertEquals(0, result.pendingCount)
                    assertTrue(actual.photos(f.project).isEmpty())
                    assertTrue(f.paths.originalsDirectory(f.project).listFiles().orEmpty().isEmpty())
                }
                "lost-commit-reply" -> {
                    assertEquals(1, result.importedCount)
                    assertEquals(0, result.pendingCount)
                    assertEquals(1L, actual.project(f.project)?.revision)
                }
                else -> {
                    assertEquals(1, result.pendingCount)
                    assertEquals(0, result.importedCount)
                    assertEquals(0, result.rejectedCount)
                    assertTrue(actual.photos(f.project).isEmpty())
                    assertEquals(1, f.journals().size)
                }
            }
            f.reopen() // New database/DAO and publication port: no injected readback survives.
            f.recover()
            val expected = if (fault == "before-commit") 0 else 1
            assertEquals(expected, f.dao.photos(f.project).size)
            assertEquals(expected.toLong(), f.dao.project(f.project)?.revision)
            f.dao.photos(f.project).forEach { photo ->
                assertEquals(sha(f.bytes("first")), photo.sha256)
                assertArrayEquals(f.bytes("first"), f.paths.photoFile(photo).readBytes())
            }
            f.assertClean()
            f.recover() // Recovery must remain idempotent.
            assertEquals(expected, f.dao.photos(f.project).size)
        }
    }

    @Test(timeout = 120_000) fun cancellationDuringStreamingLeavesNoRoomRowsOriginalsOrStaging() = runBlocking {
        withFixture { f ->
            var progress = 0
            try {
                f.import(sources = listOf("large")) {
                    progress++
                    throw CancellationException("Synthetic progress cancellation")
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(1, progress)
            f.reopen()
            f.recover()
            assertEquals(0L, f.dao.project(f.project)?.revision)
            assertTrue(f.dao.photos(f.project).isEmpty())
            assertTrue(f.paths.originalsDirectory(f.project).listFiles().orEmpty().isEmpty())
            f.assertClean()
        }
    }

    @Test(timeout = 120_000) fun damagedDuplicateWithoutJournalStopsBeforeOpeningLaterSources() = runBlocking {
        for (damage in listOf("missing", "same-size-corrupt")) withFixture { f ->
            assertEquals(1, f.import().importedCount)
            f.assertClean()
            val row = f.dao.photos(f.project).single()
            val destination = f.paths.photoFile(row)
            if (damage == "missing") check(destination.delete())
            else destination.writeBytes(ByteArray(row.bytes.toInt()))
            f.opened.clear()
            val result = f.import(sources = listOf("first", "second"))
            assertEquals(1, result.integrityFailureCount)
            assertEquals(0, result.duplicateCount)
            assertEquals(0, result.importedCount)
            assertEquals(0, result.pendingCount)
            assertEquals(1, result.rejectedCount)
            assertEquals(listOf("first"), f.opened)
            assertEquals(1L, f.dao.project(f.project)?.revision)
            assertEquals(listOf(row), f.dao.photos(f.project))
            f.assertClean()
            f.reopen()
            assertEquals(listOf(row), f.dao.photos(f.project))
            if (damage == "missing") assertFalse(destination.exists())
            else assertArrayEquals(ByteArray(row.bytes.toInt()), destination.readBytes())
        }
    }

    @Test(timeout = 120_000) fun damagedCommittedOriginalRemainsPendingAcrossReopenAndRecovery() = runBlocking {
        for (damage in listOf("missing", "same-size-corrupt")) withFixture { f ->
            val actual = f.dao
            val injected = object : ScanDao by actual {
                override suspend fun addPhotoExpected(photo: ScanPhoto, expectedRevision: Long): Long {
                    actual.addPhotoExpected(photo, expectedRevision)
                    val destination = f.paths.photoFile(photo)
                    if (damage == "missing") check(destination.delete())
                    else destination.writeBytes(ByteArray(photo.bytes.toInt()))
                    throw IOException("Synthetic lost reply after original damage")
                }
            }
            val result = f.import(dao = injected)
            assertEquals(0, result.importedCount)
            assertEquals(1, result.pendingCount)
            assertEquals(1, f.journals().size)
            f.reopen()
            assertEquals(1L, f.dao.project(f.project)?.revision)
            val row = f.dao.photos(f.project).single()
            try {
                f.recover()
                fail("Recovery must reject a damaged committed original")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("Committed original does not match"))
            }
            assertEquals(listOf(row), f.dao.photos(f.project))
            assertEquals(1, f.journals().size)
            val destination = f.paths.photoFile(row)
            if (damage == "missing") assertFalse(destination.exists())
            else assertArrayEquals(ByteArray(row.bytes.toInt()), destination.readBytes())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        var primary: Throwable? = null
        try { f.initialize(); block(f) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { f.close() } catch (cleanup: Throwable) {
                if (primary != null) { if (primary !== cleanup) primary.addSuppressed(cleanup) } else throw cleanup
            }
        }
    }

    private class Fixture {
        private val context = RuntimeEnvironment.getApplication()
        private val root = File(context.cacheDir, "import-host-${UUID.randomUUID()}").apply { check(mkdir()) }
        private val name = "import-host-${UUID.randomUUID()}.db"
        val paths = ProjectPathPolicy(File(root, "projects"))
        private val leaseStore = FileProjectLeaseStore(paths)
        private val coordinator = ProjectLeaseCoordinator(leaseStore)
        private val staging = File(root, "staging")
        val opened = mutableListOf<String>()
        private var database = open()
        val dao: ScanDao get() = database.dao()
        lateinit var project: String
        private fun open() = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        suspend fun initialize() { project = ProjectCreationServiceImpl(dao, paths).create("Synthetic import host") }
        fun reopen() { database.close(); database = open() }
        fun bytes(key: String): ByteArray = when (key) {
            "first" -> ByteArray(257) { (it * 13).toByte() }
            "second" -> ByteArray(319) { (it * 7 + 1).toByte() }
            "large" -> ByteArray(2 * 1024 * 1024) { (it * 17).toByte() }
            else -> error("Unknown synthetic source")
        }
        private fun service(dao: ScanDao) = ImportService(
            ImportSourceAccess { opened += it.uri; bytes(it.uri).inputStream() },
            ImportInspector { _, _ -> ImportedPhotoInfo(2, 2, null, 0.0, 0.0, 0.0,
                CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA)) },
            JournaledOriginalPublicationPort(dao, paths), staging,
        )
        suspend fun import(dao: ScanDao = this.dao, sources: List<String> = listOf("first"),
            progress: suspend (ImportProgress) -> Unit = {}): ImportBatchResult {
            val revision = requireNotNull(this.dao.project(project)).revision
            val request = ImportRequest(UUID.randomUUID().toString(), project, revision, sources.map { ImportSource(it) })
            return leased(request.operationId, revision) { service(dao).import(request, progress) }
        }
        suspend fun recover() = leased(UUID.randomUUID().toString(), requireNotNull(dao.project(project)).revision) {
            service(dao).recoverAbandoned()
            JournaledOriginalPublicationPort(dao, paths).recover(project)
        }
        private suspend fun <T> leased(operation: String, revision: Long, block: suspend () -> T): T =
            withAcquiredProjectLease(coordinator,
                ProjectOperationRequest(project, operation, ProjectOperationKind.IMPORT_PHOTO, revision)) { lease ->
                coordinator.within(lease) {
                    assertEquals(1, coordinator.activeCount(project))
                    block()
                }
            }.also { assertEquals(0, coordinator.activeCount(project)) }
        fun journals() = paths.projectDirectory(project).listFiles().orEmpty().filter { it.name.endsWith(".journal") }
        fun assertClean() {
            assertTrue(journals().isEmpty())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertEquals(0, coordinator.activeCount(project))
        }
        fun close() {
            var failure: Throwable? = null
            val actions: List<() -> Unit> = listOf(
                { database.close() }, { leaseStore.close() },
                { context.deleteDatabase(name); Unit },
                { check(root.deleteRecursively()) { "Could not remove HOST fixture root" } },
            )
            actions.forEach { action ->
                try { action() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
