package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.importing.ImportPublicationResult
import java.io.IOException

/** Preserve typed rejection/recovery information for callers of the legacy Boolean API. */
class PhotoImportFailureException(val outcome: ImportPublicationResult) : IOException(
    when (outcome) {
        is ImportPublicationResult.Rejected -> "${outcome.code}: ${outcome.message}"
        is ImportPublicationResult.PendingRecovery -> "IMPORT_PENDING_RECOVERY: ${outcome.message}"
        is ImportPublicationResult.OriginalIntegrityFailure -> "ORIGINAL_INTEGRITY_FAILURE: ${outcome.message}"
        else -> error("A successful import is not a failure.")
    },
)

/** Only a verified duplicate is false; rejected/uncertain publication is never success. */
internal fun ImportPublicationResult.toSinglePhotoImportResult(): Boolean = when (this) {
    is ImportPublicationResult.Imported -> true
    is ImportPublicationResult.Duplicate -> false
    is ImportPublicationResult.Rejected,
    is ImportPublicationResult.PendingRecovery,
    is ImportPublicationResult.OriginalIntegrityFailure -> throw PhotoImportFailureException(this)
}
