package com.realityforge.scanner.data.publication

import java.io.File

/**
 * Immutable identity for one publication attempt. The metadata owner must bind the
 * operation id and attempt to a durable job record before a session is opened.
 */
data class PublicationIdentity(
    val purpose: PublicationPurpose,
    val projectId: String,
    val projectRevision: Long,
    val operationId: String,
    val attempt: Long,
    val inputIdentitySha256: String,
    val settingsIdentitySha256: String,
    val engineVersion: String,
) {
    init {
        requireSafeToken(projectId, "projectId")
        require(projectRevision >= 0) { "projectRevision must be non-negative." }
        requireSafeToken(operationId, "operationId")
        require(attempt > 0) { "attempt must be positive." }
        requireSha256(inputIdentitySha256, "inputIdentitySha256")
        requireSha256(settingsIdentitySha256, "settingsIdentitySha256")
        require(engineVersion.length in 1..128 && engineVersion.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "engineVersion is invalid."
        }
    }
}

enum class PublicationPurpose {
    RESULT_SET,
    ORIGINAL_IMPORT,
}

enum class ArtifactPresence {
    PRESENT,
    ABSENT,
}

/** A producer-owned declaration. Paths are relative to one immutable artifact-set root. */
data class ArtifactDeclaration(
    val logicalName: String,
    val relativePath: String,
    val representation: String,
    val required: Boolean,
    val maxBytes: Long,
) {
    init {
        requireSafeName(logicalName, "logicalName")
        requireSafeRelativePath(relativePath)
        requireSafeName(representation, "representation")
        require(maxBytes in 1..MAX_ARTIFACT_BYTES) { "maxBytes is outside the supported range." }
    }
}

data class ArtifactManifestEntry(
    val declaration: ArtifactDeclaration,
    val presence: ArtifactPresence,
    val bytes: Long,
    val sha256: String?,
) {
    init {
        when (presence) {
            ArtifactPresence.PRESENT -> {
                require(bytes in 0..declaration.maxBytes) { "Present artifact byte count is invalid." }
                requireNotNull(sha256) { "Present artifact requires a SHA-256 digest." }
                requireSha256(sha256, "artifact sha256")
            }
            ArtifactPresence.ABSENT -> {
                require(!declaration.required) { "A required artifact cannot be absent." }
                require(bytes == 0L && sha256 == null) { "Absent artifact metadata must be empty." }
            }
        }
    }
}

data class ArtifactSetManifest(
    val schemaVersion: Int,
    val identity: PublicationIdentity,
    val createdAtEpochMillis: Long,
    val entries: List<ArtifactManifestEntry>,
    val setId: String,
) {
    init {
        require(schemaVersion == MANIFEST_SCHEMA_VERSION) { "Unsupported manifest schema." }
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must be non-negative." }
        require(entries.isNotEmpty() && entries.size <= MAX_ARTIFACT_COUNT) { "Artifact count is invalid." }
        require(entries.any { it.declaration.required }) { "At least one required artifact is necessary." }
        require(entries.map { it.declaration.logicalName }.toSet().size == entries.size) { "Duplicate logical artifact name." }
        require(entries.map { it.declaration.relativePath }.toSet().size == entries.size) { "Duplicate artifact path." }
        require(entries.sumOf { it.bytes } <= MAX_SET_BYTES) { "Artifact set exceeds the maximum total size." }
        requireSha256(setId, "setId")
    }
}

data class PublicationPlan(
    val identity: PublicationIdentity,
    val declarations: List<ArtifactDeclaration>,
) {
    init {
        require(declarations.isNotEmpty() && declarations.size <= MAX_ARTIFACT_COUNT) { "Artifact declaration count is invalid." }
        require(declarations.any { it.required }) { "At least one required artifact is necessary." }
        require(declarations.map { it.logicalName }.toSet().size == declarations.size) { "Duplicate logical artifact name." }
        require(declarations.map { it.relativePath }.toSet().size == declarations.size) { "Duplicate artifact path." }
        require(declarations.sumOf { it.maxBytes } <= MAX_SET_BYTES) { "Declared artifact set is unbounded." }
    }
}

enum class DurableOperationState {
    RUNNING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    FAILED,
}

data class PublicationPointer(
    val purpose: PublicationPurpose,
    val projectId: String,
    val projectRevision: Long,
    val operationId: String,
    val attempt: Long,
    val setId: String,
    val manifestSha256: String,
    val relativeDirectory: String,
    val publishedAtEpochMillis: Long,
    val durability: PublicationDurability,
) {
    init {
        requireSafeToken(projectId, "projectId")
        require(projectRevision >= 0) { "projectRevision must be non-negative." }
        requireSafeToken(operationId, "operationId")
        require(attempt > 0) { "attempt must be positive." }
        requireSha256(setId, "setId")
        requireSha256(manifestSha256, "manifestSha256")
        requireSafeRelativePath(relativeDirectory)
        require(publishedAtEpochMillis >= 0) { "publishedAtEpochMillis must be non-negative." }
    }
}

data class PublicationDurability(
    val artifactContentsSynced: Boolean,
    val manifestSynced: Boolean,
    val atomicDirectoryPromotion: Boolean,
    val stagingDirectorySynced: Boolean,
    val destinationDirectorySynced: Boolean,
)

data class ProjectPublicationSnapshot(
    val projectId: String,
    val projectRevision: Long,
    val deleted: Boolean,
    val activeOperationId: String?,
    val activeAttempt: Long?,
    val activeOperationState: DurableOperationState?,
    val currentResult: PublicationPointer?,
    val previousCompletedResult: PublicationPointer?,
    val metadataVersion: Long,
) {
    init {
        requireSafeToken(projectId, "projectId")
        require(projectRevision >= 0) { "projectRevision must be non-negative." }
        require(metadataVersion >= 0) { "metadataVersion must be non-negative." }
        if (activeOperationId != null) requireSafeToken(activeOperationId, "activeOperationId")
        if (activeAttempt != null) require(activeAttempt > 0) { "activeAttempt must be positive." }
    }
}

data class PublicationCommitRequest(
    val identity: PublicationIdentity,
    val pointer: PublicationPointer,
    val expectedMetadataVersion: Long,
    val expectedCurrentResultSetId: String?,
)

sealed class PublicationCommitResult {
    data class Committed(
        val committedPointer: PublicationPointer,
        val currentResult: PublicationPointer?,
        val previousCompletedResult: PublicationPointer?,
        val metadataVersion: Long,
    ) : PublicationCommitResult()

    data class AlreadyCommitted(
        val pointer: PublicationPointer,
        val metadataVersion: Long,
    ) : PublicationCommitResult()

    data class Rejected(val reason: PublicationRejection) : PublicationCommitResult()
}

enum class PublicationRejection {
    PROJECT_MISSING,
    PROJECT_DELETED,
    REVISION_CHANGED,
    OPERATION_CHANGED,
    ATTEMPT_CHANGED,
    OPERATION_NOT_RUNNING,
    CURRENT_RESULT_CHANGED,
    METADATA_VERSION_CHANGED,
    PURPOSE_UNSUPPORTED,
}

/**
 * Agent 005 owns the durable implementation. commit() must be one database
 * transaction that rechecks every expected field and shifts current -> previous
 * only when the new verified set becomes current.
 */
interface PublicationMetadataPort {
    fun snapshot(projectId: String): ProjectPublicationSnapshot?
    fun commit(request: PublicationCommitRequest): PublicationCommitResult

    /** Must include result sets and committed original-import batches. */
    fun committedPointer(projectId: String, setId: String): PublicationPointer? {
        val snapshot = snapshot(projectId) ?: return null
        return sequenceOf(snapshot.currentResult, snapshot.previousCompletedResult)
            .filterNotNull()
            .firstOrNull { it.setId == setId }
    }
}

enum class PublicationJournalState {
    OPEN,
    SEALED,
    PROMOTING,
    FILES_PROMOTED,
    METADATA_COMMITTED,
    COMPLETED,
    ABORTED,
    STALE;

    val terminal: Boolean
        get() = this == COMPLETED || this == ABORTED || this == STALE
}

data class PublicationJournal(
    val schemaVersion: Int,
    val identity: PublicationIdentity,
    val state: PublicationJournalState,
    val expectedMetadataVersion: Long,
    val expectedCurrentResultSetId: String?,
    val setId: String?,
    val stageRelativeDirectory: String,
    val finalRelativeDirectory: String?,
    val durability: PublicationDurability,
    val sequence: Long,
    val updatedAtEpochMillis: Long,
    val detail: String?,
) {
    init {
        require(schemaVersion == JOURNAL_SCHEMA_VERSION) { "Unsupported journal schema." }
        require(expectedMetadataVersion >= 0) { "expectedMetadataVersion must be non-negative." }
        expectedCurrentResultSetId?.let { requireSha256(it, "expectedCurrentResultSetId") }
        setId?.let { requireSha256(it, "setId") }
        requireSafeRelativePath(stageRelativeDirectory)
        finalRelativeDirectory?.let(::requireSafeRelativePath)
        require(sequence >= 0) { "sequence must be non-negative." }
        require(updatedAtEpochMillis >= 0) { "updatedAtEpochMillis must be non-negative." }
        require(detail == null || (detail.length <= 512 && detail.none { it == '\u0000' })) { "Journal detail is invalid." }
    }
}

data class VerifiedArtifactSet(
    val pointer: PublicationPointer,
    val manifest: ArtifactSetManifest,
    val directory: File,
) {
    fun artifact(logicalName: String): File? {
        val entry = manifest.entries.singleOrNull { it.declaration.logicalName == logicalName } ?: return null
        if (entry.presence != ArtifactPresence.PRESENT) return null
        return File(directory, entry.declaration.relativePath)
    }
}

sealed class PublicationReadResult {
    data class Verified(val value: VerifiedArtifactSet) : PublicationReadResult()
    data object None : PublicationReadResult()
    data class Invalid(val reason: String) : PublicationReadResult()
}

data class PublicationRecoveryReport(
    val completed: Int,
    val aborted: Int,
    val stale: Int,
    val unchanged: Int,
    val corruptJournals: Int,
    val details: List<String>,
)

enum class PublicationBoundary {
    JOURNAL_OPENED,
    ARTIFACT_SYNCED,
    MANIFEST_SEALED,
    PROMOTION_INTENT_SYNCED,
    DIRECTORY_PROMOTED,
    METADATA_COMMITTED,
}

fun interface PublicationFaultInjector {
    fun after(boundary: PublicationBoundary)

    companion object {
        val NONE = PublicationFaultInjector { }
    }
}

open class PublicationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class PublicationRejectedException(val rejection: PublicationRejection) : PublicationException("Publication rejected: $rejection")

/** Tests use Error so coordinator exception handling models an uncatchable process death. */
class SimulatedProcessDeath(val boundary: PublicationBoundary) : Error("Simulated process death after $boundary")

internal const val MANIFEST_SCHEMA_VERSION = 1
internal const val JOURNAL_SCHEMA_VERSION = 1
internal const val MAX_ARTIFACT_COUNT = 512
internal const val MAX_ARTIFACT_BYTES = 40L * 1024 * 1024 * 1024
internal const val MAX_SET_BYTES = 64L * 1024 * 1024 * 1024
internal val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val SAFE_TOKEN_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val SAFE_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")

internal fun requireSha256(value: String, label: String) {
    require(SHA256_REGEX.matches(value)) { "$label must be a lowercase SHA-256 digest." }
}

internal fun requireSafeToken(value: String, label: String) {
    require(SAFE_TOKEN_REGEX.matches(value) && value != "." && value != "..") { "$label is not a safe token." }
}

internal fun requireSafeName(value: String, label: String) {
    require(SAFE_NAME_REGEX.matches(value) && value != "." && value != "..") { "$label is invalid." }
}

internal fun requireSafeRelativePath(value: String) {
    require(value.length in 1..512 && !value.startsWith('/') && !value.startsWith('\\')) { "Artifact path must be relative." }
    require('\\' !in value && '\u0000' !in value) { "Artifact path contains a forbidden character." }
    val pieces = value.split('/')
    require(pieces.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 128 }) {
        "Artifact path contains an unsafe segment."
    }
}
