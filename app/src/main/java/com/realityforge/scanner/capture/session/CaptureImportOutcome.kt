package com.realityforge.scanner.capture.session

import com.realityforge.scanner.data.importing.ImportPublicationResult

/** Only a confirmed new durable import authorizes releasing the separate CameraX source copy. */
internal fun ImportPublicationResult.toCaptureCommitOutcome(expectedRevision: Long): CaptureCommitOutcome = when (this) {
    is ImportPublicationResult.Imported -> if (expectedRevision < Long.MAX_VALUE && committedRevision == expectedRevision + 1)
        CaptureCommitOutcome.Accepted(committedRevision, CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL)
        else CaptureCommitOutcome.Uncertain("Capture publication revision could not be verified; reopen the project.")
    is ImportPublicationResult.Duplicate -> if (revision == expectedRevision) CaptureCommitOutcome.Duplicate(revision)
        else CaptureCommitOutcome.Uncertain("Duplicate publication revision changed; reopen the project.")
    is ImportPublicationResult.OriginalIntegrityFailure -> CaptureCommitOutcome.Uncertain(message)
    is ImportPublicationResult.PendingRecovery -> CaptureCommitOutcome.Uncertain(message)
    is ImportPublicationResult.Rejected -> CaptureCommitOutcome.Rejected(message)
}
