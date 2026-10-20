package com.realityforge.scanner.agents.a025

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.archives.*
import com.realityforge.scanner.capture.metadata.*
import com.realityforge.scanner.data.curation.*
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class ProjectCaptureArchiveServiceTest {
    private class MemoryStore : ArchiveProjectStore {
        val projects = linkedMapOf<String, ScanProject>()
        val images = linkedMapOf<String, List<ScanPhoto>>()
        override suspend fun project(id: String) = projects[id]
        override suspend fun photos(id: String) = images[id].orEmpty()
        override suspend fun restore(project: ScanProject, photos: List<ScanPhoto>) {
            check(project.id !in projects)
            require(photos.all { it.projectId == project.id })
            projects[project.id] = project
            images[project.id] = photos.toList()
        }
    }

    @Test fun validatedRestoreCreatesIndependentIdsAndPreservesBytesSelectionOrderAndProvenance() = runBlocking {
        val root = Files.createTempDirectory("archive-roundtrip").toFile()
        try {
            val fixture = fixture(root)
            val store = MemoryStore()
            val paths = ProjectPathPolicy(File(root, "projects"))
            val service = ProjectCaptureArchiveService(store, paths, "test-engine-current")
            val restored = service.restore(fixture.bytes.inputStream())
            assertNotEquals(fixture.projectId, restored)
            val photos = store.photos(restored)
            assertEquals(listOf(false, true, true), photos.map { it.included })
            assertEquals(listOf(0L, 1L, 2L), photos.map { it.selectionOrder })
            assertEquals(fixture.originals.map { sha256(it.readBytes()) }, photos.map { it.sha256 })
            assertTrue(photos.none { photo -> fixture.originals.any { it.name == photo.filename } })
            photos.forEachIndexed { index, photo -> assertArrayEquals(fixture.originals[index].readBytes(), paths.photoFile(photo).readBytes()) }
            assertEquals(17.0, photos.first().sharpness, 0.0)
            val rebound = photos.map { PhotoCurationRecordCodec.decode(requireNotNull(it.curationRecord)) }
            assertEquals(photos.map { it.id }, rebound.map { it.id })
            assertEquals(listOf(0, 1, 2), rebound.map { it.position })
            assertEquals(CuratedPhotoState.REMOVAL_PENDING, rebound.first().state)
            assertNotEquals("source-removal-0", rebound.first().pendingRemoval!!.requestId)
            assertEquals(PhotoRemovalCode.PRIVACY, rebound.first().pendingRemoval!!.reason.code)
            assertTrue(photos.all { it.captureSettingsRecord == "{}" })
            photos.forEach { photo ->
                val record = CaptureMetadataCodec.decode(requireNotNull(photo.captureMetadataRecord).toByteArray(Charsets.UTF_8))
                assertEquals(CaptureMetadataCodec.opticalIdentitySha256(record), photo.opticalIdentitySha256)
            }
            val exported = ByteArrayOutputStream()
            assertEquals(3, service.export(restored, exported))
            CaptureArchiveImporter().stage(exported.toByteArray().inputStream(), File(root, "second-stage")).use {
                assertEquals("synthetic", it.provenance)
                assertEquals("test-engine-current", it.engineVersion)
                assertEquals(photos.map { it.sha256 }, it.photos.map { it.sha256 })
                assertEquals(photos.map { it.included }, it.photos.map { it.included })
                assertEquals(photos.map { it.captureMetadataRecord }, it.photos.map { it.captureMetadataRecord })
                assertEquals(photos.map { it.curationRecord }, it.photos.map { it.curationRecord })
            }
            paths.photoFile(photos.first()).writeBytes(byteArrayOf(7))
            assertEquals(fixture.hashes, fixture.originals.map { sha256(it.readBytes()) })
            assertEquals(0, service.recover().incompleteRemoved)
        } finally { root.deleteRecursively() }
    }

    @Test fun newServiceReconcilesDiskImagesFromEachPublicationBoundary() = runBlocking {
        for (boundary in listOf(ArchiveRestoreBoundary.JOURNAL_SYNCED, ArchiveRestoreBoundary.FILES_PROMOTED,
            ArchiveRestoreBoundary.DATABASE_COMMITTED)) {
            val root = Files.createTempDirectory("archive-crash").toFile()
            try {
                val fixture = fixture(root)
                val paths = ProjectPathPolicy(File(root, "projects"))
                val store = MemoryStore()
                val snapshot = File(root, "disk-at-crash")
                val service = ProjectCaptureArchiveService(store, paths, "test-engine", faults = ArchiveRestoreFaultInjector {
                    if (it == boundary) {
                        paths.root.copyRecursively(snapshot)
                        throw InterruptedArchiveWrite()
                    }
                })
                try { service.restore(fixture.bytes.inputStream()); fail("Expected an interrupted restore") }
                catch (_: InterruptedArchiveWrite) { }
                // Recreate the exact persisted filesystem image captured before normal exception cleanup.
                paths.root.deleteRecursively()
                snapshot.copyRecursively(paths.root)
                val report = ProjectCaptureArchiveService(store, paths, "test-engine").recover()
                assertTrue(report.failures.toString(), report.failures.isEmpty())
                assertEquals(if (boundary == ArchiveRestoreBoundary.DATABASE_COMMITTED) 1 else 0, report.committedPreserved)
                assertEquals(if (boundary == ArchiveRestoreBoundary.DATABASE_COMMITTED) 0 else 1, report.incompleteRemoved)
                assertEquals(if (boundary == ArchiveRestoreBoundary.DATABASE_COMMITTED) 1 else 0, store.projects.size)
                assertEquals(fixture.hashes, fixture.originals.map { sha256(it.readBytes()) })
                assertEquals(0, ProjectCaptureArchiveService(store, paths, "test-engine").recover().incompleteRemoved)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun recoveryRefusesToRemoveAProjectWithAnUnrelatedOwnershipMarker() = runBlocking {
        val root = Files.createTempDirectory("archive-ownership").toFile()
        try {
            val fixture = fixture(root)
            val paths = ProjectPathPolicy(File(root, "projects"))
            val store = MemoryStore()
            val snapshot = File(root, "crash")
            val service = ProjectCaptureArchiveService(store, paths, "test", faults = ArchiveRestoreFaultInjector {
                if (it == ArchiveRestoreBoundary.FILES_PROMOTED) {
                    paths.root.copyRecursively(snapshot)
                    throw InterruptedArchiveWrite()
                }
            })
            try { service.restore(fixture.bytes.inputStream()); fail("Expected interruption") } catch (_: InterruptedArchiveWrite) { }
            paths.root.deleteRecursively(); snapshot.copyRecursively(paths.root)
            val promoted = paths.root.listFiles()!!.single { !it.name.startsWith(".") }
            File(promoted, "archive-restoration.json").writeText("{}")
            val before = promoted.walkTopDown().filter(File::isFile).count()
            val report = ProjectCaptureArchiveService(store, paths, "test").recover()
            assertEquals(1, report.failures.size)
            assertEquals(before, promoted.walkTopDown().filter(File::isFile).count())
            assertTrue(store.projects.isEmpty())
        } finally { root.deleteRecursively() }
    }

    private class InterruptedArchiveWrite : IllegalStateException("Injected persistence boundary interruption")
    private data class Fixture(val projectId: String, val bytes: ByteArray, val originals: List<File>, val hashes: List<String>)
    private fun fixture(root: File): Fixture {
        val project = UUID.randomUUID().toString()
        val originals = (0..2).map { seed ->
            File(root, "${UUID.randomUUID()}.image").also { it.writeBytes(ByteArray(8192) { ((it * 31 + seed) % 256).toByte() }) }
        }
        val output = ByteArrayOutputStream()
        val metadata = CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE)
        val photos = originals.mapIndexed { index, file ->
            val id = file.name.removeSuffix(".image")
            val digest = sha256(file.readBytes())
            val curated = if (index == 0) CuratedPhoto(id, digest, index * 2, CuratedPhotoState.REMOVAL_PENDING,
                pendingRemoval = PendingPhotoRemoval("source-removal-0", 100,
                    PhotoRemovalReason(PhotoRemovalCode.PRIVACY), RestorablePhotoState.INCLUDED))
                else CuratedPhoto(id, digest, index * 2)
            CaptureArchivePhoto(id, file, digest, file.length(), 80, 60, null, 1234L + index,
                index != 0, (index * 2).toLong(), 17.0 + index, .1, .2,
                CaptureMetadataCodec.encode(metadata).toString(Charsets.UTF_8), CaptureMetadataCodec.opticalIdentitySha256(metadata),
                PhotoCurationRecordCodec.encode(curated), "{}")
        }
        CaptureArchiveWriter().write(CaptureArchiveSource(project, "Synthetic fixture", 5, "source-engine", photos,
            source = "synthetic"), output)
        return Fixture(project, output.toByteArray(), originals, originals.map { sha256(it.readBytes()) })
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
