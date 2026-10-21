package com.realityforge.scanner.data.safexport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed interface SourceVerification {
    data class Verified(
        val identity: ExportSourceIdentity,
        val byteCount: Long,
        val sha256: String,
    ) : SourceVerification

    data class Missing(val detail: String) : SourceVerification

    data class Changed(
        val currentIdentity: ExportSourceIdentity?,
        val detail: String,
    ) : SourceVerification

    data class Invalid(val detail: String) : SourceVerification
}

interface VerifiedExportSourceAccess {
    suspend fun verify(source: VerifiedExportSource): SourceVerification

    suspend fun open(source: VerifiedExportSource): InputStream
}

fun interface TransferCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = TransferCancellation { false }
    }
}

data class TransferProgress(
    val requestId: String,
    val bytesCopied: Long,
    val expectedByteCount: Long,
)

enum class CompletionEvidence {
    READ_BACK_SHA256,
    SIZE_ONLY,
    PROVIDER_ACCEPTED_ONLY,
}

enum class CleanupDisposition {
    NOT_NEEDED,
    OWNED_PARTIAL_DELETED,
    PRE_EXISTING_RETAINED,
    OWNED_DELETE_UNSUPPORTED,
    OWNED_DELETE_FAILED,
    UNKNOWN,
}

data class CleanupResult(
    val disposition: CleanupDisposition,
    val detail: String? = null,
)

sealed interface DestinationCommitResult {
    data class Committed(
        val finalUri: String,
        val atomicity: ProviderAtomicity,
    ) : DestinationCommitResult

    data class Failed(val detail: String) : DestinationCommitResult
}

interface PreparedDestination {
    val request: BoundDestinationRequest
    val writeUri: String
    val atomicity: ProviderAtomicity
    val usesTemporaryDocument: Boolean

    fun openOutput(): OutputStream

    fun openReadback(): InputStream?

    fun querySize(): Long?

    suspend fun commit(): DestinationCommitResult

    suspend fun abort(deleteOwnedIncomplete: Boolean): CleanupResult
}

interface DestinationDocumentStore {
    suspend fun prepare(request: BoundDestinationRequest): PreparedDestination
}

open class SourceAccessException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class SourceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : SourceAccessException(message, cause)

class SourceChangedException(
    message: String,
    cause: Throwable? = null,
) : SourceAccessException(message, cause)

open class DestinationAccessException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class DestinationPermissionException(
    message: String,
    cause: Throwable? = null,
) : DestinationAccessException(message, cause)

class DestinationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : DestinationAccessException(message, cause)

class DestinationStorageFullException(
    message: String,
    cause: Throwable? = null,
) : DestinationAccessException(message, cause)

class DestinationWriteException(
    message: String,
    cause: Throwable? = null,
) : DestinationAccessException(message, cause)

enum class TransferFailureKind {
    SOURCE_BINDING_MISMATCH,
    SOURCE_INVALID,
    SOURCE_CHANGED,
    PERMISSION_DENIED,
    DESTINATION_UNAVAILABLE,
    DESTINATION_STORAGE_FULL,
    DESTINATION_CONTENT_MISMATCH,
    DESTINATION_COMMIT_FAILED,
    IO_FAILURE,
}

enum class TransferRecoveryAction {
    REBUILD_OR_RESELECT_SOURCE,
    PICK_DESTINATION_AGAIN,
    REAUTHORIZE_DESTINATION,
    FREE_DESTINATION_SPACE,
    REVIEW_PARTIAL_DESTINATION,
    RETRY,
    NONE,
}

data class TransferReceipt(
    val requestId: String,
    val sourceIdentity: ExportSourceIdentity,
    val finalDestinationUri: String,
    val byteCount: Long,
    val sha256: String,
    val completionEvidence: CompletionEvidence,
    val providerAtomicity: ProviderAtomicity,
    val usedTemporaryDocument: Boolean,
)

sealed interface TransferOutcome {
    data class VerifiedSuccess(val receipt: TransferReceipt) : TransferOutcome

    data class CompletedUnverified(
        val receipt: TransferReceipt,
        val warning: String,
        val recoveryAction: TransferRecoveryAction = TransferRecoveryAction.NONE,
    ) : TransferOutcome

    data class Cancelled(
        val requestId: String,
        val bytesCopied: Long,
        val cleanup: CleanupResult,
        val recoveryAction: TransferRecoveryAction,
    ) : TransferOutcome

    data class Failure(
        val requestId: String,
        val kind: TransferFailureKind,
        val detail: String,
        val bytesCopied: Long,
        val cleanup: CleanupResult,
        val recoveryAction: TransferRecoveryAction,
    ) : TransferOutcome
}
