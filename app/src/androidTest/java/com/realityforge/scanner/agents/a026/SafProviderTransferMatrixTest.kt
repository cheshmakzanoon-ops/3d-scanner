package com.realityforge.scanner.agents.a026

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.safexport.*
import com.realityforge.scanner.qa.FaultDocumentProvider
import com.realityforge.scanner.qa.FaultDocumentProvider.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the real ContentResolver adapter and the single shared test DocumentsProvider. */
@RunWith(AndroidJUnit4::class)
class SafProviderTransferMatrixTest {
    @Test fun exactAndKernelShortWritesCompleteOnlyAfterReadbackHashMatches() = runBlocking {
        withSource { fixture ->
            for (mode in listOf(Mode.EXACT, Mode.SHORT_WRITES)) fixture.withDestination(mode) { uri ->
                val outcome = fixture.transfer.transfer(fixture.source, fixture.request(uri))
                assertTrue(outcome.toString(), outcome is TransferOutcome.VerifiedSuccess)
                val receipt = (outcome as TransferOutcome.VerifiedSuccess).receipt
                assertEquals(CompletionEvidence.READ_BACK_SHA256, receipt.completionEvidence)
                assertEquals(ProviderAtomicity.DIRECT_NON_ATOMIC, receipt.providerAtomicity)
                assertEquals(fixture.source.expectedByteCount, receipt.byteCount)
                assertEquals(fixture.source.sha256, FaultDocumentProvider.hash(fixture.context, uri))
                assertArrayEquals(fixture.file.readBytes(), FaultDocumentProvider.read(fixture.context, uri))
                if (mode == Mode.SHORT_WRITES)
                    assertTrue(FaultDocumentProvider.inspect(fixture.context, uri).shortWriteCalls > 0)
            }
        }
    }

    @Test fun providerAcknowledgingDiscardedBytesCannotProduceSuccessAndCleansOnlyOwnedDocument() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.SILENT_TRUNCATION) { uri ->
            val result = failure(fixture.transfer.transfer(fixture.source, fixture.request(uri)))
            assertEquals(TransferFailureKind.DESTINATION_CONTENT_MISMATCH, result.kind)
            assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, result.cleanup.disposition)
            assertFalse(FaultDocumentProvider.inspect(fixture.context, uri).exists)
        } }
    }

    @Test fun actualProxyDescriptorEnospcIsReportedAsDestinationStorageFull() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.DISK_FULL) { uri ->
            val result = failure(fixture.transfer.transfer(fixture.source, fixture.request(uri)))
            assertEquals(result.detail, TransferFailureKind.DESTINATION_STORAGE_FULL, result.kind)
            assertEquals(TransferRecoveryAction.FREE_DESTINATION_SPACE, result.recoveryAction)
            assertTrue(result.bytesCopied > 0)
            assertTrue(result.bytesCopied < fixture.source.expectedByteCount)
            assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, result.cleanup.disposition)
            assertFalse(FaultDocumentProvider.inspect(fixture.context, uri).exists)
        } }
    }

    @Test fun revokedInitialAndMidTransferUriGrantsNeverBecomeVerifiedSuccess() = runBlocking {
        withSource { fixture ->
            for (mode in listOf(Mode.REVOKED, Mode.REVOKE_AFTER_WRITE)) fixture.withDestination(mode) { uri ->
                val result = failure(fixture.transfer.transfer(fixture.source, fixture.request(uri)))
                assertEquals(result.detail, TransferFailureKind.PERMISSION_DENIED, result.kind)
                assertEquals(TransferRecoveryAction.REAUTHORIZE_DESTINATION, result.recoveryAction)
                // Revocation prevents cleanup too: keep a truthful partial-document receipt.
                assertEquals(CleanupDisposition.OWNED_DELETE_FAILED, result.cleanup.disposition)
                assertTrue(FaultDocumentProvider.inspect(fixture.context, uri).exists)
                if (mode == Mode.REVOKED) assertEquals(0L, result.bytesCopied)
                else assertTrue(FaultDocumentProvider.inspect(fixture.context, uri).writeCalls > 0)
            }
        }
    }

    @Test fun unavailableDocumentIsActionableAndTheNewOwnedPlaceholderIsRemoved() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.UNAVAILABLE) { uri ->
            val result = failure(fixture.transfer.transfer(fixture.source, fixture.request(uri)))
            assertEquals(TransferFailureKind.DESTINATION_UNAVAILABLE, result.kind)
            assertEquals(TransferRecoveryAction.PICK_DESTINATION_AGAIN, result.recoveryAction)
            assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, result.cleanup.disposition)
            assertFalse(FaultDocumentProvider.inspect(fixture.context, uri).exists)
        } }
    }

    @Test fun cancellationAfterWrittenBytesDeletesOnlyNewOwnedPartial() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.EXACT) { uri ->
            val cancel = AtomicBoolean(false)
            val outcome = fixture.transfer.transfer(fixture.source, fixture.request(uri), TransferCancellation { cancel.get() },
                onProgress = { if (it.bytesCopied >= 4096) cancel.set(true) })
            assertTrue(outcome.toString(), outcome is TransferOutcome.Cancelled)
            val cancelled = outcome as TransferOutcome.Cancelled
            assertTrue(cancelled.bytesCopied > 0 && cancelled.bytesCopied < fixture.source.expectedByteCount)
            assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, cancelled.cleanup.disposition)
            assertFalse(FaultDocumentProvider.inspect(fixture.context, uri).exists)
        } }
    }

    @Test fun cancellingPreexistingDestinationRetainsItsPartialDocumentForUserRecovery() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.EXACT, ByteArray(8192) { 9 }) { uri ->
            val cancel = AtomicBoolean(false)
            val outcome = fixture.transfer.transfer(fixture.source, fixture.request(uri, DestinationOwnership.PRE_EXISTING),
                TransferCancellation { cancel.get() }, onProgress = { cancel.set(true) })
            assertTrue(outcome.toString(), outcome is TransferOutcome.Cancelled)
            assertEquals(CleanupDisposition.PRE_EXISTING_RETAINED, (outcome as TransferOutcome.Cancelled).cleanup.disposition)
            val retained = FaultDocumentProvider.inspect(fixture.context, uri)
            assertTrue(retained.exists)
            assertTrue(retained.bytes > 0 && retained.bytes < fixture.source.expectedByteCount)
        } }
    }

    @Test fun deniedWriteToPreexistingDocumentRetainsItsOriginalBytes() = runBlocking {
        withSource { fixture ->
            val original = ByteArray(8192) { ((it + 19) % 253).toByte() }
            fixture.withDestination(Mode.REVOKED, original) { uri ->
                val result = failure(fixture.transfer.transfer(fixture.source, fixture.request(uri, DestinationOwnership.PRE_EXISTING)))
                assertEquals(TransferFailureKind.PERMISSION_DENIED, result.kind)
                assertEquals(CleanupDisposition.PRE_EXISTING_RETAINED, result.cleanup.disposition)
                assertArrayEquals(original, FaultDocumentProvider.read(fixture.context, uri))
            }
        }
    }

    @Test fun stalePickerBindingIsRejectedBeforeAnyProviderWriteOrCleanup() = runBlocking {
        withSource { fixture ->
            val initial = byteArrayOf(7, 8, 9)
            fixture.withDestination(Mode.EXACT, initial) { uri ->
                val request = fixture.request(uri).copy(sourceIdentity = fixture.source.identity.copy(revision = 2))
                val result = failure(fixture.transfer.transfer(fixture.source, request))
                assertEquals(TransferFailureKind.SOURCE_BINDING_MISMATCH, result.kind)
                assertEquals(CleanupDisposition.NOT_NEEDED, result.cleanup.disposition)
                assertEquals(0L, FaultDocumentProvider.inspect(fixture.context, uri).writeCalls)
                assertArrayEquals(initial, FaultDocumentProvider.read(fixture.context, uri))
            }
        }
    }

    @Test fun unsupportedReadbackKeepsCompletionExplicitlyUnverified() = runBlocking {
        withSource { fixture -> fixture.withDestination(Mode.EXACT) { uri ->
            val request = fixture.request(uri).copy(capabilities = DestinationCapabilities.CONSERVATIVE)
            val outcome = fixture.transfer.transfer(fixture.source, request)
            assertTrue(outcome.toString(), outcome is TransferOutcome.CompletedUnverified)
            assertEquals(CompletionEvidence.PROVIDER_ACCEPTED_ONLY,
                (outcome as TransferOutcome.CompletedUnverified).receipt.completionEvidence)
            assertEquals(fixture.source.sha256, FaultDocumentProvider.hash(fixture.context, uri))
        } }
    }

    private class SourceFixture(val context: Context, val file: File, val source: VerifiedExportSource) {
        val transfer = DestinationTransferService(FileVerifiedExportSourceAccess(ExportSourceFileResolver { identity ->
            if (identity == source.identity) ResolvedExportFile(identity, file) else null
        }), AndroidSafDestinationStore(context), bufferSize = 4096)

        fun request(uri: Uri, ownership: DestinationOwnership = DestinationOwnership.NEWLY_CREATED) = BoundDestinationRequest(
            UUID.randomUUID().toString(), source.identity, uri.toString(), ownership,
            DestinationCapabilities(canReadBack = true, canQuerySize = true, canDeleteOwnedIncomplete = true,
                supportsTemporaryDocument = false, supportsAtomicCommit = false))

        suspend fun withDestination(mode: Mode, initial: ByteArray = ByteArray(0), block: suspend (Uri) -> Unit) {
            val uri = FaultDocumentProvider.createUri(context, mode, initial)
            try { block(uri) } finally { FaultDocumentProvider.cleanup(context, uri) }
        }
    }

    private suspend fun withSource(block: suspend (SourceFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "saf-source-${UUID.randomUUID()}.fixture")
        val original = ByteArray(128 * 1024) { ((it * 31 + it / 127) % 251).toByte() }
        val unrelated = byteArrayOf(41, 42, 43, 44)
        val otherUri = FaultDocumentProvider.createUri(context, Mode.EXACT, unrelated)
        source.writeBytes(original)
        try {
            val descriptor = VerifiedExportSource(ExportSourceIdentity("fixture-project", 1, "fixture-artifact", "archive/zip"),
                source.length(), sha256(original), "fixture.zip", "application/zip")
            block(SourceFixture(context, source, descriptor))
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(unrelated, FaultDocumentProvider.read(context, otherUri))
        } finally {
            source.delete()
            FaultDocumentProvider.cleanup(context, otherUri)
        }
    }

    private fun failure(outcome: TransferOutcome): TransferOutcome.Failure {
        assertTrue(outcome.toString(), outcome is TransferOutcome.Failure)
        return outcome as TransferOutcome.Failure
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
