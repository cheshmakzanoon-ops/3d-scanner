package com.realityforge.scanner.data.jobs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val SHA256_HEX_LENGTH = 64
private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val SAFE_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")
private val SHA256 = Regex("[0-9a-f]{64}")

@JvmInline
value class JobId(val value: String) {
    init { requireSafeId("jobId", value) }
    override fun toString(): String = value
}

/** A monotonically increasing attempt plus an unguessable token fence every worker mutation. */
data class JobLease(
    val jobId: JobId,
    val attempt: Long,
    val token: String,
    val workerId: String,
    val expiresAtEpochMillis: Long,
    val stateVersion: Long,
) {
    init {
        require(attempt > 0) { "A lease attempt must be positive." }
        requireSafeId("lease token", token)
        requireSafeId("workerId", workerId)
        require(expiresAtEpochMillis > 0) { "Lease expiry must be positive." }
        require(stateVersion >= 0) { "State version cannot be negative." }
    }
}

enum class ReconstructionJobState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    STALE,
    BLOCKED_EXTERNAL;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, FAILED, CANCELLED, STALE, BLOCKED_EXTERNAL)
}

/** Stable coarse cursor. Fine-grained algorithm progress belongs to C2, not the durable schema. */
enum class ReconstructionStage(val order: Int) {
    CREATED(0),
    PREPARED(1),
    FEATURES(2),
    MATCHES(3),
    SPARSE(4),
    DENSE(5),
    SURFACE(6),
    TEXTURE(7),
    PUBLISHING(8),
}

enum class JobProgressUnit {
    PHOTOS,
    FEATURES,
    MATCHES,
    CAMERAS,
    TRACKS,
    TILES,
    STAGES,
    BYTES,
    ITEMS,
}

data class JobInputSnapshot(
    val projectId: String,
    val projectRevision: Long,
    val requestId: String,
    val inputSha256: String,
    val calibrationSha256: String,
    val settingsSha256: String,
    val engineSha256: String,
    val engineVersion: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported job input schema $schemaVersion."
        }
        requireSafeId("projectId", projectId)
        require(projectRevision >= 0) { "Project revision cannot be negative." }
        requireSafeId("requestId", requestId)
        requireSha256("inputSha256", inputSha256)
        requireSha256("calibrationSha256", calibrationSha256)
        requireSha256("settingsSha256", settingsSha256)
        requireSha256("engineSha256", engineSha256)
        requireSafeId("engineVersion", engineVersion)
    }

    /** Length-prefixed canonical hashing prevents concatenation ambiguity and locale drift. */
    fun identityKey(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.addCanonical("realityforge-durable-job-input-v1")
        digest.addCanonical(schemaVersion.toString())
        digest.addCanonical(projectId)
        digest.addCanonical(projectRevision.toString())
        digest.addCanonical(requestId)
        digest.addCanonical(inputSha256)
        digest.addCanonical(calibrationSha256)
        digest.addCanonical(settingsSha256)
        digest.addCanonical(engineSha256)
        digest.addCanonical(engineVersion)
        return digest.digest().toHex()
    }

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

data class JobStageCursor(
    val acceptedStage: ReconstructionStage = ReconstructionStage.CREATED,
    val checkpointIdentity: String? = null,
    val checkpointSha256: String? = null,
) {
    init {
        checkpointIdentity?.let { requireSafeId("checkpointIdentity", it) }
        checkpointSha256?.let { requireSha256("checkpointSha256", it) }
        require((checkpointIdentity == null) == (checkpointSha256 == null)) {
            "Checkpoint identity and checksum must be supplied together."
        }
        require((acceptedStage == ReconstructionStage.CREATED) == (checkpointIdentity == null)) {
            "Created is the only stage without an accepted checkpoint artifact."
        }
    }

    companion object { fun initial() = JobStageCursor() }
}

data class JobProgress(
    val stage: ReconstructionStage,
    val completedUnits: Long,
    val totalUnits: Long?,
    val unit: JobProgressUnit,
) {
    init {
        require(completedUnits >= 0) { "Progress cannot be negative." }
        totalUnits?.let {
            require(it > 0) { "Progress total must be positive when known." }
            require(completedUnits <= it) { "Progress cannot exceed its declared total." }
        }
    }
}

data class JobSnapshot(
    val jobId: JobId,
    val identityKey: String,
    val input: JobInputSnapshot,
    val state: ReconstructionJobState,
    val cursor: JobStageCursor,
    val attempt: Long,
    val lease: JobLease?,
    val resultArtifactIdentity: String?,
    val resultArtifactSha256: String?,
    val outcomeCode: String?,
    val progress: JobProgress?,
    val progressUpdatedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val stateVersion: Long,
) {
    init {
        requireSha256("identityKey", identityKey)
        require(attempt >= 0) { "Attempt cannot be negative." }
        require(stateVersion >= 0) { "State version cannot be negative." }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Job timestamps are invalid."
        }
        resultArtifactIdentity?.let { requireSafeId("resultArtifactIdentity", it) }
        resultArtifactSha256?.let { requireSha256("resultArtifactSha256", it) }
        require((resultArtifactIdentity == null) == (resultArtifactSha256 == null)) {
            "Result identity and checksum must be supplied together."
        }
        outcomeCode?.let { requireSafeCode("outcomeCode", it) }
        require((progress == null) == (progressUpdatedAtEpochMillis == null)) {
            "Progress and its timestamp must be supplied together."
        }
        progressUpdatedAtEpochMillis?.let {
            require(it in createdAtEpochMillis..updatedAtEpochMillis) {
                "Progress timestamp must be within the durable job timeline."
            }
        }
        progress?.let {
            val delta = it.stage.order - cursor.acceptedStage.order
            require(delta in 0..1) {
                "Progress must describe the accepted stage or the immediately executing stage."
            }
        }
        require((state == ReconstructionJobState.RUNNING) == (lease != null)) {
            "Only a running job may hold a lease, and every running job must hold one."
        }
        require(lease == null || lease.jobId == jobId) { "Lease belongs to another job." }
        require(lease == null || lease.attempt == attempt) { "Lease attempt is inconsistent." }
        require(lease == null || lease.stateVersion == stateVersion) {
            "Lease state version is inconsistent."
        }
        require(state != ReconstructionJobState.COMPLETED || resultArtifactIdentity != null) {
            "Completed work requires a verified result artifact."
        }
        require(state != ReconstructionJobState.COMPLETED || outcomeCode == null) {
            "Completed work cannot carry a failure outcome."
        }
    }
}

data class LeasedJob(val snapshot: JobSnapshot, val lease: JobLease) {
    init {
        require(snapshot.state == ReconstructionJobState.RUNNING)
        require(snapshot.lease == lease)
    }
}

enum class JobEventKind {
    CREATED,
    ACQUIRED,
    STAGE_ACCEPTED,
    PROGRESS,
    PAUSED,
    RELINQUISHED,
    RESUMED,
    RETRIED,
    CANCELLED,
    RECOVERED,
    COMPLETED,
    FAILED,
    BLOCKED_EXTERNAL,
    STALE,
}

data class JobEvent(
    val eventId: Long,
    val jobId: JobId,
    val attempt: Long,
    val kind: JobEventKind,
    val state: ReconstructionJobState,
    val stage: ReconstructionStage,
    val stateVersion: Long,
    val code: String?,
    val progress: JobProgress?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(eventId > 0)
        require(attempt >= 0)
        require(stateVersion >= 0)
        code?.let { requireSafeCode("event code", it) }
        require(createdAtEpochMillis >= 0)
    }
}

internal fun requireSafeId(name: String, value: String) {
    require(SAFE_ID.matches(value)) { "$name must be a bounded stable identifier." }
}

internal fun requireSafeCode(name: String, value: String) {
    require(SAFE_CODE.matches(value)) { "$name must be an uppercase stable code." }
}

internal fun requireSha256(name: String, value: String) {
    require(value.length == SHA256_HEX_LENGTH && SHA256.matches(value)) {
        "$name must be lowercase SHA-256 hex."
    }
}

private fun MessageDigest.addCanonical(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
