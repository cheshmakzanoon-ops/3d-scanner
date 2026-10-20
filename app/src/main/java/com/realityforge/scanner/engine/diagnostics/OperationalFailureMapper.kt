package com.realityforge.scanner.engine.diagnostics

import com.realityforge.scanner.data.operations.ProjectAccessException
import com.realityforge.scanner.data.operations.ProjectMissingException
import com.realityforge.scanner.data.operations.StaleProjectRevisionException
import com.realityforge.scanner.data.storagebudget.InsufficientStorageException
import com.realityforge.scanner.engine.nativebridge.NativeBoundaryFailure
import com.realityforge.scanner.engine.pipeline.UnsupportedPipelineProfileException
import com.realityforge.scanner.processing.resources.ResourceCheckpointPause
import java.io.IOException

/** Owners use this when the source knows the failure type before any exception translation. */
internal class DiagnosticOperationException(val failure: ScanFailure) : IllegalStateException(failure.messageKey)

/** Map known operational types; exception messages, file paths and photographs never enter C9. */
internal object OperationalFailureMapper {
    fun from(stage: ScanStage, throwable: Throwable): ScanFailure = when (throwable) {
        is DiagnosticOperationException -> throwable.failure
        is ResourceCheckpointPause -> resourcePaused(stage)
        is InsufficientStorageException -> {
            val values = linkedMapOf<DiagnosticContextKey, DiagnosticValue>(
                DiagnosticContextKey.REQUIRED_BYTES to DiagnosticValue.Count(throwable.decision.requiredBytes))
            throwable.decision.measuredAvailableBytes?.let {
                values[DiagnosticContextKey.AVAILABLE_BYTES] = DiagnosticValue.Count(it)
            }
            val context = DiagnosticContext.from(values)
            if (throwable.decision.measuredAvailableBytes == null)
                ScanFailure(ScanFailureCode.STORAGE_MEASUREMENT_UNAVAILABLE, stage, StageOutcome.PAUSED,
                    RecoveryAction.RESUME_JOB, "scan.failure.storage_measurement_unavailable", context)
            else storageDenied(stage, permissionRevoked = false, context = context)
        }
        is StaleProjectRevisionException -> ScanFailure(ScanFailureCode.REVISION_MISMATCH, stage,
            StageOutcome.FAILED, RecoveryAction.REVIEW_INPUTS, "scan.failure.revision_mismatch")
        is ProjectMissingException -> ScanFailure(ScanFailureCode.INPUT_CHANGED, stage,
            StageOutcome.FAILED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_changed")
        is ProjectAccessException -> ScanFailure(ScanFailureCode.PROJECT_ACCESS_BLOCKED, stage,
            StageOutcome.PAUSED, RecoveryAction.RETRY_JOB, "scan.failure.project_access_blocked")
        is UnsupportedPipelineProfileException -> ScanFailure(ScanFailureCode.INPUT_UNSUPPORTED, stage,
            StageOutcome.UNSUPPORTED, RecoveryAction.REVIEW_INPUTS, "scan.failure.profile_unsupported",
            DiagnosticContext.of(DiagnosticContextKey.IMPORTED_PHOTO_COUNT to
                DiagnosticValue.Count(throwable.admission.photoCount.toLong())))
        is SecurityException -> storageDenied(stage, permissionRevoked = true)
        is IOException -> ScanFailure(ScanFailureCode.IO_FAILURE, stage, StageOutcome.FAILED,
            RecoveryAction.RETRY_STAGE, "scan.failure.io_failure")
        else -> native(stage, NativeBoundaryFailure.from(throwable))
    }

    fun native(
        stage: ScanStage,
        failure: NativeBoundaryFailure,
        context: DiagnosticContext = DiagnosticContext.EMPTY,
    ): ScanFailure = when (failure) {
        NativeBoundaryFailure.CANCELLED -> ScanFailure(ScanFailureCode.CANCELLED, stage,
            StageOutcome.CANCELLED, RecoveryAction.RETRY_JOB, "scan.failure.cancelled", context)
        NativeBoundaryFailure.RESOURCE_EXHAUSTED -> ScanFailure(ScanFailureCode.RESOURCE_EXHAUSTED, stage,
            StageOutcome.FAILED, RecoveryAction.RETRY_JOB, "scan.failure.resource_exhausted", context)
        NativeBoundaryFailure.INVALID_INPUT -> ScanFailure(ScanFailureCode.INPUT_UNSUPPORTED, stage,
            StageOutcome.FAILED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_unsupported", context)
        NativeBoundaryFailure.LIBRARY_UNAVAILABLE -> ScanFailure(ScanFailureCode.NATIVE_LIBRARY_UNAVAILABLE, stage,
            StageOutcome.UNSUPPORTED, RecoveryAction.RETRY_JOB, "scan.failure.native_library_unavailable", context)
        NativeBoundaryFailure.NATIVE_FAILURE -> ScanFailure(ScanFailureCode.INTERNAL_FAILURE, stage,
            StageOutcome.FAILED, RecoveryAction.RETRY_STAGE, "scan.failure.internal_failure", context)
    }

    fun resourcePaused(
        stage: ScanStage,
        context: DiagnosticContext = DiagnosticContext.EMPTY,
    ): ScanFailure = ScanFailure(ScanFailureCode.RESOURCE_PAUSED, stage, StageOutcome.PAUSED,
        RecoveryAction.RESUME_JOB, "scan.failure.resource_paused", context)

    fun storageDenied(
        stage: ScanStage,
        permissionRevoked: Boolean,
        context: DiagnosticContext = DiagnosticContext.EMPTY,
    ): ScanFailure = if (permissionRevoked) {
        ScanFailure(ScanFailureCode.STORAGE_PERMISSION_REVOKED, stage, StageOutcome.FAILED,
            RecoveryAction.RESTORE_PERMISSION, "scan.failure.storage_permission_revoked", context)
    } else {
        ScanFailure(ScanFailureCode.STORAGE_FULL, stage, StageOutcome.FAILED,
            RecoveryAction.FREE_STORAGE, "scan.failure.storage_full", context)
    }
}
