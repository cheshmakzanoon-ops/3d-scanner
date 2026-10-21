package com.realityforge.scanner.capture.controls

sealed class BackendControlResult<out T> {
    data class Confirmed<T>(
        val value: T,
        val metadata: AppliedOpticsMetadata? = null,
        val detail: String? = null,
    ) : BackendControlResult<T>()

    data class Acknowledged<T>(
        val value: T,
        val metadata: AppliedOpticsMetadata? = null,
        val detail: String? = null,
    ) : BackendControlResult<T>()

    data class Unsupported(val detail: String) : BackendControlResult<Nothing>()

    data class Failed(
        val diagnosticCode: String,
        val detail: String,
        val cause: Throwable? = null,
    ) : BackendControlResult<Nothing>()

    data class Cancelled(val detail: String) : BackendControlResult<Nothing>()
}

data class LockBackendResult(
    val ae: BackendControlResult<Boolean>?,
    val awb: BackendControlResult<Boolean>?,
    val metadata: AppliedOpticsMetadata? = null,
)

/**
 * Narrow adapter boundary between session-safe control state and a concrete camera binding.
 * Implementations must never update UI state directly; the controller verifies the session
 * generation before publishing every result.
 */
interface OpticalControlBackend {
    suspend fun setZoomRatio(ratio: Float): BackendControlResult<Float>

    suspend fun setExposureCompensation(index: Int): BackendControlResult<Int>

    suspend fun applyFocus(request: FocusRequest): BackendControlResult<FocusRequest>

    suspend fun setLocks(
        aeLocked: Boolean?,
        awbLocked: Boolean?,
        confirmationTimeoutMillis: Long,
    ): LockBackendResult

    fun latestMetadata(): AppliedOpticsMetadata?

    /** Best-effort cancellation. Late platform completions are still rejected by generation. */
    fun cancelPending(reason: String)
}
