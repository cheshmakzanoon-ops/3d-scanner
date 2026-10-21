package com.realityforge.scanner.data.safexport

private val SHA256_HEX = Regex("[0-9a-f]{64}")

data class ExportSourceIdentity(
    val projectId: String,
    val revision: Long,
    val artifactKey: String,
    val representation: String,
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(revision >= 0) { "revision must be non-negative" }
        require(artifactKey.isNotBlank()) { "artifactKey must not be blank" }
        require(representation.isNotBlank()) { "representation must not be blank" }
    }
}

data class VerifiedExportSource(
    val identity: ExportSourceIdentity,
    val expectedByteCount: Long,
    val sha256: String,
    val displayName: String,
    val mimeType: String,
) {
    init {
        require(expectedByteCount >= 0) { "expectedByteCount must be non-negative" }
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require('/' !in displayName && '\\' !in displayName) { "displayName must be a single file name" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}

enum class DestinationOwnership {
    NEWLY_CREATED,
    PRE_EXISTING,
}

enum class DestinationSelectionMode {
    CREATE_NEW_DOCUMENT,
    USE_PRE_EXISTING_DOCUMENT,
}

enum class ProviderAtomicity {
    ATOMIC_PROVIDER_COMMIT,
    DIRECT_NON_ATOMIC,
    UNKNOWN,
}

data class DestinationCapabilities(
    val canReadBack: Boolean,
    val canQuerySize: Boolean,
    val canDeleteOwnedIncomplete: Boolean,
    val supportsTemporaryDocument: Boolean,
    val supportsAtomicCommit: Boolean,
) {
    companion object {
        val CONSERVATIVE = DestinationCapabilities(
            canReadBack = false,
            canQuerySize = false,
            canDeleteOwnedIncomplete = false,
            supportsTemporaryDocument = false,
            supportsAtomicCommit = false,
        )
    }
}

data class DestinationSelectionRequest(
    val requestId: String,
    val sourceIdentity: ExportSourceIdentity,
    val displayName: String,
    val mimeType: String,
    val mode: DestinationSelectionMode,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require('/' !in displayName && '\\' !in displayName) { "displayName must be a single file name" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}

data class DestinationSelectionCallback(
    val requestId: String,
    val destinationUri: String,
    val ownership: DestinationOwnership,
    val capabilities: DestinationCapabilities,
)

data class BoundDestinationRequest(
    val requestId: String,
    val sourceIdentity: ExportSourceIdentity,
    val destinationUri: String,
    val ownership: DestinationOwnership,
    val capabilities: DestinationCapabilities,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(destinationUri.isNotBlank()) { "destinationUri must not be blank" }
    }
}

enum class DestinationBindingFailure {
    REQUEST_ID_MISMATCH,
    EMPTY_DESTINATION,
    OWNERSHIP_MISMATCH,
}

sealed interface DestinationBindingResult {
    data class Bound(val request: BoundDestinationRequest) : DestinationBindingResult

    data class Rejected(
        val reason: DestinationBindingFailure,
        val detail: String,
    ) : DestinationBindingResult
}

fun DestinationSelectionRequest.bind(
    callback: DestinationSelectionCallback,
): DestinationBindingResult {
    if (callback.requestId != requestId) {
        return DestinationBindingResult.Rejected(
            reason = DestinationBindingFailure.REQUEST_ID_MISMATCH,
            detail = "Destination callback does not belong to this export request.",
        )
    }
    if (callback.destinationUri.isBlank()) {
        return DestinationBindingResult.Rejected(
            reason = DestinationBindingFailure.EMPTY_DESTINATION,
            detail = "The document provider returned no destination.",
        )
    }
    val expectedOwnership = when (mode) {
        DestinationSelectionMode.CREATE_NEW_DOCUMENT -> DestinationOwnership.NEWLY_CREATED
        DestinationSelectionMode.USE_PRE_EXISTING_DOCUMENT -> DestinationOwnership.PRE_EXISTING
    }
    if (callback.ownership != expectedOwnership) {
        return DestinationBindingResult.Rejected(
            reason = DestinationBindingFailure.OWNERSHIP_MISMATCH,
            detail = "Destination ownership does not match the picker request.",
        )
    }
    return DestinationBindingResult.Bound(
        BoundDestinationRequest(
            requestId = requestId,
            sourceIdentity = sourceIdentity,
            destinationUri = callback.destinationUri,
            ownership = callback.ownership,
            capabilities = callback.capabilities,
        ),
    )
}
