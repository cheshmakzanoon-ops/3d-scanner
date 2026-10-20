package com.realityforge.scanner.agents.a026

import com.realityforge.scanner.data.safexport.BoundDestinationRequest
import com.realityforge.scanner.data.safexport.CleanupDisposition
import com.realityforge.scanner.data.safexport.CleanupResult
import com.realityforge.scanner.data.safexport.CompletionEvidence
import com.realityforge.scanner.data.safexport.DestinationBindingResult
import com.realityforge.scanner.data.safexport.DestinationCapabilities
import com.realityforge.scanner.data.safexport.DestinationCommitResult
import com.realityforge.scanner.data.safexport.DestinationDocumentStore
import com.realityforge.scanner.data.safexport.DestinationOwnership
import com.realityforge.scanner.data.safexport.DestinationPermissionException
import com.realityforge.scanner.data.safexport.DestinationSelectionCallback
import com.realityforge.scanner.data.safexport.DestinationSelectionMode
import com.realityforge.scanner.data.safexport.DestinationSelectionRequest
import com.realityforge.scanner.data.safexport.DestinationStorageFullException
import com.realityforge.scanner.data.safexport.DestinationTransferService
import com.realityforge.scanner.data.safexport.DestinationUnavailableException
import com.realityforge.scanner.data.safexport.ExportSourceIdentity
import com.realityforge.scanner.data.safexport.PreparedDestination
import com.realityforge.scanner.data.safexport.ProviderAtomicity
import com.realityforge.scanner.data.safexport.SourceVerification
import com.realityforge.scanner.data.safexport.TransferCancellation
import com.realityforge.scanner.data.safexport.TransferFailureKind
import com.realityforge.scanner.data.safexport.TransferOutcome
import com.realityforge.scanner.data.safexport.TransferRecoveryAction
import com.realityforge.scanner.data.safexport.VerifiedExportSource
import com.realityforge.scanner.data.safexport.VerifiedExportSourceAccess
import com.realityforge.scanner.data.safexport.bind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTransferServiceTest {
    @Test
    fun exactReadBackHashProducesVerifiedSuccess() = runBlocking {
        val fixture = Fixture()
        val outcome = fixture.service.transfer(fixture.source, fixture.bound())

        assertTrue(outcome is TransferOutcome.VerifiedSuccess)
        val receipt = (outcome as TransferOutcome.VerifiedSuccess).receipt
        assertEquals(CompletionEvidence.READ_BACK_SHA256, receipt.completionEvidence)
        assertEquals(ProviderAtomicity.DIRECT_NON_ATOMIC, receipt.providerAtomicity)
        assertArrayEquals(fixture.bytes, fixture.store.document)
        assertEquals(0, fixture.store.deleteAttempts)
    }

    @Test
    fun shortWriteIsDetectedAndOnlyOwnedNewDocumentIsDeleted() = runBlocking {
        val fixture = Fixture(mode = StoreMode.SHORT_WRITE)
        val outcome = fixture.service.transfer(fixture.source, fixture.bound())

        assertTrue(outcome is TransferOutcome.Failure)
        outcome as TransferOutcome.Failure
        assertEquals(TransferFailureKind.DESTINATION_CONTENT_MISMATCH, outcome.kind)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertEquals(1, fixture.store.deleteAttempts)
        assertEquals(0, fixture.store.document.size)
    }

    @Test
    fun revokedPermissionAndUnavailableDestinationHaveTypedOutcomes() = runBlocking {
        val denied = Fixture(mode = StoreMode.PERMISSION_DENIED)
        val deniedOutcome = denied.service.transfer(denied.source, denied.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.PERMISSION_DENIED, deniedOutcome.kind)

        val missing = Fixture(mode = StoreMode.UNAVAILABLE)
        val missingOutcome = missing.service.transfer(missing.source, missing.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.DESTINATION_UNAVAILABLE, missingOutcome.kind)
        assertEquals(CleanupDisposition.NOT_NEEDED, missingOutcome.cleanup.disposition)
    }

    @Test
    fun diskFullAndRequestedCancellationNeverReportSuccess() = runBlocking {
        val full = Fixture(mode = StoreMode.STORAGE_FULL)
        val fullOutcome = full.service.transfer(full.source, full.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.DESTINATION_STORAGE_FULL, fullOutcome.kind)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, fullOutcome.cleanup.disposition)

        val cancelled = Fixture(bytes = ByteArray(80_000) { (it * 17).toByte() })
        var checks = 0
        val cancelledOutcome = cancelled.service.transfer(
            source = cancelled.source,
            destination = cancelled.bound(),
            cancellation = TransferCancellation { ++checks >= 5 },
        )
        assertTrue(cancelledOutcome is TransferOutcome.Cancelled)
        cancelledOutcome as TransferOutcome.Cancelled
        assertTrue(cancelledOutcome.bytesCopied in 1 until cancelled.bytes.size.toLong())
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, cancelledOutcome.cleanup.disposition)
    }

    @Test
    fun preExistingDocumentIsNeverDeletedWhenTransferFails() = runBlocking {
        val fixture = Fixture(mode = StoreMode.STORAGE_FULL)
        val outcome = fixture.service.transfer(
            fixture.source,
            fixture.bound(ownership = DestinationOwnership.PRE_EXISTING),
        ) as TransferOutcome.Failure

        assertEquals(CleanupDisposition.PRE_EXISTING_RETAINED, outcome.cleanup.disposition)
        assertEquals(0, fixture.store.deleteAttempts)
        assertTrue(fixture.store.document.isNotEmpty())
    }

    @Test
    fun pickerCallbackCannotSwitchSourceProjectOrRevision() = runBlocking {
        val fixture = Fixture()
        val request = DestinationSelectionRequest(
            requestId = "request-a",
            sourceIdentity = fixture.identity,
            displayName = fixture.source.displayName,
            mimeType = fixture.source.mimeType,
            mode = DestinationSelectionMode.CREATE_NEW_DOCUMENT,
        )
        val bound = request.bind(
            DestinationSelectionCallback(
                requestId = "request-a",
                destinationUri = "content://provider/document/new",
                ownership = DestinationOwnership.NEWLY_CREATED,
                capabilities = Fixture.CAPABILITIES,
            ),
        ) as DestinationBindingResult.Bound
        assertEquals(fixture.identity, bound.request.sourceIdentity)

        val switched = fixture.source.copy(
            identity = fixture.identity.copy(projectId = "other-project", revision = 99),
        )
        val outcome = fixture.service.transfer(switched, bound.request) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_BINDING_MISMATCH, outcome.kind)
        assertEquals(0, fixture.store.prepareCalls)
    }

    @Test
    fun sourceRevisionChangeAfterCopyCleansOwnedDestination() = runBlocking {
        val fixture = Fixture()
        fixture.sourceAccess.verifications.add(
            SourceVerification.Verified(
                identity = fixture.identity,
                byteCount = fixture.bytes.size.toLong(),
                sha256 = fixture.source.sha256,
            ),
        )
        fixture.sourceAccess.verifications.add(
            SourceVerification.Changed(
                currentIdentity = fixture.identity.copy(revision = fixture.identity.revision + 1),
                detail = "Project revision advanced.",
            ),
        )

        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertEquals(1, fixture.store.deleteAttempts)
    }


    @Test
    fun finalSourceVerificationRejectsRevisionChangeDuringCommit() = runBlocking {
        val fixture = Fixture()
        fixture.sourceAccess.verifications.add(
            SourceVerification.Verified(
                identity = fixture.identity,
                byteCount = fixture.bytes.size.toLong(),
                sha256 = fixture.source.sha256,
            ),
        )
        fixture.sourceAccess.verifications.add(
            SourceVerification.Verified(
                identity = fixture.identity,
                byteCount = fixture.bytes.size.toLong(),
                sha256 = fixture.source.sha256,
            ),
        )
        fixture.sourceAccess.verifications.add(
            SourceVerification.Changed(
                currentIdentity = fixture.identity.copy(revision = fixture.identity.revision + 1),
                detail = "Project revision advanced while the provider committed.",
            ),
        )

        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertEquals(fixture.bytes.size.toLong(), outcome.bytesCopied)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertEquals(1, fixture.store.deleteAttempts)
    }

    @Test
    fun requestedCancellationDuringReadBackCleansOwnedDestination() = runBlocking {
        val fixture = Fixture(bytes = ByteArray(24_321) { (it * 13 + 5).toByte() })
        var checks = 0
        val outcome = fixture.service.transfer(
            source = fixture.source,
            destination = fixture.bound(),
            cancellation = TransferCancellation { ++checks >= 12 },
        )

        assertTrue(outcome is TransferOutcome.Cancelled)
        outcome as TransferOutcome.Cancelled
        assertEquals(fixture.bytes.size.toLong(), outcome.bytesCopied)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertEquals(1, fixture.store.deleteAttempts)
    }


    @Test
    fun providerCannotClaimUnsupportedTemporaryAtomicTransfer() = runBlocking {
        val fixture = Fixture(mode = StoreMode.UNSUPPORTED_TEMP_ATOMIC)
        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure

        assertEquals(TransferFailureKind.DESTINATION_UNAVAILABLE, outcome.kind)
        assertTrue(outcome.detail.contains("temporary-document staging"))
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
        assertEquals(1, fixture.store.deleteAttempts)
    }

    @Test
    fun providerWithoutExactEvidenceReturnsExplicitUnverifiedCompletion() = runBlocking {
        val fixture = Fixture(capabilities = DestinationCapabilities.CONSERVATIVE)
        val outcome = fixture.service.transfer(fixture.source, fixture.bound())

        assertTrue(outcome is TransferOutcome.CompletedUnverified)
        outcome as TransferOutcome.CompletedUnverified
        assertEquals(CompletionEvidence.PROVIDER_ACCEPTED_ONLY, outcome.receipt.completionEvidence)
        assertTrue(outcome.warning.contains("no exact completion evidence"))
        assertTrue(outcome.warning.contains("atomic replacement was not available"))
        assertEquals(TransferRecoveryAction.REVIEW_PARTIAL_DESTINATION, outcome.recoveryAction)
    }

    @Test(timeout = 2_000)
    fun stalledSourceIsBoundedAndCannotReportSuccess() = runBlocking {
        val fixture = Fixture()
        var reads = 0
        fixture.sourceAccess.inputFactory = {
            object : InputStream() {
                override fun read(): Int = error("The transfer must use its bounded buffer.")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
            }
        }
        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_INVALID, outcome.kind)
        assertEquals(DestinationTransferService.MAX_STALLED_READS, reads)
        assertEquals(0L, outcome.bytesCopied)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
    }

    @Test(timeout = 2_000)
    fun stalledReadbackIsBoundedAndLeavesNoOwnedPartial() = runBlocking {
        val fixture = Fixture(mode = StoreMode.STALLED_READBACK)
        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.IO_FAILURE, outcome.kind)
        assertEquals(DestinationTransferService.MAX_STALLED_READS, fixture.store.readbackCalls)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
    }

    @Test(timeout = 2_000)
    fun endlessDestinationIsRejectedAfterTheExpectedLength() = runBlocking {
        val fixture = Fixture(mode = StoreMode.ENDLESS_READBACK)
        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.DESTINATION_CONTENT_MISMATCH, outcome.kind)
        assertTrue(fixture.store.readbackCalls <= fixture.bytes.size / 4096 + 1)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
    }

    @Test
    fun sourceGrowthNeverWritesPastTheVerifiedByteCount() = runBlocking {
        val fixture = Fixture()
        fixture.sourceAccess.inputFactory = { ByteArrayInputStream(fixture.bytes + ByteArray(16_000)) }
        val outcome = fixture.service.transfer(fixture.source, fixture.bound()) as TransferOutcome.Failure
        assertEquals(TransferFailureKind.SOURCE_CHANGED, outcome.kind)
        assertTrue(outcome.bytesCopied <= fixture.source.expectedByteCount)
        assertEquals(CleanupDisposition.OWNED_PARTIAL_DELETED, outcome.cleanup.disposition)
    }

    private class Fixture(
        val bytes: ByteArray = ByteArray(24_321) { (it * 31 + 7).toByte() },
        mode: StoreMode = StoreMode.NORMAL,
        private val capabilities: DestinationCapabilities = CAPABILITIES,
    ) {
        val identity = ExportSourceIdentity(
            projectId = "project-a",
            revision = 7,
            artifactKey = "artifact-0123456789abcdef",
            representation = "model/glb",
        )
        val source = VerifiedExportSource(
            identity = identity,
            expectedByteCount = bytes.size.toLong(),
            sha256 = sha256(bytes),
            displayName = "model.glb",
            mimeType = "model/gltf-binary",
        )
        val sourceAccess = FakeSourceAccess(source, bytes)
        val store = FakeDocumentStore(mode)
        val service = DestinationTransferService(sourceAccess, store, bufferSize = 4 * 1024)

        fun bound(ownership: DestinationOwnership = DestinationOwnership.NEWLY_CREATED) =
            BoundDestinationRequest(
                requestId = "request-a",
                sourceIdentity = identity,
                destinationUri = "content://provider/document/target",
                ownership = ownership,
                capabilities = capabilities.copy(
                    canDeleteOwnedIncomplete = ownership == DestinationOwnership.NEWLY_CREATED,
                ),
            )

        companion object {
            val CAPABILITIES = DestinationCapabilities(
                canReadBack = true,
                canQuerySize = true,
                canDeleteOwnedIncomplete = true,
                supportsTemporaryDocument = false,
                supportsAtomicCommit = false,
            )
        }
    }

    private class FakeSourceAccess(
        private val source: VerifiedExportSource,
        private val bytes: ByteArray,
    ) : VerifiedExportSourceAccess {
        val verifications = ArrayDeque<SourceVerification>()
        var inputFactory: (() -> InputStream)? = null

        override suspend fun verify(source: VerifiedExportSource): SourceVerification =
            verifications.removeFirstOrNull() ?: SourceVerification.Verified(
                identity = this.source.identity,
                byteCount = bytes.size.toLong(),
                sha256 = this.source.sha256,
            )

        override suspend fun open(source: VerifiedExportSource): InputStream = inputFactory?.invoke() ?: ByteArrayInputStream(bytes)
    }

    private enum class StoreMode {
        NORMAL,
        SHORT_WRITE,
        PERMISSION_DENIED,
        UNAVAILABLE,
        STORAGE_FULL,
        UNSUPPORTED_TEMP_ATOMIC,
        STALLED_READBACK,
        ENDLESS_READBACK,
    }

    private class FakeDocumentStore(
        private val mode: StoreMode,
    ) : DestinationDocumentStore {
        var document = ByteArray(0)
        var deleteAttempts = 0
        var prepareCalls = 0
        var readbackCalls = 0

        override suspend fun prepare(request: BoundDestinationRequest): PreparedDestination {
            prepareCalls++
            if (mode == StoreMode.UNAVAILABLE) {
                throw DestinationUnavailableException("Destination disappeared.")
            }
            return object : PreparedDestination {
                override val request = request
                override val writeUri = request.destinationUri
                override val atomicity = if (mode == StoreMode.UNSUPPORTED_TEMP_ATOMIC) {
                    ProviderAtomicity.ATOMIC_PROVIDER_COMMIT
                } else {
                    ProviderAtomicity.DIRECT_NON_ATOMIC
                }
                override val usesTemporaryDocument = mode == StoreMode.UNSUPPORTED_TEMP_ATOMIC
                private val sink = ByteArrayOutputStream()

                override fun openOutput(): OutputStream {
                    if (mode == StoreMode.PERMISSION_DENIED) {
                        throw DestinationPermissionException("Permission revoked.")
                    }
                    val delegate = object : OutputStream() {
                        private var accepted = 0

                        override fun write(value: Int) {
                            write(byteArrayOf(value.toByte()), 0, 1)
                        }

                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            when (mode) {
                                StoreMode.STORAGE_FULL -> {
                                    val remaining = (6_000 - accepted).coerceAtLeast(0)
                                    val count = minOf(remaining, length)
                                    if (count > 0) {
                                        sink.write(buffer, offset, count)
                                        accepted += count
                                        document = sink.toByteArray()
                                    }
                                    if (count < length) throw DestinationStorageFullException("No space left.")
                                }
                                StoreMode.SHORT_WRITE -> {
                                    val remaining = (5_000 - accepted).coerceAtLeast(0)
                                    val count = minOf(remaining, length)
                                    if (count > 0) {
                                        sink.write(buffer, offset, count)
                                        accepted += count
                                        document = sink.toByteArray()
                                    }
                                }
                                else -> {
                                    sink.write(buffer, offset, length)
                                    accepted += length
                                    document = sink.toByteArray()
                                }
                            }
                        }
                    }
                    return delegate
                }

                override fun openReadback(): InputStream? {
                    if (!request.capabilities.canReadBack) return null
                    if (mode !in setOf(StoreMode.STALLED_READBACK, StoreMode.ENDLESS_READBACK)) return ByteArrayInputStream(document)
                    return object : InputStream() {
                        override fun read(): Int = error("The transfer must use its bounded buffer.")
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            readbackCalls++
                            if (mode == StoreMode.STALLED_READBACK) return 0
                            bytes.fill(7, offset, offset + length)
                            return length
                        }
                    }
                }

                override fun querySize(): Long? =
                    if (request.capabilities.canQuerySize) document.size.toLong() else null

                override suspend fun commit(): DestinationCommitResult = DestinationCommitResult.Committed(
                    finalUri = request.destinationUri,
                    atomicity = atomicity,
                )

                override suspend fun abort(deleteOwnedIncomplete: Boolean): CleanupResult {
                    if (request.ownership == DestinationOwnership.PRE_EXISTING) {
                        return CleanupResult(CleanupDisposition.PRE_EXISTING_RETAINED)
                    }
                    if (!deleteOwnedIncomplete) {
                        return CleanupResult(CleanupDisposition.OWNED_DELETE_UNSUPPORTED)
                    }
                    deleteAttempts++
                    document = ByteArray(0)
                    return CleanupResult(CleanupDisposition.OWNED_PARTIAL_DELETED)
                }
            }
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
