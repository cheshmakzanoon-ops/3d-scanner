package com.realityforge.scanner.ui.navigation

import kotlinx.coroutines.CancellationException

/**
 * Notification permission is optional, never the owner of a reconstruction request.
 * The Unit-returning service performs its own admission checks; returning here is NOT
 * evidence of a saved job. The destination must read actual persisted job state.
 */
internal object ReconstructionNotificationRequest {
    fun submit(
        reconstruct: (() -> Unit)?,
        inspectJobs: () -> Unit,
        requestNotifications: () -> Unit,
        notify: (String) -> Unit,
    ) {
        if (reconstruct == null) {
            notify("Processing is unavailable in this configuration.")
            return
        }
        // Preserve the service's operation reservation and selection/revision fences.
        // Do not catch admission failures as though they were permission failures.
        reconstruct()
        inspectJobs()
        try {
            requestNotifications()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The already submitted request must not be retried or cancelled here.
            notify("Could not request notifications. Check saved processing jobs in the app.")
        }
    }

    // No job reference or admission callback: recreation cannot replay a submission.
    fun permissionMessage(granted: Boolean): String? = if (granted) null else
        "Notifications are disabled. Check saved processing jobs in the app."
}
