package com.realityforge.scanner.domain

/** Immutable application-facing state. UI owners may adapt this without owning domain work. */
data class ScannerState(
    val selectedProject: SelectedProjectState? = null,
    val selectionGeneration: Long = 0L,
    val activeOperation: OperationContext? = null,
    val pendingRequests: List<PendingRequestState> = emptyList(),
    val isBusy: Boolean = false,
    val progress: OperationProgress? = null,
    val preview: PreviewState = PreviewState.None,
    val calibration: CalibrationState = CalibrationState.None,
    val message: PresentationMessage? = null,
)

data class SelectedProjectState(
    val projectId: String,
    val revision: Long,
    val name: String,
    val persistedState: String,
)

data class PendingRequestState(
    val requestId: RequestId,
    val kind: OperationKind,
    val project: ProjectRevision,
    val sessionId: SessionId?,
    val createdAtEpochMillis: Long,
) {
    companion object {
        fun from(request: BoundExternalRequest) = PendingRequestState(
            requestId = request.requestId,
            kind = request.kind,
            project = request.project,
            sessionId = request.sessionId,
            createdAtEpochMillis = request.createdAtEpochMillis,
        )
    }
}

data class OperationProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
)

sealed class PreviewState {
    data object None : PreviewState()
    data class Loading(val project: ProjectRevision) : PreviewState()
    data class Ready(
        val project: ProjectRevision,
        val artifactKey: String,
        val representation: String,
    ) : PreviewState()
    data class Failed(val projectId: String, val reason: String) : PreviewState()
}

sealed class CalibrationState {
    data object None : CalibrationState()
    data class Loading(val projectId: String) : CalibrationState()
    data class Estimated(val projectId: String) : CalibrationState()
    data class Calibrated(val projectId: String, val acceptedViews: Int, val rmsPixels: Double) : CalibrationState()
    data class Invalid(val projectId: String, val reason: String) : CalibrationState()
}

data class PresentationMessage(
    val code: String,
    val fallbackText: String,
    val arguments: Map<String, String> = emptyMap(),
)
