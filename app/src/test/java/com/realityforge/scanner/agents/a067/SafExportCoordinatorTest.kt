package com.realityforge.scanner.agents.a067

import com.realityforge.scanner.data.safexport.*
import com.realityforge.scanner.export.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real 026 transfer/source-verification code; only provider/source ports are test doubles. */
class SafExportCoordinatorTest {
    private val bytes = "{\"synthetic\":true,\"representation\":\"sparse_points\"}".toByteArray()
    private val identity = ArtifactIdentity(UUID.randomUUID().toString(), 4, "completed-artifact", "a".repeat(64))
    private val option = ExportOption("json", ExportFormat.JSON, AssetRepresentation.DIAGNOSTICS, bytes.size.toLong())
    private fun request() = ExportRequest(UUID.randomUUID().toString(), identity, option)
    private fun callback(request: ExportRequest, readable: Boolean = true, size: Boolean = true) = DestinationSelectionCallback(
        requestId = request.requestId,
        destinationUri = "content://explicit-test-provider/model",
        ownership = DestinationOwnership.NEWLY_CREATED,
        capabilities = DestinationCapabilities(readable, size, true, false, false),
    )
    private fun temporary(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("saf-export-coordinator-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private inner class Source : ExportSource {
        var current = true
        var invalid = false
        var oversize = false
        var materializations = 0
        var currentChecks = 0
        var catalog = listOf(option)
        override suspend fun isCurrent(identity: ArtifactIdentity): Boolean {
            currentChecks++
            return current && identity == this@SafExportCoordinatorTest.identity
        }
        override suspend fun available(identity: ArtifactIdentity) = catalog
        override fun materialize(request: ExportRequest, target: File, checkpoint: () -> Unit) {
            checkpoint(); materializations++
            target.writeBytes(if (oversize) bytes + byteArrayOf(0) else bytes)
        }
        override fun validate(request: ExportRequest, file: File) {
            check(!invalid) { "INVALID_ARTIFACT" }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    private class Store : DestinationDocumentStore {
        val bytes = ByteArrayOutputStream()
        var prepares = 0
        var opens = 0
        var committed = false
        var aborted = false
        var deleted = false
        var deleteRequested: Boolean? = null
        var silentlyDropLastByte = false
        var throwDuringWrite = false
        var corruptReadback = false
        var beforePrepare: () -> Unit = {}
        var onCommit: () -> Unit = {}
        override suspend fun prepare(request: BoundDestinationRequest): PreparedDestination {
            prepares++; beforePrepare()
            return object : PreparedDestination {
                override val request = request
                override val writeUri = request.destinationUri
                override val atomicity = ProviderAtomicity.DIRECT_NON_ATOMIC
                override val usesTemporaryDocument = false
                override fun openOutput(): OutputStream {
                    opens++
                    return object : OutputStream() {
                        override fun write(value: Int) { bytes.write(value) }
                        override fun write(data: ByteArray, offset: Int, length: Int) {
                            if (throwDuringWrite) throw IOException("Explicit synthetic provider short write")
                            bytes.write(data, offset, if (silentlyDropLastByte) length - 1 else length)
                        }
                    }
                }
                override fun openReadback() = ByteArrayInputStream(
                    if (corruptReadback) byteArrayOf(0, 1, 2) else bytes.toByteArray(),
                )
                override fun querySize() = bytes.size().toLong()
                override suspend fun commit(): DestinationCommitResult {
                    committed = true
                    onCommit()
                    return DestinationCommitResult.Committed(request.destinationUri, atomicity)
                }
                override suspend fun abort(deleteOwnedIncomplete: Boolean): CleanupResult {
                    aborted = true; deleteRequested = deleteOwnedIncomplete
                    if (request.ownership == DestinationOwnership.PRE_EXISTING) {
                        return CleanupResult(CleanupDisposition.PRE_EXISTING_RETAINED)
                    }
                    if (deleteOwnedIncomplete) { bytes.reset(); deleted = true; return CleanupResult(CleanupDisposition.OWNED_PARTIAL_DELETED) }
                    return CleanupResult(CleanupDisposition.OWNED_DELETE_UNSUPPORTED)
                }
            }
        }
    }
    private fun transferred(outcome: SafCoordinatorOutcome): TransferOutcome {
        assertTrue(outcome is SafCoordinatorOutcome.Transferred)
        return (outcome as SafCoordinatorOutcome.Transferred).outcome
    }

    @Test fun exactReadbackProducesReal026VerifiedSuccessAndRemovesPrivateStage() = temporary { root ->
        val source = Source(); val store = Store(); val coordinator = SafExportCoordinator(source, root); val fixed = request()
        val selection = coordinator.selection(fixed)
        assertEquals(DestinationSelectionMode.CREATE_NEW_DOCUMENT, selection.mode)
        assertEquals(fixed.requestId, selection.requestId)
        assertEquals(identity.projectId, selection.sourceIdentity.projectId)
        assertEquals("diagnostics", selection.sourceIdentity.representation)
        assertEquals("application/json", selection.mimeType)
        val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store))
        assertTrue(outcome is TransferOutcome.VerifiedSuccess)
        val receipt = (outcome as TransferOutcome.VerifiedSuccess).receipt
        assertEquals(CompletionEvidence.READ_BACK_SHA256, receipt.completionEvidence)
        assertEquals(bytes.size.toLong(), receipt.byteCount)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, receipt.sha256)
        assertTrue(store.committed); assertFalse(store.aborted)
        assertArrayEquals(bytes, store.bytes.toByteArray())
        assertEquals(1, source.materializations)
        assertTrue("Resolver must recheck source on every verify/open", source.currentChecks >= 9)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun providerCloseOrSizeOnlyIsNeverPromotedToVerifiedSuccess() = temporary { root ->
        for (size in listOf(false, true)) {
            val source = Source(); val store = Store(); val coordinator = SafExportCoordinator(source, root); val fixed = request()
            coordinator.selection(fixed)
            val outcome = transferred(coordinator.transfer(fixed, callback(fixed, readable = false, size = size), store))
            assertTrue(outcome is TransferOutcome.CompletedUnverified)
            val receipt = (outcome as TransferOutcome.CompletedUnverified).receipt
            assertEquals(if (size) CompletionEvidence.SIZE_ONLY else CompletionEvidence.PROVIDER_ACCEPTED_ONLY, receipt.completionEvidence)
            assertTrue(store.committed); assertEquals(0, root.listFiles()!!.size)
        }
    }

    @Test fun wrongPickerRequestIdCannotOpenOrConsumeTheCorrectRequest() = temporary { root ->
        val source = Source(); val store = Store(); val coordinator = SafExportCoordinator(source, root); val fixed = request()
        coordinator.selection(fixed)
        val mismatch = coordinator.transfer(fixed, callback(fixed).copy(requestId = UUID.randomUUID().toString()), store)
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "REQUEST_ID_MISMATCH"), mismatch)
        assertEquals(0, store.prepares); assertEquals(0, source.materializations)
        assertTrue(transferred(coordinator.transfer(fixed, callback(fixed), store)) is TransferOutcome.VerifiedSuccess)
    }

    @Test fun sameIdMutatedOptionsAreRejectedBeforeStagingOrDestination() = temporary { root ->
        val source = Source(); val store = Store(); val coordinator = SafExportCoordinator(source, root); val fixed = request()
        coordinator.selection(fixed)
        val mutated = fixed.copy(options = ExportOptions(editIdentity = "b".repeat(64)))
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "REQUEST_CHANGED"), coordinator.transfer(mutated, callback(fixed), store))
        assertEquals(0, store.prepares); assertEquals(0, source.materializations)
    }

    @Test fun restoredFixedRequestReauthorizesWithoutChoosingAnotherProject() = temporary { root ->
        val source = Source(); val fixed = request()
        val first = SafExportCoordinator(source, root)
        val picker = first.selection(fixed)
        val recreated = SafExportCoordinator(source, root)
        assertEquals(picker, recreated.selection(fixed))
        val store = Store()
        val outcome = transferred(recreated.transfer(fixed, callback(fixed), store)) as TransferOutcome.VerifiedSuccess
        assertEquals(identity.projectId, outcome.receipt.sourceIdentity.projectId)
        assertEquals(identity.revision, outcome.receipt.sourceIdentity.revision)
    }

    @Test fun duplicateOrAbandonedCallbacksCannotWriteTwice() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed)
        assertTrue(transferred(coordinator.transfer(fixed, callback(fixed), store)) is TransferOutcome.VerifiedSuccess)
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "UNKNOWN_REQUEST"), coordinator.transfer(fixed, callback(fixed), store))
        assertEquals(1, store.prepares)
        val cancelledPicker = request(); coordinator.selection(cancelledPicker); coordinator.abandon(cancelledPicker.requestId)
        assertEquals(SafCoordinatorOutcome.Rejected(cancelledPicker.requestId, "UNKNOWN_REQUEST"), coordinator.transfer(cancelledPicker, callback(cancelledPicker), store))
    }

    @Test fun preExistingDestinationsAreNeverOpenedOrDeletedByCreateOnlySelection() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        val prior = "pre-existing user document".toByteArray(); store.bytes.write(prior)
        coordinator.selection(fixed)
        val outcome = coordinator.transfer(fixed, callback(fixed).copy(ownership = DestinationOwnership.PRE_EXISTING), store)
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "OWNERSHIP_MISMATCH"), outcome)
        assertEquals(0, store.prepares); assertFalse(store.deleted); assertArrayEquals(prior, store.bytes.toByteArray())
    }

    @Test fun unavailableCatalogOptionCannotBeMaterializedAfterPicker() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed); source.catalog = emptyList()
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "OPTION_UNAVAILABLE"), coordinator.transfer(fixed, callback(fixed), store))
        assertEquals(0, source.materializations); assertEquals(0, store.prepares)
    }

    @Test fun sizeBudgetIsCheckedBeforeMaterializationAllocationOrPickerAuthorization() = temporary { root ->
        val source = Source(); val fixed = request(); val coordinator = SafExportCoordinator(source, root, 8)
        try { coordinator.selection(fixed); fail("Over-budget source was admitted") } catch (expected: IllegalStateException) { assertEquals("EXPORT_LIMIT", expected.message) }
        assertEquals(0, source.materializations); assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun invalidAndOversizedStagedArtifactsFailBeforeDestinationPreparation() = temporary { root ->
        for (oversize in listOf(false, true)) {
            val source = Source().apply { invalid = !oversize; this.oversize = oversize }
            val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
            coordinator.selection(fixed)
            assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, if (oversize) "INVALID_SIZE" else "INVALID_ARTIFACT"), coordinator.transfer(fixed, callback(fixed), store))
            assertEquals(0, store.prepares); assertEquals(0, root.listFiles()!!.size)
        }
    }

    @Test fun staleSourceAfterSelectionCannotOpenDestination() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed); source.current = false
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "STALE_ARTIFACT"), coordinator.transfer(fixed, callback(fixed), store))
        assertEquals(0, source.materializations); assertEquals(0, store.prepares)
    }

    @Test fun sourceRecheckedWhen026OpensAfterPreparingDestination() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store().apply { beforePrepare = { source.current = false } }
        val coordinator = SafExportCoordinator(source, root); coordinator.selection(fixed)
        val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store)) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertEquals(0, store.opens); assertTrue(store.aborted); assertFalse(store.committed)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun sourceChangedDuringTransferRetainsFailureAnd026CleanupEvidence() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed)
        val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store, progress = { source.current = false })) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertFalse(store.committed); assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun shortWritesAndWrongReadbackCannotBecomeSuccessfulExports() = temporary { root ->
        for (silent in listOf(false, true)) {
            val source = Source(); val fixed = request(); val store = Store().apply { throwDuringWrite = !silent; silentlyDropLastByte = silent }
            val coordinator = SafExportCoordinator(source, root); coordinator.selection(fixed)
            val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store)) as TransferOutcome.Failure
            assertEquals(if (silent) TransferFailureKind.DESTINATION_CONTENT_MISMATCH else TransferFailureKind.IO_FAILURE, outcome.kind)
            assertTrue(store.aborted); assertTrue(store.deleted); assertFalse(store.committed)
            assertEquals(0, root.listFiles()!!.size)
        }
    }

    @Test fun cancellationCleansStageAndPreservesTyped026CancelledOutcome() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); var cancel = false
        val coordinator = SafExportCoordinator(source, root); coordinator.selection(fixed)
        val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store, cancelled = { cancel }, progress = { cancel = true }))
        assertTrue(outcome is TransferOutcome.Cancelled)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, (outcome as TransferOutcome.Cancelled).cleanup.disposition)
        assertTrue(store.aborted); assertFalse(store.committed); assertEquals(0, root.listFiles()!!.size)
        assertArrayEquals("{\"synthetic\":true,\"representation\":\"sparse_points\"}".toByteArray(), bytes)
    }

    @Test fun cancellationBeforeStagingDoesNotPrepareDestination() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed)
        assertEquals(SafCoordinatorOutcome.Rejected(fixed.requestId, "CANCELLED"), coordinator.transfer(fixed, callback(fixed), store, cancelled = { true }))
        assertEquals(0, source.materializations); assertEquals(0, store.prepares); assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun coroutineCancellationRuns026CleanupAndRemovesPrivateStage() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store(); val coordinator = SafExportCoordinator(source, root)
        coordinator.selection(fixed)
        try {
            coordinator.transfer(fixed, callback(fixed), store, progress = { throw CancellationException("Explicit coroutine-cancellation regression") })
            fail("Coroutine cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(store.aborted); assertTrue(store.deleted); assertFalse(store.committed)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun finalSourceRecheckAfterProviderCommitPreventsStaleSuccess() = temporary { root ->
        val source = Source(); val fixed = request(); val store = Store().apply { onCommit = { source.current = false } }
        val coordinator = SafExportCoordinator(source, root); coordinator.selection(fixed)
        val outcome = transferred(coordinator.transfer(fixed, callback(fixed), store)) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertTrue(store.committed); assertTrue(store.aborted); assertTrue(store.deleted)
        assertEquals(0, root.listFiles()!!.size)
    }
}
