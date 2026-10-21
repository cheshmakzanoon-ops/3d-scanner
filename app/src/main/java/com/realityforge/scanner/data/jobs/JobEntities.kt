package com.realityforge.scanner.data.jobs

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "execution_jobs",
    indices = [
        Index(value = ["identityKey"], unique = true),
        Index(value = ["projectId", "projectRevision"]),
        Index(value = ["state", "createdAtEpochMillis"]),
        Index(value = ["leaseExpiresAtEpochMillis"]),
    ],
)
data class ReconstructionJobEntity(
    @PrimaryKey val jobId: String,
    val schemaVersion: Int,
    val identityKey: String,
    val projectId: String,
    val projectRevision: Long,
    val requestId: String,
    val inputSha256: String,
    val calibrationSha256: String,
    val settingsSha256: String,
    val engineSha256: String,
    val engineVersion: String,
    val state: String,
    val acceptedStage: String,
    val checkpointIdentity: String?,
    val checkpointSha256: String?,
    val attempt: Long,
    val leaseToken: String?,
    val leaseOwner: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val resultArtifactIdentity: String?,
    val resultArtifactSha256: String?,
    val outcomeCode: String?,
    val progressStage: String?,
    val progressCompletedUnits: Long?,
    val progressTotalUnits: Long?,
    val progressUnit: String?,
    val progressUpdatedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val stateVersion: Long,
    val settingsJson: String = "{}",
)

@Entity(
    tableName = "execution_job_events",
    foreignKeys = [
        ForeignKey(
            entity = ReconstructionJobEntity::class,
            parentColumns = ["jobId"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["jobId", "eventId"])],
)
data class ReconstructionJobEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val jobId: String,
    val attempt: Long,
    val kind: String,
    val state: String,
    val stage: String,
    val stateVersion: Long,
    val code: String?,
    val progressStage: String?,
    val progressCompletedUnits: Long?,
    val progressTotalUnits: Long?,
    val progressUnit: String?,
    val createdAtEpochMillis: Long,
)

internal fun ReconstructionJobEntity.toSnapshot(): JobSnapshot {
    require(schemaVersion == JobInputSnapshot.CURRENT_SCHEMA_VERSION) {
        "Unsupported persisted job schema $schemaVersion."
    }
    val input = JobInputSnapshot(
        projectId = projectId,
        projectRevision = projectRevision,
        requestId = requestId,
        inputSha256 = inputSha256,
        calibrationSha256 = calibrationSha256,
        settingsSha256 = settingsSha256,
        engineSha256 = engineSha256,
        engineVersion = engineVersion,
        schemaVersion = schemaVersion,
        settingsJson = settingsJson,
    )
    require(input.identityKey() == identityKey) { "Persisted job input identity is corrupt." }
    val parsedState = enumValue<ReconstructionJobState>("job state", state)
    val cursor = JobStageCursor(
        acceptedStage = enumValue("accepted stage", acceptedStage),
        checkpointIdentity = checkpointIdentity,
        checkpointSha256 = checkpointSha256,
    )
    val progress = progressStage?.let {
        JobProgress(
            stage = enumValue("progress stage", it),
            completedUnits = requireNotNull(progressCompletedUnits) { "Progress is incomplete." },
            totalUnits = progressTotalUnits,
            unit = enumValue("progress unit", requireNotNull(progressUnit) { "Progress unit is missing." }),
        )
    }
    require((progress == null) == (progressUpdatedAtEpochMillis == null)) {
        "Progress timestamp is inconsistent."
    }
    val id = JobId(jobId)
    val lease = if (leaseToken == null && leaseOwner == null && leaseExpiresAtEpochMillis == null) null else {
        JobLease(
            jobId = id,
            attempt = attempt,
            token = requireNotNull(leaseToken) { "Lease token is missing." },
            workerId = requireNotNull(leaseOwner) { "Lease owner is missing." },
            expiresAtEpochMillis = requireNotNull(leaseExpiresAtEpochMillis) { "Lease expiry is missing." },
            stateVersion = stateVersion,
        )
    }
    return JobSnapshot(
        jobId = id,
        identityKey = identityKey,
        input = input,
        state = parsedState,
        cursor = cursor,
        attempt = attempt,
        lease = lease,
        resultArtifactIdentity = resultArtifactIdentity,
        resultArtifactSha256 = resultArtifactSha256,
        outcomeCode = outcomeCode,
        progress = progress,
        progressUpdatedAtEpochMillis = progressUpdatedAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        stateVersion = stateVersion,
    )
}

internal fun ReconstructionJobEventEntity.toEvent(): JobEvent = JobEvent(
    eventId = eventId,
    jobId = JobId(jobId),
    attempt = attempt,
    kind = enumValue("job event kind", kind),
    state = enumValue("job event state", state),
    stage = enumValue("job event stage", stage),
    stateVersion = stateVersion,
    code = code,
    progress = progressStage?.let {
        JobProgress(
            stage = enumValue("job event progress stage", it),
            completedUnits = requireNotNull(progressCompletedUnits),
            totalUnits = progressTotalUnits,
            unit = enumValue("job event progress unit", requireNotNull(progressUnit)),
        )
    },
    createdAtEpochMillis = createdAtEpochMillis,
)

private inline fun <reified T : Enum<T>> enumValue(name: String, value: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Unknown persisted $name '$value'.")
