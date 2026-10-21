package com.realityforge.scanner.agents.a011

import com.realityforge.scanner.capture.metadata.CaptureMetadata
import com.realityforge.scanner.capture.metadata.CaptureSource
import com.realityforge.scanner.data.importing.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val project = UUID.randomUUID().toString()
    private val info = ImportedPhotoInfo(10, 10, null, 1.0, 0.0, 0.0,
        CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA))

    @Test fun mixedBatchPreservesBytesAndReportsEachOutcomeAtTheInitiatingRevision() = runBlocking {
        val root = temporary.newFolder("staging")
        val originals = temporary.newFolder("originals")
        val sourceBytes = mapOf("first" to ByteArray(100) { it.toByte() }, "last" to byteArrayOf(1, 4, 9))
        var revision = 7L
        val hashes = linkedMapOf<String, String>()
        val service = ImportService(ImportSourceAccess { source ->
            if (source.uri == "revoked") throw SecurityException("provider revoked")
            ByteArrayInputStream(sourceBytes[source.uri] ?: sourceBytes.getValue("first"))
        }, ImportInspector { _, source ->
            require(source.uri != "corrupt") { "Malformed image fixture" }; info
        }, ImportPublicationPort { staged ->
            assertEquals(project, staged.projectId)
            assertEquals(revision, staged.expectedRevision)
            assertEquals(staged.sha256, sha(staged.file.readBytes()))
            val existing = hashes[staged.sha256]
            if (existing != null) ImportPublicationResult.Duplicate(existing, revision)
            else {
                val id = UUID.randomUUID().toString()
                staged.file.copyTo(File(originals, id))
                hashes[staged.sha256] = id
                ImportPublicationResult.Imported(id, ++revision)
            }
        }, root, ImportBudgets(freeSpaceHeadroomBytes = 0), { Long.MAX_VALUE })
        val result = service.import(ImportRequest(UUID.randomUUID().toString(), project, 7,
            listOf("first", "duplicate", "corrupt", "revoked", "last").map { ImportSource(it) }))
        assertEquals(2, result.importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(2, result.rejectedCount)
        assertEquals(9L, result.finalRevision)
        assertEquals(listOf(0, 1, 2, 3, 4), result.photos.map { it.sourceIndex })
        assertEquals(sourceBytes.values.map(::sha).toSet(), originals.listFiles()!!.map { sha(it.readBytes()) }.toSet())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun zeroAndShortReadsMakeProgressWithoutTruncatingTheOriginal() = runBlocking {
        val bytes = ByteArray(321) { (it * 13).toByte() }
        var reads = 0
        var published: ByteArray? = null
        val root = temporary.newFolder()
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return if (reads % 3 == 0) 0 else super.read(buffer, offset, minOf(length, 7))
            }
        }
        val service = ImportService(ImportSourceAccess { stream }, ImportInspector { _, _ -> info },
            ImportPublicationPort { photo ->
                published = photo.file.readBytes()
                ImportPublicationResult.Imported("photo", 1)
            }, root, ImportBudgets(freeSpaceHeadroomBytes = 0), { Long.MAX_VALUE })
        service.import(request("input"))
        assertArrayEquals(bytes, published)
        assertTrue(reads < 200)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun storageAndEncodedByteLimitsRejectBeforePublicationAndLeaveNoPartial() = runBlocking {
        for (diskFull in listOf(false, true)) {
            val root = temporary.newFolder()
            var publications = 0
            val service = ImportService(ImportSourceAccess { ByteArrayInputStream(ByteArray(100)) },
                ImportInspector { _, _ -> info }, ImportPublicationPort {
                    publications++; ImportPublicationResult.Imported("bad", 1)
                }, root, ImportBudgets(maxPhotoBytes = if (diskFull) 200 else 50,
                    maxBatchBytes = 200, freeSpaceHeadroomBytes = 8), { if (diskFull) 0 else Long.MAX_VALUE })
            val result = service.import(request("oversized-or-full"))
            assertEquals(1, result.rejectedCount)
            assertEquals(0, publications)
            assertEquals(0L, result.finalRevision)
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationDuringStreamingNeverPublishesAndCleansStaging() = runBlocking {
        val root = temporary.newFolder()
        var publications = 0
        val service = ImportService(ImportSourceAccess { ByteArrayInputStream(ByteArray(200_000)) },
            ImportInspector { _, _ -> info }, ImportPublicationPort {
                publications++; ImportPublicationResult.Imported("bad", 1)
            }, root, ImportBudgets(maxPhotoBytes = 300_000, maxBatchBytes = 300_000,
                freeSpaceHeadroomBytes = 0), { Long.MAX_VALUE })
        try {
            service.import(request("cancel")) { throw CancellationException("injected stream cancellation") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, publications)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun recoveryDeletesOnlyOwnedAbandonedStreamsAndPreservesUnknownFiles() = runBlocking {
        val root = temporary.newFolder()
        val abandoned = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        File(abandoned, "0.partial").writeBytes(byteArrayOf(1, 2))
        val unknown = File(root, "operator-note.txt").apply { writeText("keep") }
        val service = ImportService(ImportSourceAccess { error("unused") }, ImportInspector { _, _ -> info },
            ImportPublicationPort { error("unused") }, root)
        assertEquals(1, service.recoverAbandoned())
        assertFalse(abandoned.exists())
        assertEquals("keep", unknown.readText())
        assertEquals(0, service.recoverAbandoned())
    }

    @Test fun pendingPublicationPausesBatchWithoutOpeningLaterSourcesOrClaimingARevision() = runBlocking {
        val root = temporary.newFolder()
        val opened = mutableListOf<String>()
        var published = 0
        val service = ImportService(ImportSourceAccess { source ->
            opened += source.uri
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }, ImportInspector { _, _ -> info }, ImportPublicationPort { photo ->
            published++
            when (photo.sourceIndex) {
                0 -> ImportPublicationResult.Imported("confirmed", 8)
                1 -> ImportPublicationResult.PendingRecovery("uncertain",
                    "Publication is pending recovery.", 8)
                else -> error("Later photographs must not be published")
            }
        }, root, ImportBudgets(freeSpaceHeadroomBytes = 0), { Long.MAX_VALUE })
        val result = service.import(ImportRequest(UUID.randomUUID().toString(), project, 7,
            listOf("confirmed", "uncertain", "unopened", "also-unopened").map { ImportSource(it) }))

        assertEquals(listOf("confirmed", "uncertain"), opened)
        assertEquals(2, published)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.pendingCount)
        assertEquals(2, result.rejectedCount)
        assertEquals(8L, result.finalRevision)
        assertEquals(listOf(0, 1, 2, 3), result.photos.map { it.sourceIndex })
        assertTrue(result.photos[1].outcome is ImportPublicationResult.PendingRecovery)
        assertEquals(listOf("BATCH_PAUSED_FOR_RECOVERY", "BATCH_PAUSED_FOR_RECOVERY"),
            result.photos.drop(2).map { (it.outcome as ImportPublicationResult.Rejected).code })
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun recoveryValidatesTheWholeBoundedOperationBeforeDeletingAnyFile() = runBlocking {
        val root = temporary.newFolder()
        val operation = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val staged = File(operation, "0.partial").apply { writeText("owned but retained on ambiguity") }
        val unknown = File(operation, "operator-note.txt").apply { writeText("retain") }
        val service = ImportService(ImportSourceAccess { error("unused") }, ImportInspector { _, _ -> info },
            ImportPublicationPort { error("unused") }, root)
        try { service.recoverAbandoned(); fail("Ambiguous staging was accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(staged.exists())
        assertEquals("retain", unknown.readText())
    }

    @Test fun symlinkedOperationDirectoryCannotDeleteAnotherDirectory() = runBlocking {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val retained = File(outside, "0.partial").apply { writeText("original") }
        val request = request("input")
        Files.createSymbolicLink(File(root, request.operationId).toPath(), outside.toPath())
        val service = ImportService(ImportSourceAccess { error("must not open") }, ImportInspector { _, _ -> info },
            ImportPublicationPort { error("must not publish") }, root)
        try { service.import(request); fail("Symlink was accepted") } catch (_: IllegalStateException) { }
        assertEquals("original", retained.readText())
    }

    @Test fun integrityFailureStopsBatchWithoutFalseSuccessOrOpeningRemainingSource() = runBlocking {
        var opened = 0
        val external = temporary.newFile().apply { writeText("known-good-source") }
        val service = ImportService(ImportSourceAccess { opened++; external.inputStream() },
            ImportInspector { _, _ -> info }, ImportPublicationPort { staged ->
                ImportPublicationResult.OriginalIntegrityFailure("existing", "Existing original needs repair; no automatic repair.", staged.expectedRevision)
            }, temporary.newFolder())
        val result = service.import(ImportRequest(UUID.randomUUID().toString(), project, 7,
            listOf(ImportSource("first"), ImportSource("must-not-open"))))
        assertEquals(1, opened); assertEquals(7L, result.finalRevision)
        assertEquals(0, result.importedCount); assertEquals(0, result.duplicateCount); assertEquals(0, result.pendingCount)
        assertEquals(1, result.integrityFailureCount); assertEquals(1, result.rejectedCount)
        assertEquals("BATCH_PAUSED_FOR_INTEGRITY", (result.photos.last().outcome as ImportPublicationResult.Rejected).code)
        assertEquals("known-good-source", external.readText())
    }

    private fun request(source: String) = ImportRequest(UUID.randomUUID().toString(), project, 0,
        listOf(ImportSource(source)))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
