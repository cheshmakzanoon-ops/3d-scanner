package com.realityforge.scanner.data.jobs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReconstructionJobDao {
    @Query("SELECT * FROM execution_jobs WHERE projectId = :projectId " +
        "ORDER BY CASE WHEN projectRevision = :revision THEN 0 ELSE 1 END, createdAtEpochMillis DESC, jobId DESC LIMIT 1")
    fun observeLatestForProject(projectId: String, revision: Long): Flow<ReconstructionJobEntity?>

    @Query("SELECT * FROM execution_jobs ORDER BY updatedAtEpochMillis DESC, jobId LIMIT :limit")
    suspend fun recentNotificationJobs(limit: Int): List<ReconstructionJobEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: ReconstructionJobEntity): Long

    @Insert
    suspend fun insertEvent(event: ReconstructionJobEventEntity): Long

    @Query("SELECT * FROM execution_jobs WHERE jobId = :jobId")
    suspend fun job(jobId: String): ReconstructionJobEntity?

    @Query("SELECT * FROM execution_jobs WHERE identityKey = :identityKey")
    suspend fun jobByIdentity(identityKey: String): ReconstructionJobEntity?

    @Query("SELECT * FROM execution_jobs WHERE jobId = :jobId")
    fun observeJob(jobId: String): Flow<ReconstructionJobEntity?>

    @Query(
        """
        SELECT * FROM execution_jobs
        WHERE state IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY createdAtEpochMillis, jobId
        LIMIT :limit
        """,
    )
    fun observeQueue(limit: Int): Flow<List<ReconstructionJobEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM execution_job_events
            WHERE jobId = :jobId
            ORDER BY eventId DESC
            LIMIT :limit
        ) ORDER BY eventId
        """,
    )
    fun observeHistory(jobId: String, limit: Int): Flow<List<ReconstructionJobEventEntity>>

    @Query(
        """
        SELECT * FROM execution_jobs
        WHERE state = 'QUEUED'
        ORDER BY createdAtEpochMillis, jobId
        LIMIT 1
        """,
    )
    suspend fun oldestQueued(): ReconstructionJobEntity?

    @Query(
        """
        SELECT * FROM execution_jobs
        WHERE projectId = :projectId
          AND state IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY createdAtEpochMillis, jobId
        """,
    )
    suspend fun activeForProject(projectId: String): List<ReconstructionJobEntity>

    @Query(
        """
        SELECT * FROM execution_jobs
        WHERE state = 'RUNNING'
          AND (leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis <= :nowEpochMillis)
        ORDER BY updatedAtEpochMillis, jobId
        LIMIT :limit
        """,
    )
    suspend fun expiredRunning(nowEpochMillis: Long, limit: Int): List<ReconstructionJobEntity>

    @Query(
        """
        UPDATE execution_jobs SET
            state = :state,
            acceptedStage = :acceptedStage,
            checkpointIdentity = :checkpointIdentity,
            checkpointSha256 = :checkpointSha256,
            attempt = :attempt,
            leaseToken = :leaseToken,
            leaseOwner = :leaseOwner,
            leaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis,
            resultArtifactIdentity = :resultArtifactIdentity,
            resultArtifactSha256 = :resultArtifactSha256,
            outcomeCode = :outcomeCode,
            progressStage = :progressStage,
            progressCompletedUnits = :progressCompletedUnits,
            progressTotalUnits = :progressTotalUnits,
            progressUnit = :progressUnit,
            progressUpdatedAtEpochMillis = :progressUpdatedAtEpochMillis,
            updatedAtEpochMillis = :updatedAtEpochMillis,
            stateVersion = :nextStateVersion
        WHERE jobId = :jobId AND stateVersion = :expectedStateVersion
        """,
    )
    suspend fun compareAndSet(
        jobId: String,
        expectedStateVersion: Long,
        state: String,
        acceptedStage: String,
        checkpointIdentity: String?,
        checkpointSha256: String?,
        attempt: Long,
        leaseToken: String?,
        leaseOwner: String?,
        leaseExpiresAtEpochMillis: Long?,
        resultArtifactIdentity: String?,
        resultArtifactSha256: String?,
        outcomeCode: String?,
        progressStage: String?,
        progressCompletedUnits: Long?,
        progressTotalUnits: Long?,
        progressUnit: String?,
        progressUpdatedAtEpochMillis: Long?,
        updatedAtEpochMillis: Long,
        nextStateVersion: Long,
    ): Int

    @Query(
        """
        DELETE FROM execution_job_events
        WHERE jobId = :jobId AND eventId NOT IN (
            SELECT eventId FROM execution_job_events
            WHERE jobId = :jobId
            ORDER BY eventId DESC
            LIMIT :keep
        )
        """,
    )
    suspend fun trimHistory(jobId: String, keep: Int): Int
}
