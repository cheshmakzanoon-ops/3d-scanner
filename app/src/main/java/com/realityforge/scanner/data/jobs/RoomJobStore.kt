package com.realityforge.scanner.data.jobs

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomJobStore(
    private val database: RoomDatabase,
    private val dao: ReconstructionJobDao,
    private val jobIdFactory: JobIdFactory = JobIdFactory { JobId(UUID.randomUUID().toString()) },
    private val tokenFactory: JobTokenFactory = JobTokenFactory { UUID.randomUUID().toString() },
    private val historyLimit: Int = JobStore.DEFAULT_HISTORY_LIMIT,
) : JobStore {
    init { require(historyLimit in 1..MAX_HISTORY_LIMIT) }

    override suspend fun enqueue(input: JobInputSnapshot, nowEpochMillis: Long): JobSnapshot {
        require(nowEpochMillis >= 0)
        val identityKey = input.identityKey()
        return database.withTransaction {
            dao.jobByIdentity(identityKey)?.toSnapshot()?.let { return@withTransaction it }

            for (active in dao.activeForProject(input.projectId)) {
                if (active.identityKey == identityKey) return@withTransaction active.toSnapshot()
                JobStateMachine.requireCommandTime(active.toSnapshot(), nowEpochMillis)
                val stale = active.copy(
                    state = ReconstructionJobState.STALE.name,
                    leaseToken = null,
                    leaseOwner = null,
                    leaseExpiresAtEpochMillis = null,
                    outcomeCode = "INPUT_IDENTITY_CHANGED",
                    updatedAtEpochMillis = nowEpochMillis,
                    stateVersion = active.stateVersion + 1,
                )
                write(active, stale)
                appendEvent(stale, JobEventKind.STALE, "INPUT_IDENTITY_CHANGED", nowEpochMillis)
            }

            repeat(MAX_JOB_ID_ATTEMPTS) {
                val id = jobIdFactory.nextJobId()
                val entity = ReconstructionJobEntity(
                    jobId = id.value,
                    schemaVersion = input.schemaVersion,
                    identityKey = identityKey,
                    projectId = input.projectId,
                    projectRevision = input.projectRevision,
                    requestId = input.requestId,
                    inputSha256 = input.inputSha256,
                    calibrationSha256 = input.calibrationSha256,
                    settingsSha256 = input.settingsSha256,
                    engineSha256 = input.engineSha256,
                    engineVersion = input.engineVersion,
                    state = ReconstructionJobState.QUEUED.name,
                    acceptedStage = ReconstructionStage.CREATED.name,
                    checkpointIdentity = null,
                    checkpointSha256 = null,
                    attempt = 0,
                    leaseToken = null,
                    leaseOwner = null,
                    leaseExpiresAtEpochMillis = null,
                    resultArtifactIdentity = null,
                    resultArtifactSha256 = null,
                    outcomeCode = null,
                    progressStage = null,
                    progressCompletedUnits = null,
                    progressTotalUnits = null,
                    progressUnit = null,
                    progressUpdatedAtEpochMillis = null,
                    createdAtEpochMillis = nowEpochMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                    stateVersion = 0,
                    settingsJson = input.settingsJson,
                )
                if (dao.insertJob(entity) != -1L) {
                    appendEvent(entity, JobEventKind.CREATED, null, nowEpochMillis)
                    return@withTransaction entity.toSnapshot()
                }
                dao.jobByIdentity(identityKey)?.let { return@withTransaction it.toSnapshot() }
            }
            throw JobStoreException("Unable to allocate a unique durable job ID.")
        }
    }

    override suspend fun snapshot(jobId: JobId): JobSnapshot? = dao.job(jobId.value)?.toSnapshot()

    override fun observe(jobId: JobId): Flow<JobSnapshot?> =
        dao.observeJob(jobId.value).map { it?.toSnapshot() }

    override fun observeQueue(limit: Int): Flow<List<JobSnapshot>> {
        require(limit in 1..MAX_QUEUE_LIMIT)
        return dao.observeQueue(limit).map { rows -> rows.map { it.toSnapshot() } }
    }

    override fun observeHistory(jobId: JobId, limit: Int): Flow<List<JobEvent>> {
        require(limit in 1..MAX_HISTORY_LIMIT)
        return dao.observeHistory(jobId.value, limit).map { rows -> rows.map { it.toEvent() } }
    }

    override suspend fun acquireNext(
        workerId: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): LeasedJob? {
        requireSafeId("workerId", workerId)
        val expiry = JobStateMachine.checkedExpiry(nowEpochMillis, leaseDurationMillis)
        return database.withTransaction {
            val current = dao.oldestQueued() ?: return@withTransaction null
            JobStateMachine.requireCommandTime(current.toSnapshot(), nowEpochMillis)
            val token = tokenFactory.nextToken().also { requireSafeId("lease token", it) }
            val next = current.copy(
                state = ReconstructionJobState.RUNNING.name,
                attempt = current.attempt + 1,
                leaseToken = token,
                leaseOwner = workerId,
                leaseExpiresAtEpochMillis = expiry,
                outcomeCode = null,
                updatedAtEpochMillis = nowEpochMillis,
                stateVersion = current.stateVersion + 1,
            )
            write(current, next)
            appendEvent(next, JobEventKind.ACQUIRED, "LEASE_ACQUIRED", nowEpochMillis)
            next.toSnapshot().asLeased()
        }
    }

    override suspend fun validateLease(lease: JobLease, nowEpochMillis: Long): JobSnapshot =
        database.withTransaction {
            val current = requireJob(lease.jobId)
            val snapshot = current.toSnapshot()
            JobStateMachine.requireValidLease(snapshot, lease, nowEpochMillis)
            snapshot
        }

    override suspend fun renew(
        lease: JobLease,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): LeasedJob {
        val expiry = JobStateMachine.checkedExpiry(nowEpochMillis, leaseDurationMillis)
        return mutateLease(lease, nowEpochMillis) { current ->
            current.copy(
                leaseExpiresAtEpochMillis = expiry,
                updatedAtEpochMillis = nowEpochMillis,
                stateVersion = current.stateVersion + 1,
            )
        }
    }

    override suspend fun relinquish(
        lease: JobLease,
        reasonCode: String,
        nowEpochMillis: Long,
    ): JobSnapshot {
        requireSafeCode("reasonCode", reasonCode)
        return database.withTransaction {
            val current = requireJob(lease.jobId)
            JobStateMachine.requireValidLease(current.toSnapshot(), lease, nowEpochMillis)
            val next = current.copy(
                state = ReconstructionJobState.PAUSED.name,
                leaseToken = null,
                leaseOwner = null,
                leaseExpiresAtEpochMillis = null,
                outcomeCode = null,
                updatedAtEpochMillis = nowEpochMillis,
                stateVersion = current.stateVersion + 1,
            )
            write(current, next)
            appendEvent(next, JobEventKind.RELINQUISHED, reasonCode, nowEpochMillis)
            next.toSnapshot()
        }
    }

    override suspend fun acceptStage(
        lease: JobLease,
        cursor: JobStageCursor,
        nowEpochMillis: Long,
    ): LeasedJob = database.withTransaction {
        val current = requireJob(lease.jobId)
        val snapshot = current.toSnapshot()
        JobStateMachine.requireValidLease(snapshot, lease, nowEpochMillis)
        JobStateMachine.requireStageAdvance(snapshot.cursor, cursor)
        if (snapshot.cursor == cursor) return@withTransaction snapshot.asLeased()
        val next = current.copy(
            acceptedStage = cursor.acceptedStage.name,
            checkpointIdentity = cursor.checkpointIdentity,
            checkpointSha256 = cursor.checkpointSha256,
            progressStage = null,
            progressCompletedUnits = null,
            progressTotalUnits = null,
            progressUnit = null,
            progressUpdatedAtEpochMillis = null,
            updatedAtEpochMillis = nowEpochMillis,
            stateVersion = current.stateVersion + 1,
        )
        write(current, next)
        appendEvent(next, JobEventKind.STAGE_ACCEPTED, "STAGE_ACCEPTED", nowEpochMillis)
        next.toSnapshot().asLeased()
    }

    override suspend fun rewindCheckpoint(lease: JobLease, cursor: JobStageCursor, reasonCode: String,
        nowEpochMillis: Long): LeasedJob = database.withTransaction {
        requireSafeCode("reasonCode", reasonCode)
        val current = requireJob(lease.jobId)
        val snapshot = current.toSnapshot()
        JobStateMachine.requireValidLease(snapshot, lease, nowEpochMillis)
        JobStateMachine.requireRecoveryCursor(snapshot.cursor, cursor)
        if (snapshot.cursor == cursor) return@withTransaction snapshot.asLeased()
        val next = current.copy(acceptedStage = cursor.acceptedStage.name,
            checkpointIdentity = cursor.checkpointIdentity, checkpointSha256 = cursor.checkpointSha256,
            progressStage = null, progressCompletedUnits = null, progressTotalUnits = null,
            progressUnit = null, progressUpdatedAtEpochMillis = null,
            updatedAtEpochMillis = nowEpochMillis, stateVersion = current.stateVersion + 1)
        write(current, next)
        appendEvent(next, JobEventKind.RECOVERED, reasonCode, nowEpochMillis)
        next.toSnapshot().asLeased()
    }

    override suspend fun recordProgress(
        lease: JobLease,
        progress: JobProgress,
        nowEpochMillis: Long,
    ): LeasedJob = database.withTransaction {
        val current = requireJob(lease.jobId)
        val snapshot = current.toSnapshot()
        JobStateMachine.requireValidLease(snapshot, lease, nowEpochMillis)
        JobStateMachine.requireProgress(snapshot.cursor, progress)
        if (!JobStateMachine.shouldPersistProgress(
                snapshot.progress,
                snapshot.progressUpdatedAtEpochMillis,
                progress,
                nowEpochMillis,
            )
        ) return@withTransaction snapshot.asLeased()
        val next = current.copy(
            progressStage = progress.stage.name,
            progressCompletedUnits = progress.completedUnits,
            progressTotalUnits = progress.totalUnits,
            progressUnit = progress.unit.name,
            progressUpdatedAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            stateVersion = current.stateVersion + 1,
        )
        write(current, next)
        appendEvent(next, JobEventKind.PROGRESS, null, nowEpochMillis, progress)
        next.toSnapshot().asLeased()
    }

    override suspend fun complete(
        lease: JobLease,
        resultArtifactIdentity: String,
        resultArtifactSha256: String,
        nowEpochMillis: Long,
    ): JobSnapshot {
        requireSafeId("resultArtifactIdentity", resultArtifactIdentity)
        requireSha256("resultArtifactSha256", resultArtifactSha256)
        return finishLease(
            lease = lease,
            nowEpochMillis = nowEpochMillis,
            state = ReconstructionJobState.COMPLETED,
            code = null,
            resultArtifactIdentity = resultArtifactIdentity,
            resultArtifactSha256 = resultArtifactSha256,
            kind = JobEventKind.COMPLETED,
        )
    }

    override suspend fun fail(
        lease: JobLease,
        failureCode: String,
        nowEpochMillis: Long,
    ): JobSnapshot {
        requireSafeCode("failureCode", failureCode)
        return finishLease(
            lease,
            nowEpochMillis,
            ReconstructionJobState.FAILED,
            failureCode,
            null,
            null,
            JobEventKind.FAILED,
        )
    }

    override suspend fun blockExternal(
        lease: JobLease,
        blockerCode: String,
        nowEpochMillis: Long,
    ): JobSnapshot {
        requireSafeCode("blockerCode", blockerCode)
        return finishLease(
            lease,
            nowEpochMillis,
            ReconstructionJobState.BLOCKED_EXTERNAL,
            blockerCode,
            null,
            null,
            JobEventKind.BLOCKED_EXTERNAL,
        )
    }

    /** Fence delayed UI callbacks in the same Room transaction as the state mutation. */
    suspend fun commandIfCurrent(jobId: JobId, projectId: String, revision: Long,
        attempt: Long, stateVersion: Long, action: FencedJobCommand,
        nowEpochMillis: Long): JobCommandResult? = database.withTransaction {
        val current = dao.job(jobId.value)?.toSnapshot() ?: return@withTransaction null
        if (current.input.projectId != projectId || current.input.projectRevision != revision ||
            current.attempt != attempt || current.stateVersion != stateVersion) return@withTransaction null
        when (action) {
            FencedJobCommand.PAUSE -> pause(jobId, nowEpochMillis)
            FencedJobCommand.RESUME -> resume(jobId, nowEpochMillis)
            FencedJobCommand.RETRY -> retry(jobId, nowEpochMillis)
            FencedJobCommand.CANCEL -> cancel(jobId, nowEpochMillis)
        }.takeIf { it.disposition != JobCommandDisposition.TERMINAL_UNCHANGED }
    }

    override suspend fun pause(jobId: JobId, nowEpochMillis: Long, reasonCode: String?): JobCommandResult {
        reasonCode?.let { requireSafeCode("pauseReasonCode", it) }
        return command(jobId, nowEpochMillis) { current, state ->
            when {
                state == ReconstructionJobState.PAUSED -> CommandMutation.already()
                state in setOf(ReconstructionJobState.QUEUED, ReconstructionJobState.RUNNING) ->
                    CommandMutation.apply(
                        current.copy(
                            state = ReconstructionJobState.PAUSED.name,
                            leaseToken = null,
                            leaseOwner = null,
                            leaseExpiresAtEpochMillis = null,
                            outcomeCode = reasonCode,
                        ),
                        JobEventKind.PAUSED,
                        reasonCode ?: "PAUSED_BY_USER",
                    )
                else -> CommandMutation.terminal()
            }
        }
    }

    override suspend fun resume(jobId: JobId, nowEpochMillis: Long): JobCommandResult =
        command(jobId, nowEpochMillis) { current, state ->
            when (state) {
                ReconstructionJobState.QUEUED -> CommandMutation.already()
                ReconstructionJobState.PAUSED -> CommandMutation.apply(
                    current.copy(state = ReconstructionJobState.QUEUED.name, outcomeCode = null),
                    JobEventKind.RESUMED,
                    "RESUMED_BY_USER",
                )
                else -> CommandMutation.terminal()
            }
        }

    override suspend fun retry(jobId: JobId, nowEpochMillis: Long): JobCommandResult =
        command(jobId, nowEpochMillis) { current, state ->
            when (state) {
                ReconstructionJobState.QUEUED -> CommandMutation.already()
                ReconstructionJobState.FAILED,
                ReconstructionJobState.BLOCKED_EXTERNAL -> CommandMutation.apply(
                    current.copy(
                        state = ReconstructionJobState.QUEUED.name,
                        leaseToken = null,
                        leaseOwner = null,
                        leaseExpiresAtEpochMillis = null,
                        resultArtifactIdentity = null,
                        resultArtifactSha256 = null,
                        outcomeCode = null,
                    ),
                    JobEventKind.RETRIED,
                    "RETRY_REQUESTED",
                )
                else -> CommandMutation.terminal()
            }
        }

    override suspend fun cancel(jobId: JobId, nowEpochMillis: Long): JobCommandResult =
        command(jobId, nowEpochMillis) { current, state ->
            when {
                state == ReconstructionJobState.CANCELLED -> CommandMutation.already()
                !state.isTerminal -> CommandMutation.apply(
                    current.copy(
                        state = ReconstructionJobState.CANCELLED.name,
                        leaseToken = null,
                        leaseOwner = null,
                        leaseExpiresAtEpochMillis = null,
                        outcomeCode = "CANCELLED_BY_USER",
                    ),
                    JobEventKind.CANCELLED,
                    "CANCELLED_BY_USER",
                )
                else -> CommandMutation.terminal()
            }
        }

    override suspend fun recoverExpired(
        nowEpochMillis: Long,
        validator: JobCheckpointValidator,
        limit: Int,
    ): RecoverySummary {
        require(nowEpochMillis >= 0)
        require(limit in 1..MAX_RECOVERY_LIMIT)
        val candidates = database.withTransaction { dao.expiredRunning(nowEpochMillis, limit) }
        var recovered = 0
        var conflicts = 0
        var failures = 0
        for (candidate in candidates) {
            val snapshot = try {
                candidate.toSnapshot()
            } catch (_: RuntimeException) {
                failures++
                continue
            }
            val decision = try {
                validator.validate(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures++
                continue
            }
            val requestedCursor = when (decision) {
                is CheckpointRecoveryDecision.ResumeFrom -> decision.cursor
                is CheckpointRecoveryDecision.RestartFromBeginning -> JobStageCursor.initial()
            }
            val recoveryCode = try {
                JobStateMachine.requireRecoveryCursor(snapshot.cursor, requestedCursor)
                decision.code
            } catch (_: JobTransitionRejectedException) {
                failures++
                continue
            }
            try {
                database.withTransaction {
                    val current = requireJob(snapshot.jobId)
                    if (
                        current.stateVersion != candidate.stateVersion ||
                        current.state != ReconstructionJobState.RUNNING.name ||
                        (current.leaseExpiresAtEpochMillis ?: Long.MIN_VALUE) > nowEpochMillis
                    ) throw JobWriteConflictException(snapshot.jobId)
                    val currentSnapshot = current.toSnapshot()
                    JobStateMachine.requireCommandTime(currentSnapshot, nowEpochMillis)
                    JobStateMachine.requireRecoveryCursor(currentSnapshot.cursor, requestedCursor)
                    val next = current.copy(
                        state = ReconstructionJobState.PAUSED.name,
                        acceptedStage = requestedCursor.acceptedStage.name,
                        checkpointIdentity = requestedCursor.checkpointIdentity,
                        checkpointSha256 = requestedCursor.checkpointSha256,
                        leaseToken = null,
                        leaseOwner = null,
                        leaseExpiresAtEpochMillis = null,
                        progressStage = null,
                        progressCompletedUnits = null,
                        progressTotalUnits = null,
                        progressUnit = null,
                        progressUpdatedAtEpochMillis = null,
                        outcomeCode = null,
                        updatedAtEpochMillis = nowEpochMillis,
                        stateVersion = current.stateVersion + 1,
                    )
                    write(current, next)
                    appendEvent(next, JobEventKind.RECOVERED, recoveryCode, nowEpochMillis)
                }
                recovered++
            } catch (_: JobWriteConflictException) {
                conflicts++
            }
        }
        return RecoverySummary(candidates.size, recovered, conflicts, failures)
    }

    private suspend fun mutateLease(
        lease: JobLease,
        nowEpochMillis: Long,
        mutation: (ReconstructionJobEntity) -> ReconstructionJobEntity,
    ): LeasedJob = database.withTransaction {
        val current = requireJob(lease.jobId)
        JobStateMachine.requireValidLease(current.toSnapshot(), lease, nowEpochMillis)
        val next = mutation(current)
        write(current, next)
        next.toSnapshot().asLeased()
    }

    private suspend fun finishLease(
        lease: JobLease,
        nowEpochMillis: Long,
        state: ReconstructionJobState,
        code: String?,
        resultArtifactIdentity: String?,
        resultArtifactSha256: String?,
        kind: JobEventKind,
    ): JobSnapshot = database.withTransaction {
        val current = requireJob(lease.jobId)
        JobStateMachine.requireValidLease(current.toSnapshot(), lease, nowEpochMillis)
        val next = current.copy(
            state = state.name,
            leaseToken = null,
            leaseOwner = null,
            leaseExpiresAtEpochMillis = null,
            resultArtifactIdentity = resultArtifactIdentity,
            resultArtifactSha256 = resultArtifactSha256,
            outcomeCode = code,
            updatedAtEpochMillis = nowEpochMillis,
            stateVersion = current.stateVersion + 1,
        )
        write(current, next)
        appendEvent(next, kind, code, nowEpochMillis)
        next.toSnapshot()
    }

    private suspend fun command(
        jobId: JobId,
        nowEpochMillis: Long,
        mutation: (ReconstructionJobEntity, ReconstructionJobState) -> CommandMutation,
    ): JobCommandResult = database.withTransaction {
        val current = requireJob(jobId)
        val snapshot = current.toSnapshot()
        JobStateMachine.requireCommandTime(snapshot, nowEpochMillis)
        val result = mutation(current, snapshot.state)
        if (result.disposition != JobCommandDisposition.APPLIED) {
            return@withTransaction JobCommandResult(snapshot, result.disposition)
        }
        val prepared = requireNotNull(result.entity).copy(
            updatedAtEpochMillis = nowEpochMillis,
            stateVersion = current.stateVersion + 1,
        )
        write(current, prepared)
        appendEvent(
            prepared,
            requireNotNull(result.kind),
            result.code,
            nowEpochMillis,
        )
        JobCommandResult(prepared.toSnapshot(), JobCommandDisposition.APPLIED)
    }

    private suspend fun write(current: ReconstructionJobEntity, next: ReconstructionJobEntity) {
        require(next.jobId == current.jobId)
        require(next.stateVersion == current.stateVersion + 1)
        val changed = dao.compareAndSet(
            jobId = next.jobId,
            expectedStateVersion = current.stateVersion,
            state = next.state,
            acceptedStage = next.acceptedStage,
            checkpointIdentity = next.checkpointIdentity,
            checkpointSha256 = next.checkpointSha256,
            attempt = next.attempt,
            leaseToken = next.leaseToken,
            leaseOwner = next.leaseOwner,
            leaseExpiresAtEpochMillis = next.leaseExpiresAtEpochMillis,
            resultArtifactIdentity = next.resultArtifactIdentity,
            resultArtifactSha256 = next.resultArtifactSha256,
            outcomeCode = next.outcomeCode,
            progressStage = next.progressStage,
            progressCompletedUnits = next.progressCompletedUnits,
            progressTotalUnits = next.progressTotalUnits,
            progressUnit = next.progressUnit,
            progressUpdatedAtEpochMillis = next.progressUpdatedAtEpochMillis,
            updatedAtEpochMillis = next.updatedAtEpochMillis,
            nextStateVersion = next.stateVersion,
        )
        if (changed != 1) throw JobWriteConflictException(JobId(current.jobId))
    }

    private suspend fun appendEvent(
        entity: ReconstructionJobEntity,
        kind: JobEventKind,
        code: String?,
        nowEpochMillis: Long,
        progress: JobProgress? = entity.toSnapshot().progress,
    ) {
        code?.let { requireSafeCode("event code", it) }
        dao.insertEvent(
            ReconstructionJobEventEntity(
                jobId = entity.jobId,
                attempt = entity.attempt,
                kind = kind.name,
                state = entity.state,
                stage = entity.acceptedStage,
                stateVersion = entity.stateVersion,
                code = code,
                progressStage = progress?.stage?.name,
                progressCompletedUnits = progress?.completedUnits,
                progressTotalUnits = progress?.totalUnits,
                progressUnit = progress?.unit?.name,
                createdAtEpochMillis = nowEpochMillis,
            ),
        )
        dao.trimHistory(entity.jobId, historyLimit)
    }

    private suspend fun requireJob(jobId: JobId): ReconstructionJobEntity =
        dao.job(jobId.value) ?: throw JobNotFoundException(jobId)

    private fun JobSnapshot.asLeased(): LeasedJob = LeasedJob(this, requireNotNull(lease))

    private data class CommandMutation(
        val disposition: JobCommandDisposition,
        val entity: ReconstructionJobEntity? = null,
        val kind: JobEventKind? = null,
        val code: String? = null,
    ) {
        companion object {
            fun apply(entity: ReconstructionJobEntity, kind: JobEventKind, code: String?) =
                CommandMutation(JobCommandDisposition.APPLIED, entity, kind, code)
            fun already() = CommandMutation(JobCommandDisposition.ALREADY_SATISFIED)
            fun terminal() = CommandMutation(JobCommandDisposition.TERMINAL_UNCHANGED)
        }
    }

    companion object {
        private const val MAX_JOB_ID_ATTEMPTS = 8
        const val MAX_HISTORY_LIMIT = 256
        const val MAX_QUEUE_LIMIT = 100
        const val MAX_RECOVERY_LIMIT = 100
    }
}
