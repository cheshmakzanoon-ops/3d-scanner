package com.realityforge.scanner.data.jobs

import kotlinx.coroutines.flow.Flow

fun interface JobIdFactory { fun nextJobId(): JobId }
fun interface JobTokenFactory { fun nextToken(): String }

sealed interface CheckpointRecoveryDecision {
    val code: String

    data class ResumeFrom(
        val cursor: JobStageCursor,
        override val code: String = "CHECKPOINT_VALIDATED",
    ) : CheckpointRecoveryDecision {
        init { requireSafeCode("recovery code", code) }
    }

    data class RestartFromBeginning(
        override val code: String = "NO_VALID_CHECKPOINT",
    ) : CheckpointRecoveryDecision {
        init { requireSafeCode("recovery code", code) }
    }
}

fun interface JobCheckpointValidator {
    suspend fun validate(snapshot: JobSnapshot): CheckpointRecoveryDecision
}

data class RecoverySummary(
    val considered: Int,
    val recovered: Int,
    val conflicts: Int,
    val validationFailures: Int,
) {
    init {
        require(considered >= 0) { "Considered recovery jobs cannot be negative." }
        require(recovered >= 0) { "Recovered job count cannot be negative." }
        require(conflicts >= 0) { "Recovery conflict count cannot be negative." }
        require(validationFailures >= 0) { "Recovery validation failures cannot be negative." }
        require(recovered + conflicts + validationFailures == considered) {
            "Recovery outcomes must account for every considered job exactly once."
        }
    }
}

enum class JobCommandDisposition { APPLIED, ALREADY_SATISFIED, TERMINAL_UNCHANGED }

data class JobCommandResult(
    val snapshot: JobSnapshot,
    val disposition: JobCommandDisposition,
)

open class JobStoreException(message: String) : IllegalStateException(message)
class JobNotFoundException(jobId: JobId) : JobStoreException("Unknown reconstruction job $jobId.")
class JobLeaseRejectedException(message: String) : JobStoreException(message)
class JobTransitionRejectedException(message: String) : JobStoreException(message)
class JobWriteConflictException(jobId: JobId) : JobStoreException("Concurrent update rejected for $jobId.")

interface JobStore {
    suspend fun enqueue(input: JobInputSnapshot, nowEpochMillis: Long): JobSnapshot
    suspend fun snapshot(jobId: JobId): JobSnapshot?
    fun observe(jobId: JobId): Flow<JobSnapshot?>
    fun observeQueue(limit: Int = DEFAULT_QUEUE_LIMIT): Flow<List<JobSnapshot>>
    fun observeHistory(jobId: JobId, limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<JobEvent>>

    suspend fun acquireNext(
        workerId: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): LeasedJob?

    suspend fun validateLease(lease: JobLease, nowEpochMillis: Long): JobSnapshot
    suspend fun renew(
        lease: JobLease,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): LeasedJob

    suspend fun relinquish(
        lease: JobLease,
        reasonCode: String,
        nowEpochMillis: Long,
    ): JobSnapshot

    suspend fun acceptStage(
        lease: JobLease,
        cursor: JobStageCursor,
        nowEpochMillis: Long,
    ): LeasedJob

    /** Explicit restart after independent revalidation; never advances or silently replaces a checkpoint. */
    suspend fun rewindCheckpoint(lease: JobLease, cursor: JobStageCursor, reasonCode: String,
        nowEpochMillis: Long): LeasedJob

    suspend fun recordProgress(
        lease: JobLease,
        progress: JobProgress,
        nowEpochMillis: Long,
    ): LeasedJob

    suspend fun complete(
        lease: JobLease,
        resultArtifactIdentity: String,
        resultArtifactSha256: String,
        nowEpochMillis: Long,
    ): JobSnapshot

    suspend fun fail(lease: JobLease, failureCode: String, nowEpochMillis: Long): JobSnapshot
    suspend fun blockExternal(lease: JobLease, blockerCode: String, nowEpochMillis: Long): JobSnapshot

    suspend fun pause(jobId: JobId, nowEpochMillis: Long, reasonCode: String? = null): JobCommandResult
    suspend fun resume(jobId: JobId, nowEpochMillis: Long): JobCommandResult
    suspend fun retry(jobId: JobId, nowEpochMillis: Long): JobCommandResult
    suspend fun cancel(jobId: JobId, nowEpochMillis: Long): JobCommandResult

    suspend fun recoverExpired(
        nowEpochMillis: Long,
        validator: JobCheckpointValidator,
        limit: Int = DEFAULT_RECOVERY_LIMIT,
    ): RecoverySummary

    companion object {
        const val DEFAULT_QUEUE_LIMIT = 50
        const val DEFAULT_HISTORY_LIMIT = 64
        const val DEFAULT_RECOVERY_LIMIT = 100
    }
}
