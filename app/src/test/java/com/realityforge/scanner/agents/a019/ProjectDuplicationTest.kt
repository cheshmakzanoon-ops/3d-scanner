package com.realityforge.scanner.agents.a019

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.*
import com.realityforge.scanner.data.library.*
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectDuplicationTest {
    @Test fun copyHasIndependentOriginalsPhotoIdsSessionLinksAndReboundCuration() = runBlocking {
        fixture { root, port ->
            val oldHashes = port.photos.map { it.sha256 }
            val result = ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0)
                .duplicate(port.project.id, 7, "نسخه مستقل")
            val copied = requireNotNull(port.publishedPhotos)
            assertTrue(copied.all { it.projectId == result && it.captureSessionId == null && it.captureSequence == null })
            assertEquals(copied.size, copied.map { it.id }.distinct().size)
            assertTrue(copied.none { clone -> port.photos.any { original -> original.id == clone.id } })
            val excluded = copied[1]
            assertFalse(excluded.included)
            val record = PhotoCurationRecordCodec.decode(requireNotNull(excluded.curationRecord))
            assertEquals(excluded.id, record.id)
            assertEquals("Blur in source", record.exclusion?.note)
            assertEquals(1, record.position)
            File(root, port.project.id).deleteRecursively()
            // Use the same strict resolver as repository reopening/reconstruction, not
            // a raw File that would hide an inconsistent photo-ID/filename mapping.
            val paths = ProjectPathPolicy(root)
            assertEquals(oldHashes, copied.map { hash(paths.photoFile(it)) })
            assertTrue(copied.all { it.filename == "${it.id}.image" })
        }
    }

    @Test fun diskExhaustionDuringCopyAbortsOnlyItsReservationAndPreservesSource() = runBlocking {
        fixture { root, port ->
            var reads = 0
            val service = ProjectLibraryService(port, root, { if (++reads < 3) Long.MAX_VALUE else 0 }, 0)
            try { service.duplicate(port.project.id, 7, "copy"); fail("Disk exhaustion was ignored") }
            catch (_: InsufficientDuplicateStorage) { }
            assertEquals(1, port.aborted)
            assertNull(port.publishedPhotos)
            assertEquals(listOf(port.project.id), root.list().orEmpty().toList())
            assertOriginals(root, port)
        }
    }

    @Test fun independentCopyDoesNotInheritTheSourcesPermissionToDeleteAnOriginal() = runBlocking {
        fixture { root, port ->
            val first = port.photos.first()
            val pending = CuratedPhoto(first.id, first.sha256, 0, CuratedPhotoState.REMOVAL_PENDING,
                pendingRemoval = PendingPhotoRemoval("source-removal", 2_000,
                    PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST), RestorablePhotoState.INCLUDED))
            val pendingSource = FakePort(port.project, listOf(first.copy(included = false,
                curationRecord = PhotoCurationRecordCodec.encode(pending))) + port.photos.drop(1))
            ProjectLibraryService(pendingSource, root, { Long.MAX_VALUE }, 0)
                .duplicate(port.project.id, 7, "independent")
            val copied = requireNotNull(pendingSource.publishedPhotos).first()
            val record = PhotoCurationRecordCodec.decode(requireNotNull(copied.curationRecord))
            assertTrue(copied.included)
            assertEquals(CuratedPhotoState.INCLUDED, record.state)
            assertNull(record.pendingRemoval)
            assertNotEquals(first.id, record.id)
            assertOriginals(root, port)
        }
    }

    @Test fun cancellationAndChecksumMismatchLeaveNoVisibleOrUntrackedPartialDuplicate() = runBlocking {
        fixture { root, port ->
            try {
                ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0).duplicate(port.project.id, 7, "copy") {
                    if (it.copiedBytes > 0) throw CancellationException("cancel fixture")
                }
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) { }
            assertEquals(1, port.aborted)
            assertNull(port.publishedPhotos)
            assertEquals(listOf(port.project.id), root.list().orEmpty().toList())
            assertOriginals(root, port)
            val first = port.photos.first()
            File(File(File(root, port.project.id), "originals"), first.filename).writeBytes(ByteArray(first.bytes.toInt()) { 0 })
            try { ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0).duplicate(port.project.id, 7, "bad copy"); fail("Checksum mismatch ignored") }
            catch (_: IllegalStateException) { }
            assertEquals(2, port.aborted)
            assertEquals(listOf(port.project.id), root.list().orEmpty().toList())
        }
    }

    @Test fun ambiguousPublicationFailureCannotDeleteAnAlreadyCommittedDuplicate() = runBlocking {
        fixture { root, port ->
            port.failAfterPublication = true
            try { ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0).duplicate(port.project.id, 7, "copy"); fail("Fixture failure expected") }
            catch (_: IllegalStateException) { }
            val copied = requireNotNull(port.publishedPhotos)
            val target = copied.first().projectId
            assertEquals(port.photos.map { it.sha256 }, copied.map { hash(File(File(File(root, target), "originals"), it.filename)) })
            assertOriginals(root, port)
            assertEquals(0, port.aborted)
        }
    }

    @Test fun missingStorageAndSymlinkedOriginalsNeverPublish() = runBlocking {
        fixture { root, port ->
            try { ProjectLibraryService(port, root, { 0 }, 0).duplicate(port.project.id, 7, "copy"); fail("Preflight expected") }
            catch (_: InsufficientDuplicateStorage) { }
            assertNull(port.reservedId)
            val outside = File(root.parentFile, "outside-${UUID.randomUUID()}.image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                val original = File(File(File(root, port.project.id), "originals"), port.photos[0].filename)
                check(original.delete())
                Files.createSymbolicLink(original.toPath(), outside.toPath())
                try { ProjectLibraryService(port, root, { Long.MAX_VALUE }, 0).duplicate(port.project.id, 7, "copy"); fail("Symlink accepted") }
                catch (_: IllegalStateException) { }
                assertArrayEquals(byteArrayOf(1, 2, 3), outside.readBytes())
                assertNull(port.publishedPhotos)
            } finally { outside.delete() }
        }
    }

    private suspend fun fixture(block: suspend (File, FakePort) -> Unit) {
        val root = Files.createTempDirectory("a019-duplicate-").toFile()
        try {
            val project = ScanProject(UUID.randomUUID().toString(), "Source", 1_000, revision = 7)
            val folder = File(File(root, project.id), "originals").apply { mkdirs() }
            val photos = (0..1).map { index ->
                val file = File(folder, "photo-$index.image").apply { writeBytes(ByteArray(150_000 + index) { (it * 31 + index).toByte() }) }
                val photoId = UUID.randomUUID().toString()
                val curated = CuratedPhoto(photoId, hash(file), index,
                    if (index == 0) CuratedPhotoState.INCLUDED else CuratedPhotoState.EXCLUDED,
                    if (index == 0) null else PhotoCurationReason(PhotoExclusionCode.BLUR, "Blur in source"))
                ScanPhoto(photoId, project.id, file.name, curated.sha256, file.length(), 32, 32,
                    null, 1.0, 0.0, 0.0, 1_000L + index, included = index == 0,
                    selectionOrder = index.toLong(), captureSessionId = "source-session", captureSequence = index.toLong(),
                    curationRecord = PhotoCurationRecordCodec.encode(curated))
            }
            block(root, FakePort(project, photos))
        } finally { root.deleteRecursively() }
    }

    private class FakePort(val project: ScanProject, val photos: List<ScanPhoto>) : ProjectLibraryPort {
        var reservedId: String? = null
        var publishedPhotos: List<ScanPhoto>? = null
        var aborted = 0
        var failAfterPublication = false
        override suspend fun summaries(query: LibraryQuery) = emptyList<ProjectSummary>()
        override suspend fun rename(id: String, expectedRevision: Long, name: String) = false
        override suspend fun snapshot(id: String) = (project to photos).takeIf { id == project.id }
        override suspend fun reserveDuplicate(sourceId: String, sourceRevision: Long, newId: String, name: String): Boolean {
            if (sourceId != project.id || sourceRevision != project.revision) return false
            reservedId = newId
            return true
        }
        override suspend fun publishDuplicate(newId: String, sourceId: String, sourceRevision: Long, photos: List<ScanPhoto>): Boolean {
            check(newId == reservedId && sourceRevision == project.revision)
            publishedPhotos = photos.toList()
            if (failAfterPublication) error("Lost reply after atomic commit")
            return true
        }
        override suspend fun abortDuplicate(newId: String) {
            check(publishedPhotos == null) { "Published duplicate must survive recovery." }
            check(newId == reservedId)
            aborted++
        }
    }

    private fun assertOriginals(root: File, port: FakePort) = assertEquals(port.photos.map { it.sha256 },
        port.photos.map { hash(File(File(File(root, port.project.id), "originals"), it.filename)) })
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
