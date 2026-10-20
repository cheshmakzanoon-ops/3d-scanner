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
