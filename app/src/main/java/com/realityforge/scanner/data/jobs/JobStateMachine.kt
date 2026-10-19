package com.realityforge.scanner.data.jobs

import kotlin.math.abs

object JobStateMachine {
    const val MAX_LEASE_DURATION_MILLIS = 24L * 60L * 60L * 1000L
    const val MIN_PROGRESS_INTERVAL_MILLIS = 1_000L
    const val MIN_PROGRESS_FRACTION_DELTA = 0.01

    fun checkedExpiry(nowEpochMillis: Long, leaseDurationMillis: Long): Long {
        require(nowEpochMillis >= 0) { "Current time cannot be negative." }
        require(leaseDurationMillis in 1..MAX_LEASE_DURATION_MILLIS) {
            "Lease duration must be between 1 ms and 24 hours."
        }
        require(nowEpochMillis <= Long.MAX_VALUE - leaseDurationMillis) { "Lease expiry overflow." }
        return nowEpochMillis + leaseDurationMillis
    }

    fun requireValidLease(snapshot: JobSnapshot, lease: JobLease, nowEpochMillis: Long) {
        if (nowEpochMillis < snapshot.createdAtEpochMillis) {
            throw JobLeaseRejectedException("Lease validation timestamp predates the job.")
        }
        if (snapshot.state != ReconstructionJobState.RUNNING) {
            throw JobLeaseRejectedException("Job ${snapshot.jobId} is not running.")
        }
        val current = snapshot.lease
            ?: throw JobLeaseRejectedException("Job ${snapshot.jobId} has no active lease.")
        if (current.attempt != lease.attempt || current.token != lease.token || current.workerId != lease.workerId) {
            throw JobLeaseRejectedException("Lease attempt or token has been superseded.")
        }
        if (current.stateVersion != lease.stateVersion || snapshot.stateVersion != lease.stateVersion) {
            throw JobLeaseRejectedException("Lease state version is stale.")
        }
        if (current.expiresAtEpochMillis <= nowEpochMillis) {
            throw JobLeaseRejectedException("Lease expired before this mutation.")
        }
    }

    fun requireStageAdvance(current: JobStageCursor, candidate: JobStageCursor) {
        val delta = candidate.acceptedStage.order - current.acceptedStage.order
        if (delta !in 0..1) {
            throw JobTransitionRejectedException(
                "Stage cursor must remain at ${current.acceptedStage} or advance exactly one stage.",
            )
        }
        if (delta == 0 && current != candidate) {
            throw JobTransitionRejectedException(
                "An accepted stage is immutable; only an exact idempotent replay is allowed.",
            )
        }
    }

    fun requireRecoveryCursor(current: JobStageCursor, recovered: JobStageCursor) {
        if (recovered.acceptedStage.order > current.acceptedStage.order) {
            throw JobTransitionRejectedException("Recovery cannot claim a stage the worker never accepted.")
        }
        if (recovered.acceptedStage == current.acceptedStage && recovered != current) {
            throw JobTransitionRejectedException(
                "Recovery cannot replace an accepted checkpoint at the same stage.",
            )
        }
    }

    fun requireProgress(cursor: JobStageCursor, progress: JobProgress) {
        val delta = progress.stage.order - cursor.acceptedStage.order
        if (delta !in 0..1) {
            throw JobTransitionRejectedException(
                "Progress must describe the accepted stage or the immediately executing stage.",
            )
        }
    }

    fun shouldPersistProgress(
        previous: JobProgress?,
        previousAtEpochMillis: Long?,
        candidate: JobProgress,
        nowEpochMillis: Long,
    ): Boolean {
        require(nowEpochMillis >= 0)
        if (previous == null || previousAtEpochMillis == null) return true
        if (previous.stage != candidate.stage || previous.unit != candidate.unit) return true
        if (candidate.completedUnits < previous.completedUnits) {
            throw JobTransitionRejectedException("Progress cannot move backwards within a stage.")
        }
        if (candidate.totalUnits != previous.totalUnits) return true
        if (candidate.totalUnits != null && candidate.completedUnits == candidate.totalUnits) return true
        val elapsed = nowEpochMillis - previousAtEpochMillis
        if (elapsed < 0) throw JobTransitionRejectedException("Progress timestamp moved backwards.")
        val enoughTime = elapsed >= MIN_PROGRESS_INTERVAL_MILLIS
        val enoughChange = if (candidate.totalUnits != null && previous.totalUnits == candidate.totalUnits) {
            val denominator = candidate.totalUnits.toDouble()
            abs(candidate.completedUnits - previous.completedUnits) / denominator >= MIN_PROGRESS_FRACTION_DELTA
        } else {
            candidate.completedUnits > previous.completedUnits
        }
        return enoughTime && enoughChange
    }

    fun requireCommandTime(snapshot: JobSnapshot, nowEpochMillis: Long) {
        if (nowEpochMillis < snapshot.createdAtEpochMillis) {
            throw JobTransitionRejectedException("Command timestamp predates the job.")
        }
    }
}
