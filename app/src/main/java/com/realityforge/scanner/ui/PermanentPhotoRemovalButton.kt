package com.realityforge.scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.application.PhotoRemovalRequest
import com.realityforge.scanner.application.ProjectCommandAdapter
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.domain.ProjectRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Cancelling the alert performs no command; retries retain the exact confirmed identity. */
@Composable
fun PermanentPhotoRemovalButton(
    model: ScannerViewModel,
    commands: ProjectCommandAdapter,
    photo: ScanPhoto,
    project: ProjectRevision,
    enabled: Boolean,
) {
    // Deliberately do not restore destructive confirmation after recreation. The user reviews it again.
    var confirmation by remember(project.projectId, photo.id) { mutableStateOf<PhotoRemovalRequest?>(null) }
    var failure by remember(project.projectId, photo.id) { mutableStateOf<String?>(null) }
    var submitting by remember(project.projectId, photo.id) { mutableStateOf(false) }
    TextButton(enabled = enabled && !submitting && photo.projectId == project.projectId, onClick = {
        try {
            val bound = PhotoRemovalRequest.bind(project, photo)
            failure = null
            confirmation = bound
        } catch (invalid: Exception) {
            model.notice.value = invalid.message ?: "Cannot identify this photograph safely. Refresh the project."
        }
    }) { Text("Remove original") }

    confirmation?.let { bound ->
        val unchanged = bound.matches(project, photo)
        AlertDialog(
            onDismissRequest = { if (!submitting) { confirmation = null; failure = null } },
            title = { Text("Permanently remove this original?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Photograph ${bound.photoId.take(8)} in project ${bound.project.projectId.take(8)}, " +
                        "revision ${bound.project.revision}.")
                    Text("This permanently deletes the app's original copy of this photograph. It cannot be undone. " +
                        "Photos outside this app are preserved. Reconstruct the project to update its result; " +
                        "any completed result is retained as an earlier revision.")
                    if (!unchanged) Text("The project or photograph changed while this alert was open. " +
                        "Cancel and review it again.", color = MaterialTheme.colorScheme.error)
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (submitting) {
                        Text("Removing the original and completing local cleanup…")
                        LinearProgressIndicator()
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = enabled && unchanged && !submitting, onClick = {
                    // projectCommand may decline a busy operation. Only mark submitting inside its accepted action.
                    model.projectCommand(allowPendingPhotoDeletion = true) { source ->
                        withContext(Dispatchers.Main) { submitting = true; failure = null }
                        try {
                            bound.requireSource(source)
                            commands.removePhoto(source, bound).also {
                                withContext(Dispatchers.Main) { confirmation = null }
                            }
                        } catch (cancelled: CancellationException) {
                            withContext(NonCancellable + Dispatchers.Main) {
                                failure = "Removal was interrupted. Local cleanup may already be pending. " +
                                    "Retry removal to finish the same confirmed request."
                            }
                            throw cancelled
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main) {
                                failure = (error.message ?: "Local cleanup could not finish.") +
                                    " Retry removal when the project is idle."
                            }
                            throw error
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) { submitting = false }
                        }
                    }
                }) { Text(if (failure == null) "Remove permanently" else "Retry removal") }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = { confirmation = null; failure = null }) { Text("Cancel") }
            },
        )
    }
}
