package com.realityforge.scanner.capture.session

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep the source alive until guidance is done. Generic publishers retain their existing ownership
 * contract. Only the durable-import adapter can attest that an independent original was copied.
 * Uncertain and duplicate sources remain cache, not a promise of durable recovery storage.
 */
internal suspend fun finishCapturePublication(
    photo: CapturedPhoto,
    outcome: CaptureCommitOutcome,
    controller: CaptureSessionController,
    staging: CaptureStagingStore,
    acceptedGuidance: suspend () -> Unit,
    onGuidanceFailure: () -> Unit,
    discard: (File) -> Boolean = staging::discard,
) {
    try {
        if (outcome is CaptureCommitOutcome.Accepted && outcome.committedRevision > photo.revision) {
            try { acceptedGuidance() }
            catch (_: CancellationException) { /* Durable acceptance is not undone by optional work. */ }
            catch (_: LinkageError) { onGuidanceFailure() }
            catch (_: Exception) { onGuidanceFailure() }
        }
    } finally {
        withContext(NonCancellable) {
            val applied = controller.onCommit(photo.captureToken, outcome, expectedPhoto = photo)
            val owned = try { staging.isOwned(photo.stagedFile) } catch (_: Exception) { false }
            if (applied && outcome is CaptureCommitOutcome.Accepted &&
                outcome.stagingDisposition == CaptureStagingDisposition.COPIED_TO_PRIVATE_ORIGINAL &&
                outcome.committedRevision > photo.revision &&
                photo.projectId == controller.identity.projectId && photo.sessionToken == controller.identity.sessionToken &&
                owned) {
                val removed = try { discard(photo.stagedFile) } catch (_: Exception) { false }
                if (!removed) controller.reportNonFatal("Photograph saved; the temporary capture copy could not be removed.")
            }
        }
    }
}
