package com.realityforge.scanner.localization

import android.content.Context
import com.realityforge.scanner.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Technical evidence stays separate and is never inserted into the actionable summary. */
data class UserMessage(val code: Code, val evidence: String? = null) {
    enum class Code { UnknownFailure, InvalidInput, StorageFull, Interrupted, StaleResult, UnsupportedAsset, NoCalibration }
}

class UserMessageResolver(private val context: Context) {
    fun resolve(message: UserMessage): String = context.getString(when (message.code) {
        UserMessage.Code.UnknownFailure -> R.string.rf_message_failed
        UserMessage.Code.InvalidInput -> R.string.rf_message_invalid_input
        UserMessage.Code.StorageFull -> R.string.rf_message_storage_full
        UserMessage.Code.Interrupted -> R.string.rf_message_interrupted
        UserMessage.Code.StaleResult -> R.string.rf_message_stale_result
        UserMessage.Code.UnsupportedAsset -> R.string.rf_message_unsupported_asset
        UserMessage.Code.NoCalibration -> R.string.rf_message_no_calibration
    })
    /** Known C1 codes resolve without parsing or displaying free-form English exceptions. */
    fun resolveCode(code: String): String = context.getString(when(code) {
        "import.completed" -> R.string.rf_import_finished
        "capture.saved" -> R.string.rf_capture_saved
        "calibration.saved" -> R.string.rf_calibration_saved
        "calibration.removed" -> R.string.rf_calibration_removed
        "reconstruction.saved" -> R.string.rf_reconstruction_saved
        "export.completed", "capture_export.completed" -> R.string.rf_export_finished
        "operation.pending_request", "operation.request_conflict" -> R.string.rf_pending_request
        "operation.stale_request", "operation.pending_restore_rejected", "export.no_current_result" -> R.string.rf_message_stale_result
        "project.none_selected", "project.not_loaded", "preview.open_failed" -> R.string.rf_project_unavailable
        "calibration.interrupted", "capture.import_interrupted", "capture_export.interrupted", "export.interrupted",
        "import.interrupted", "project.create_interrupted", "reconstruction.interrupted" -> R.string.rf_message_interrupted
        "project.delete_interrupted", "project.delete_failed" -> R.string.rf_delete_incomplete
        "calibration.rejected", "calibration.remove_failed", "capture.import_failed", "capture_export.failed",
        "export.failed", "import.failed", "native.load_failed", "project.create_failed", "reconstruction.failed" -> R.string.rf_message_failed
        else -> R.string.rf_operation_updated
    })
    fun projectSummary(state: String, hasCurrentResult: Boolean): String = context.getString(when(state) {
        "NEW" -> R.string.rf_project_new
        "READY" -> R.string.rf_project_ready
        "PROCESSING" -> R.string.rf_project_processing
        "INTERRUPTED" -> R.string.rf_message_interrupted
        "FAILED" -> R.string.rf_message_failed
        "SPARSE_READY" -> if(hasCurrentResult) R.string.rf_reconstruction_saved else R.string.rf_message_stale_result
        else -> R.string.rf_operation_updated
    })
    fun photoCount(count: Int): String {
        require(count >= 0)
        return context.resources.getQuantityString(R.plurals.rf_photo_count, count, count)
    }
}

/** Explicit units remain outside number formatting; arbitrary distances never become meters. */
object LocalizedFormats {
    fun number(value: Double, locale: Locale, fractionDigits: Int = 2): String {
        require(value.isFinite() && fractionDigits in 0..8)
        return NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = fractionDigits
            minimumFractionDigits = 0
        }.format(value)
    }
    fun percent(fraction: Double, locale: Locale): String {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        return NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(fraction)
    }
    fun date(timestampMillis: Long, locale: Locale): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(timestampMillis))
}
