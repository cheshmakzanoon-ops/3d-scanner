package com.realityforge.scanner.agents.a026

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.application.BoundSafExportService
import com.realityforge.scanner.application.ScannerCompositionRoot
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.archives.CaptureArchiveImporter
import com.realityforge.scanner.data.operations.StaleProjectRevisionException
import com.realityforge.scanner.data.safexport.CompletionEvidence
import com.realityforge.scanner.data.safexport.ProviderAtomicity
import com.realityforge.scanner.data.safexport.TransferOutcome
import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.domain.RequestId
import com.realityforge.scanner.qa.SharedFaultDocumentFixtures as FaultDocumentProvider
import com.realityforge.scanner.qa.SharedFaultDocumentProvider.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Real application composition, C6 access, Room imports, 025 ZIP encoding and 026 provider readback. */
@RunWith(AndroidJUnit4::class)
class BoundSafExportIntegrationDeviceTest {
    @Test fun captureArchiveExportUsesTheBoundProjectAndVerifiesExactProviderBytes() = runBlocking {
        withFixture { fixture ->
            val project = fixture.createProject("Bound capture archive")
            fixture.import(project, 17)
            fixture.import(project, 23)
            val before = fixture.originals(project)
            val request = fixture.request(project)
            // Creating another project after binding must never redirect the completed picker callback.
            val other = fixture.createProject("Later selected project")
            fixture.import(other, 41)
            val otherBefore = fixture.originals(other)
            val uri = fixture.destination()
            val progress = mutableListOf<Pair<Long, Long>>()
            val outcome = fixture.exports.captureArchive(request, uri) {
                assertEquals(request.requestId.value, it.requestId)
                progress += it.bytesCopied to it.expectedByteCount
            }
            assertTrue(outcome.toString(), outcome is TransferOutcome.VerifiedSuccess)
            val receipt = (outcome as TransferOutcome.VerifiedSuccess).receipt
            assertEquals(request.requestId.value, receipt.requestId)
            assertEquals(project, receipt.sourceIdentity.projectId)
            assertEquals(request.project.revision, receipt.sourceIdentity.revision)
            assertEquals("capture-archive-v2", receipt.sourceIdentity.representation)
            assertEquals(CompletionEvidence.READ_BACK_SHA256, receipt.completionEvidence)
            assertEquals(ProviderAtomicity.DIRECT_NON_ATOMIC, receipt.providerAtomicity)
            assertEquals(uri.toString(), receipt.finalDestinationUri)
            assertEquals(FaultDocumentProvider.inspect(fixture.app, uri).bytes, receipt.byteCount)
            assertEquals(FaultDocumentProvider.hash(fixture.app, uri), receipt.sha256)
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertTrue(progress.all { it.second == receipt.byteCount && it.first <= it.second })
            assertEquals(receipt.byteCount, progress.last().first)

            fixture.app.contentResolver.openInputStream(uri)!!.use { input ->
                CaptureArchiveImporter().stage(input, File(fixture.temporary, "verified-archive")).use { archive ->
                    assertEquals(project, archive.sourceProjectId)
                    assertEquals(request.project.revision, archive.sourceRevision)
                    assertEquals(before.map { it.row.id }, archive.photos.map { it.sourceId })
                    assertEquals(before.map { it.row.sha256 }, archive.photos.map { it.sha256 })
                    archive.photos.forEachIndexed { index, photo ->
                        assertArrayEquals(before[index].bytes, photo.file.readBytes())
                        assertEquals(before[index].row.captureMetadataRecord, photo.captureMetadataRecord)
                        assertEquals(before[index].row.opticalIdentitySha256, photo.opticalIdentitySha256)
                        assertEquals(before[index].row.captureSettingsRecord, photo.captureSettingsRecord)
                    }
                }
            }
            fixture.assertOriginals(project, before)
            fixture.assertOriginals(other, otherBefore)
            fixture.assertNoStagingOrReservation(request)
            assertEquals(request.project.revision, fixture.root.repository.dao.project(project)?.revision)
        }
    }

    @Test fun staleCaptureArchiveRequestRejectsBeforeWritingOrDeletingTheSelectedDocument() = runBlocking {
        withFixture { fixture ->
            val project = fixture.createProject("Stale archive request")
            fixture.import(project, 31)
            val stale = fixture.request(project)
            fixture.import(project, 47)
            val current = fixture.originals(project)
            val sentinel = byteArrayOf(9, 8, 7, 6)
            val uri = fixture.destination(sentinel)
            var progressCalled = false
            try {
                fixture.exports.captureArchive(stale, uri) { progressCalled = true }
                fail("A stale immutable capture-archive request was accepted")
            } catch (_: StaleProjectRevisionException) { }
            assertFalse(progressCalled)
            val inspection = FaultDocumentProvider.inspect(fixture.app, uri)
            assertTrue(inspection.exists)
            assertEquals(0L, inspection.writeCalls)
            assertArrayEquals(sentinel, FaultDocumentProvider.read(fixture.app, uri))
            fixture.assertOriginals(project, current)
            fixture.assertNoStagingOrReservation(stale)
            assertEquals(stale.project.revision + 1, fixture.root.repository.dao.project(project)?.revision)
        }
    }

    private data class Original(val row: ScanPhoto, val bytes: ByteArray)

    private class Fixture(val app: RealityForgeApplication, val root: ScannerCompositionRoot, val temporary: File) {
        val exports = BoundSafExportService(app, root)
        val projects = mutableListOf<String>()
        val destinations = mutableListOf<Uri>()
        private val localSources = mutableListOf<Pair<File, String>>()

        suspend fun createProject(name: String): String = root.repository.create(name).also { projects += it }

        suspend fun import(project: String, seed: Int) {
            val file = File(temporary, "photo-$seed.jpg")
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            try {
                val random = Random(seed)
                bitmap.setPixels(IntArray(160 * 120) { -0x1000000 or random.nextInt(0x1000000) }, 0, 160, 0, 0, 160, 120)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it)) }
            } finally { bitmap.recycle() }
            localSources += file to sha256(file.readBytes())
            assertTrue(root.repository.importPhoto(project, Uri.fromFile(file)))
        }

        suspend fun request(project: String): BoundExternalRequest.CaptureBundleExport {
            val row = requireNotNull(root.repository.dao.project(project))
            return BoundExternalRequest.CaptureBundleExport(RequestId(UUID.randomUUID().toString()),
                ProjectRevision(project, row.revision), 7, System.currentTimeMillis())
        }

        fun destination(initial: ByteArray = ByteArray(0)): Uri =
            FaultDocumentProvider.createUri(app, Mode.EXACT, initial).also { destinations += it }

        suspend fun originals(project: String): List<Original> = root.repository.dao.photos(project)
            .map { Original(it, root.repository.photoFile(it).readBytes()) }

        suspend fun assertOriginals(project: String, expected: List<Original>) {
            assertEquals(expected.map { it.row }, root.repository.dao.photos(project))
            expected.forEach { assertArrayEquals(it.bytes, root.repository.photoFile(it.row).readBytes()) }
            localSources.forEach { (file, hash) -> assertEquals(hash, sha256(file.readBytes())) }
        }

        suspend fun assertNoStagingOrReservation(request: BoundExternalRequest.CaptureBundleExport) {
            assertFalse(File(app.cacheDir, "capture-export-staging-v1/${request.requestId.value}").exists())
            assertTrue(root.database.storageReservationDao().all().none { it.projectId == request.project.projectId })
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RealityForgeApplication
        val root = app.compositionRoot
        root.execution.awaitRecovery()
        val directory = File(app.cacheDir, "a026-bound-export-${UUID.randomUUID()}").apply { check(mkdir()) }
        val fixture = Fixture(app, root, directory)
        try { block(fixture) } finally {
            fixture.destinations.forEach { FaultDocumentProvider.cleanup(app, it) }
            fixture.projects.asReversed().forEach { root.repository.delete(it) }
            check(directory.deleteRecursively())
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
