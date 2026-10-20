package com.realityforge.scanner.data.safexport

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DestinationTransferService(
    private val sourceAccess: VerifiedExportSourceAccess,
    private val destinationStore: DestinationDocumentStore,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init {
        require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) {
            "bufferSize must be between $MIN_BUFFER_SIZE and $MAX_BUFFER_SIZE bytes"
        }
    }

    suspend fun transfer(
        source: VerifiedExportSource,
        destination: BoundDestinationRequest,
        cancellation: TransferCancellation = TransferCancellation.NONE,
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferOutcome {
        currentCoroutineContext().ensureActive()
        if (cancellation.isCancellationRequested()) {
            return TransferOutcome.Cancelled(
                requestId = destination.requestId,
                bytesCopied = 0,
                cleanup = CleanupResult(CleanupDisposition.NOT_NEEDED),
                recoveryAction = TransferRecoveryAction.PICK_DESTINATION_AGAIN,
            )
        }
        if (source.identity != destination.sourceIdentity) {
            return failure(
                destination = destination,
                kind = TransferFailureKind.SOURCE_BINDING_MISMATCH,
                detail = "The selected destination belongs to a different project revision or artifact.",
                bytesCopied = 0,
                cleanup = CleanupResult(CleanupDisposition.NOT_NEEDED),
                action = TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        }

        val before = try {
            sourceAccess.verify(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(
                destination = destination,
                kind = TransferFailureKind.SOURCE_INVALID,
                detail = error.message ?: "The export source could not be verified.",
                bytesCopied = 0,
                cleanup = CleanupResult(CleanupDisposition.NOT_NEEDED),
                action = TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        }
        sourceVerificationFailure(source, before, destination, afterCopy = false)?.let { return it }

        var prepared: PreparedDestination? = null
        var bytesCopied = 0L
        try {
            checkCancellation(cancellation)
            prepared = destinationStore.prepare(destination)
            val invalidPreparation = when {
                prepared.request != destination || prepared.writeUri.isBlank() ->
                    "The document provider prepared a destination for a different request."
                prepared.usesTemporaryDocument && !destination.capabilities.supportsTemporaryDocument ->
                    "The provider attempted temporary-document staging without an advertised capability."
                prepared.atomicity == ProviderAtomicity.ATOMIC_PROVIDER_COMMIT &&
                    !destination.capabilities.supportsAtomicCommit ->
                    "The provider claimed an atomic commit without an advertised capability."
                else -> null
            }
            if (invalidPreparation != null) {
                val cleanup = cleanup(prepared, destination)
                return failure(
                    destination = destination,
                    kind = TransferFailureKind.DESTINATION_UNAVAILABLE,
                    detail = invalidPreparation,
                    bytesCopied = 0,
                    cleanup = cleanup,
                    action = partialDestinationAction(cleanup),
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            sourceAccess.open(source).use { input ->
                prepared.openOutput().use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        checkCancellation(cancellation)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytesCopied = Math.addExact(bytesCopied, count.toLong())
                        if (bytesCopied > source.expectedByteCount) {
                            throw SourceChangedException("The source grew while it was being exported.")
                        }
                        onProgress(
                            TransferProgress(
                                requestId = destination.requestId,
                                bytesCopied = bytesCopied,
                                expectedByteCount = source.expectedByteCount,
                            ),
                        )
                    }
                    output.flush()
                }
            }
            checkCancellation(cancellation)

            val copiedHash = digest.digest().toHex()
            if (bytesCopied != source.expectedByteCount || copiedHash != source.sha256) {
                val cleanup = cleanup(prepared, destination)
                return failure(
                    destination = destination,
                    kind = TransferFailureKind.SOURCE_CHANGED,
                    detail = "The source bytes no longer match the verified export artifact.",
                    bytesCopied = bytesCopied,
                    cleanup = cleanup,
                    action = TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
                )
            }

            val after = sourceAccess.verify(source)
            sourceVerificationFailure(source, after, destination, afterCopy = true)?.let { problem ->
                val cleanup = cleanup(prepared, destination)
                return problem.copy(cleanup = cleanup, bytesCopied = bytesCopied)
            }

            val inspection = inspectDestination(prepared, source, cancellation)
            if (inspection is DestinationInspection.Mismatch) {
                val cleanup = cleanup(prepared, destination)
                return failure(
                    destination = destination,
                    kind = TransferFailureKind.DESTINATION_CONTENT_MISMATCH,
                    detail = inspection.detail,
                    bytesCopied = bytesCopied,
                    cleanup = cleanup,
                    action = partialDestinationAction(cleanup),
                )
            }

            checkCancellation(cancellation)
            val committed = prepared.commit()
            if (committed is DestinationCommitResult.Failed) {
                val cleanup = cleanup(prepared, destination)
                return failure(
                    destination = destination,
                    kind = TransferFailureKind.DESTINATION_COMMIT_FAILED,
                    detail = committed.detail,
                    bytesCopied = bytesCopied,
                    cleanup = cleanup,
                    action = partialDestinationAction(cleanup),
                )
            }
            committed as DestinationCommitResult.Committed
            checkCancellation(cancellation)

            val finalVerification = sourceAccess.verify(source)
            sourceVerificationFailure(source, finalVerification, destination, afterCopy = true)?.let { problem ->
                val cleanup = cleanup(prepared, destination)
                return problem.copy(cleanup = cleanup, bytesCopied = bytesCopied)
            }

            val receipt = TransferReceipt(
                requestId = destination.requestId,
                sourceIdentity = source.identity,
                finalDestinationUri = committed.finalUri,
                byteCount = bytesCopied,
                sha256 = source.sha256,
                completionEvidence = inspection.evidence,
                providerAtomicity = committed.atomicity,
                usedTemporaryDocument = prepared.usesTemporaryDocument,
            )
            return if (inspection.evidence == CompletionEvidence.READ_BACK_SHA256) {
                TransferOutcome.VerifiedSuccess(receipt)
            } else {
                TransferOutcome.CompletedUnverified(
                    receipt = receipt,
                    warning = completionWarning(inspection.evidence, committed.atomicity),
                    recoveryAction = TransferRecoveryAction.REVIEW_PARTIAL_DESTINATION,
                )
            }
        } catch (requested: RequestedTransferCancellation) {
            return TransferOutcome.Cancelled(
                requestId = destination.requestId,
                bytesCopied = bytesCopied,
                cleanup = cleanup(prepared, destination),
                recoveryAction = TransferRecoveryAction.PICK_DESTINATION_AGAIN,
            )
        } catch (cancelled: CancellationException) {
            cleanup(prepared, destination)
            throw cancelled
        } catch (error: DestinationPermissionException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.PERMISSION_DENIED,
                error.message ?: "Destination permission was revoked.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.REAUTHORIZE_DESTINATION,
            )
        } catch (error: DestinationStorageFullException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.DESTINATION_STORAGE_FULL,
                error.message ?: "The destination has insufficient storage.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.FREE_DESTINATION_SPACE,
            )
        } catch (error: DestinationUnavailableException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.DESTINATION_UNAVAILABLE,
                error.message ?: "The destination is unavailable.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.PICK_DESTINATION_AGAIN,
            )
        } catch (error: SourceChangedException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.SOURCE_CHANGED,
                error.message ?: "The source changed during transfer.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        } catch (error: SourceAccessException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.SOURCE_INVALID,
                error.message ?: "The source is unavailable.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        } catch (error: IOException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.IO_FAILURE,
                error.message ?: "The transfer failed with an I/O error.",
                bytesCopied,
                cleanup,
                partialDestinationAction(cleanup),
            )
        } catch (error: ArithmeticException) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.SOURCE_INVALID,
                "The source length exceeded the supported range.",
                bytesCopied,
                cleanup,
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        } catch (error: Exception) {
            val cleanup = cleanup(prepared, destination)
            return failure(
                destination,
                TransferFailureKind.IO_FAILURE,
                error.message ?: "The document provider failed unexpectedly.",
                bytesCopied,
                cleanup,
                partialDestinationAction(cleanup),
            )
        }
    }

    private suspend fun checkCancellation(cancellation: TransferCancellation) {
        currentCoroutineContext().ensureActive()
        if (cancellation.isCancellationRequested()) throw RequestedTransferCancellation()
    }

    private suspend fun inspectDestination(
        prepared: PreparedDestination,
        source: VerifiedExportSource,
        cancellation: TransferCancellation,
    ): DestinationInspection {
        checkCancellation(cancellation)
        if (prepared.request.capabilities.canReadBack) {
            val stream = prepared.openReadback()
                ?: return DestinationInspection.Mismatch("The provider promised read-back but returned no readable document.")
            val digest = MessageDigest.getInstance("SHA-256")
            val count = stream.use { hashStream(it, digest, cancellation) }
            val hash = digest.digest().toHex()
            return if (count == source.expectedByteCount && hash == source.sha256) {
                DestinationInspection.Match(CompletionEvidence.READ_BACK_SHA256)
            } else {
                DestinationInspection.Mismatch(
                    "Destination read-back did not match the expected length and SHA-256.",
                )
            }
        }
        if (prepared.request.capabilities.canQuerySize) {
            checkCancellation(cancellation)
            val size = prepared.querySize()
            checkCancellation(cancellation)
            if (size != null && size != source.expectedByteCount) {
                return DestinationInspection.Mismatch(
                    "The provider reported $size bytes; ${source.expectedByteCount} were expected.",
                )
            }
            return if (size == source.expectedByteCount) {
                DestinationInspection.Match(CompletionEvidence.SIZE_ONLY)
            } else {
                DestinationInspection.Match(CompletionEvidence.PROVIDER_ACCEPTED_ONLY)
            }
        }
        return DestinationInspection.Match(CompletionEvidence.PROVIDER_ACCEPTED_ONLY)
    }

    private fun sourceVerificationFailure(
        source: VerifiedExportSource,
        verification: SourceVerification,
        destination: BoundDestinationRequest,
        afterCopy: Boolean,
    ): TransferOutcome.Failure? {
        val prefix = if (afterCopy) "The source changed during transfer" else "The source is not exportable"
        return when (verification) {
            is SourceVerification.Verified -> {
                if (
                    verification.identity == source.identity &&
                    verification.byteCount == source.expectedByteCount &&
                    verification.sha256 == source.sha256
                ) {
                    null
                } else {
                    failure(
                        destination = destination,
                        kind = if (afterCopy) TransferFailureKind.SOURCE_CHANGED else TransferFailureKind.SOURCE_INVALID,
                        detail = "$prefix: its identity, length, or SHA-256 no longer matches.",
                        bytesCopied = 0,
                        cleanup = CleanupResult(CleanupDisposition.NOT_NEEDED),
                        action = TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
                    )
                }
            }
            is SourceVerification.Missing -> failure(
                destination,
                if (afterCopy) TransferFailureKind.SOURCE_CHANGED else TransferFailureKind.SOURCE_INVALID,
                "$prefix: ${verification.detail}",
                0,
                CleanupResult(CleanupDisposition.NOT_NEEDED),
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
            is SourceVerification.Changed -> failure(
                destination,
                TransferFailureKind.SOURCE_CHANGED,
                "$prefix: ${verification.detail}",
                0,
                CleanupResult(CleanupDisposition.NOT_NEEDED),
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
            is SourceVerification.Invalid -> failure(
                destination,
                if (afterCopy) TransferFailureKind.SOURCE_CHANGED else TransferFailureKind.SOURCE_INVALID,
                "$prefix: ${verification.detail}",
                0,
                CleanupResult(CleanupDisposition.NOT_NEEDED),
                TransferRecoveryAction.REBUILD_OR_RESELECT_SOURCE,
            )
        }
    }

    private suspend fun cleanup(
        prepared: PreparedDestination?,
        destination: BoundDestinationRequest,
    ): CleanupResult {
        if (prepared == null) return CleanupResult(CleanupDisposition.NOT_NEEDED)
        val allowDelete = destination.ownership == DestinationOwnership.NEWLY_CREATED &&
            destination.capabilities.canDeleteOwnedIncomplete
        return withContext(NonCancellable) {
            try {
                prepared.abort(allowDelete)
            } catch (error: Exception) {
                CleanupResult(
                    disposition = if (destination.ownership == DestinationOwnership.PRE_EXISTING) {
                        CleanupDisposition.PRE_EXISTING_RETAINED
                    } else {
                        CleanupDisposition.OWNED_DELETE_FAILED
                    },
                    detail = error.message,
                )
            }
        }
    }

    private fun failure(
        destination: BoundDestinationRequest,
        kind: TransferFailureKind,
        detail: String,
        bytesCopied: Long,
        cleanup: CleanupResult,
        action: TransferRecoveryAction,
    ) = TransferOutcome.Failure(
        requestId = destination.requestId,
        kind = kind,
        detail = detail,
        bytesCopied = bytesCopied,
        cleanup = cleanup,
        recoveryAction = action,
    )

    private fun partialDestinationAction(cleanup: CleanupResult): TransferRecoveryAction =
        when (cleanup.disposition) {
            CleanupDisposition.OWNED_PARTIAL_DELETED,
            CleanupDisposition.NOT_NEEDED,
            -> TransferRecoveryAction.RETRY
            CleanupDisposition.PRE_EXISTING_RETAINED,
            CleanupDisposition.OWNED_DELETE_UNSUPPORTED,
            CleanupDisposition.OWNED_DELETE_FAILED,
            CleanupDisposition.UNKNOWN,
            -> TransferRecoveryAction.REVIEW_PARTIAL_DESTINATION
        }

    private fun completionWarning(
        evidence: CompletionEvidence,
        atomicity: ProviderAtomicity,
    ): String = buildString {
        append(
            when (evidence) {
                CompletionEvidence.SIZE_ONLY -> "The provider reported the expected size, but exact bytes could not be read back."
                CompletionEvidence.PROVIDER_ACCEPTED_ONLY -> "The provider accepted the stream, but supplied no exact completion evidence."
                CompletionEvidence.READ_BACK_SHA256 -> "Exact destination bytes were verified."
            },
        )
        if (atomicity != ProviderAtomicity.ATOMIC_PROVIDER_COMMIT) {
            append(" Provider atomic replacement was not available.")
        }
    }

    private suspend fun hashStream(
        input: InputStream,
        digest: MessageDigest,
        cancellation: TransferCancellation,
    ): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            checkCancellation(cancellation)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            total = Math.addExact(total, count.toLong())
        }
        return total
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private sealed interface DestinationInspection {
        val evidence: CompletionEvidence

        data class Match(override val evidence: CompletionEvidence) : DestinationInspection

        data class Mismatch(val detail: String) : DestinationInspection {
            override val evidence = CompletionEvidence.PROVIDER_ACCEPTED_ONLY
        }
    }

    private class RequestedTransferCancellation : RuntimeException()

    companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val MIN_BUFFER_SIZE = 4 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
    }
}
