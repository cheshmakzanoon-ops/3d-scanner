package com.realityforge.scanner.data.jobs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Synchronous queries run only inside owner023's Room publication transaction. */
@Dao
interface ExecutionPublicationDao {
    @Query("SELECT * FROM execution_jobs WHERE projectId = :projectId AND state = 'RUNNING' AND leaseExpiresAtEpochMillis > :now ORDER BY createdAtEpochMillis, jobId LIMIT 1")
    fun active(projectId: String, now: Long): ReconstructionJobEntity?

    @Query("SELECT id FROM projects ORDER BY createdAt, id LIMIT 10000")
    fun projectIds(): List<String>

    @Query("UPDATE execution_jobs SET state = 'COMPLETED', acceptedStage = 'PUBLISHING', checkpointIdentity = :setId, checkpointSha256 = :manifestSha256, resultArtifactIdentity = :setId, resultArtifactSha256 = :manifestSha256, leaseToken = NULL, leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL, outcomeCode = NULL, progressStage = NULL, progressCompletedUnits = NULL, progressTotalUnits = NULL, progressUnit = NULL, progressUpdatedAtEpochMillis = NULL, updatedAtEpochMillis = :now, stateVersion = stateVersion + 1 WHERE jobId = :jobId AND attempt = :attempt AND stateVersion = :stateVersion AND state = 'RUNNING' AND leaseToken = :leaseToken AND leaseExpiresAtEpochMillis > :now")
    fun complete(jobId: String, attempt: Long, stateVersion: Long, leaseToken: String,
        setId: String, manifestSha256: String, now: Long): Int

    @Query("UPDATE projects SET state = 'SPARSE_READY', resultRevision = :revision, resultKey = :artifactKey, message = :message WHERE id = :projectId AND revision = :revision AND state NOT IN ('DELETING', 'DELETING_PHOTO', 'RECOVERY_REQUIRED')")
    fun publishProject(projectId: String, revision: Long, artifactKey: String, message: String): Int

    @Insert
    fun event(event: ReconstructionJobEventEntity): Long

    @Query("DELETE FROM execution_job_events WHERE jobId = :jobId AND eventId NOT IN (SELECT eventId FROM execution_job_events WHERE jobId = :jobId ORDER BY eventId DESC LIMIT 200)")
    fun trimEvents(jobId: String): Int
}
