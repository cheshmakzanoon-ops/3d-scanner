package com.realityforge.scanner.ui.processing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProcessingScreen(state: JobUiState, onAction: suspend (JobAction)->Boolean, modifier: Modifier = Modifier) {
    var details by rememberSaveable(state.identity.jobId) { mutableStateOf(false) }
    val gate = remember(state.identity) { JobActionGate() }
    var actionPending by remember(state.identity) { mutableStateOf(false) }
    var actionRejected by remember(state.identity) { mutableStateOf(false) }
    val dispatch by rememberUpdatedState(onAction)
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.a074_title),style=MaterialTheme.typography.headlineMedium)
        val phaseLabel=stringResource(when(state.phase) {
            JobPhase.QUEUED->R.string.a074_queued; JobPhase.RUNNING->R.string.a074_running; JobPhase.PAUSED->R.string.a074_paused
            JobPhase.INTERRUPTED->R.string.a074_interrupted; JobPhase.FAILED->R.string.a074_failed; JobPhase.CANCELLED->R.string.a074_cancelled; JobPhase.COMPLETED->R.string.a074_completed; JobPhase.STALE->R.string.a074_stale; JobPhase.BLOCKED_EXTERNAL->R.string.a074_blocked })
        Text(phaseLabel,Modifier.semantics { liveRegion=LiveRegionMode.Polite; heading() },style=MaterialTheme.typography.titleLarge)
        state.stage?.let { Text(it) }
        if (state.phase == JobPhase.RUNNING || state.phase == JobPhase.QUEUED) {
            val fraction=state.stageFraction
            if(fraction==null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.a074_unknown)) }
            else { LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth()); Text(stringResource(R.string.a074_counts,state.completed!!,state.total!!)) }
        }
        state.reusedStages?.takeIf { it>=0 }?.let { Text(pluralStringResource(R.plurals.a074_reused,it,it)) }
        state.resourceConstraint?.let { Text(it) }
        state.message?.let { Text(it,color=if(state.phase==JobPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        if(!state.inputsCurrent) Text(stringResource(R.string.a074_changed))
        JobCommand.entries.filter(state::can).forEach { command ->
            OutlinedButton(enabled=!actionPending,onClick={
                val action=JobAction(state.identity,command)
                // Admit on the UI thread before launching: two rapid clicks cannot both dispatch.
                if(gate.admit(state,action)) {
                    actionPending=true; actionRejected=false
                    scope.launch {
                        var accepted=false
                        try { accepted=dispatch(action); actionRejected=!accepted }
                        catch(cancelled:CancellationException) { throw cancelled }
                        catch(_:Exception) { actionRejected=true }
                        finally {
                            gate.complete(action,accepted)
                            // These remembered values belong to this full identity. An old completion
                            // cannot clear the new version's gate or re-enable its controls.
                            actionPending=gate.isPending(state.identity)
                        }
                    }
                }
            },modifier=Modifier.fillMaxWidth()) {
                Text(stringResource(when(command) { JobCommand.PAUSE->R.string.a074_pause; JobCommand.RESUME->R.string.a074_resume; JobCommand.RETRY->R.string.a074_retry; JobCommand.CANCEL->R.string.a074_cancel; JobCommand.OPEN_PREVIOUS->R.string.a074_previous; JobCommand.NOTIFICATION_SETTINGS->R.string.a074_notifications }))
            }
        }
        if(actionRejected) Text(stringResource(R.string.a074_failed),color=MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.a074_preserved),style=MaterialTheme.typography.bodySmall)
        if(state.technicalDetails!=null || state.settingsIdentity!=null) {
            TextButton(onClick={details=!details}) { Text(stringResource(R.string.a074_details)) }
            if(details) { state.settingsIdentity?.let { Text(stringResource(R.string.a074_settings,it)) }; state.technicalDetails?.let { Text(it) } }
        }
    }
}
