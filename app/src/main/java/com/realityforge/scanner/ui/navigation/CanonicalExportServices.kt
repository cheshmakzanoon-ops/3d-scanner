package com.realityforge.scanner.ui.navigation

import android.net.Uri
import com.realityforge.scanner.export.ArtifactIdentity
import com.realityforge.scanner.export.ExportOption
import com.realityforge.scanner.export.ExportRequest
import com.realityforge.scanner.export.ExportSource
import com.realityforge.scanner.export.SafCoordinatorOutcome

/** Installed as one complete001-owned operation provider, never assembled from nullable callbacks.
 * C8 admission/transport remains disabled until that owner installs this port and its UI binding.
 * Implementations must reserve the exact request through C1 before launching a chooser, persist its
 * process-restoration identity, and use067/026 transfer outcomes without upgrading unverified close.
 * Canonical artifact IDs must never pass through the legacy repository file resolver/copier.
 */
interface CanonicalExportServices {
    val source: ExportSource
    suspend fun snapshot(projectId: String): ArtifactIdentity?
    suspend fun admit(identity: ArtifactIdentity, option: ExportOption): ExportRequest?
    suspend fun recover(request: ExportRequest): Boolean
    suspend fun transfer(request: ExportRequest, destination: Uri): SafCoordinatorOutcome
    suspend fun cancel(requestId: String)
}
