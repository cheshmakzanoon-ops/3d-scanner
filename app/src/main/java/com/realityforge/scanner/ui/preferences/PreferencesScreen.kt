package com.realityforge.scanner.ui.preferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import com.realityforge.scanner.preferences.*

data class PreferencesUiState(val preferences:UserPreferences,val versionName:String,val busy:Boolean=false,
    val issue:PreferenceIssue?=null,val error:String?=null,val saved:Boolean=false,
    val canManageStorage:Boolean=false,val canShowNotices:Boolean=false)
sealed interface PreferencesAction {
    data class Save(val preferences:UserPreferences):PreferencesAction
    data object Reset:PreferencesAction
    data object Help:PreferencesAction
    data object Storage:PreferencesAction
    data object Notices:PreferencesAction
}
@Composable
fun PreferencesScreen(state:PreferencesUiState,onAction:(PreferencesAction)->Unit,modifier:Modifier=Modifier) {
    var focal by rememberSaveable(state.preferences) { mutableStateOf(state.preferences.fallbackFocal35mm.toString()) }
    var theme by rememberSaveable(state.preferences) { mutableStateOf(state.preferences.theme) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val value=focal.toDoubleOrNull()?.takeIf { it.isFinite() && it in 10.0..200.0 }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.a079_title),style=MaterialTheme.typography.headlineMedium)
        if(state.issue!=null) { Text(stringResource(R.string.a079_recovery),color=MaterialTheme.colorScheme.error); OutlinedButton(enabled=!state.busy,onClick={reset=true}) { Text(stringResource(R.string.a079_reset)) } }
        OutlinedTextField(focal,{focal=it.take(12)},label={Text(stringResource(R.string.a079_default_focal))},isError=value==null,enabled=!state.busy && state.issue==null,singleLine=true,modifier=Modifier.fillMaxWidth())
        if(value==null) Text(stringResource(R.string.a079_invalid),color=MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.a079_default_explained))
        Text(stringResource(R.string.a079_theme),style=MaterialTheme.typography.titleMedium)
        DisplayTheme.entries.forEach { candidate ->
            FilterChip(selected=theme==candidate,onClick={theme=candidate},enabled=!state.busy && state.issue==null,label={Text(stringResource(when(candidate) { DisplayTheme.SYSTEM->R.string.a079_system; DisplayTheme.LIGHT->R.string.a079_light; DisplayTheme.DARK->R.string.a079_dark }))})
        }
        Button(enabled=!state.busy && state.issue==null && value!=null,onClick={onAction(PreferencesAction.Save(state.preferences.copy(fallbackFocal35mm=value!!,theme=theme)))},modifier=Modifier.fillMaxWidth().testTag("a079-save")) { Text(stringResource(R.string.a079_save)) }
        if(state.saved) Text(stringResource(R.string.a079_saved))
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.a079_profiles),style=MaterialTheme.typography.titleMedium); Text(stringResource(R.string.a079_auto_profile))
        Text(stringResource(R.string.a079_about,state.versionName),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.a079_offline)); Text(stringResource(R.string.a079_originals)); Text(stringResource(R.string.a079_exports)); Text(stringResource(R.string.a079_limits))
        OutlinedButton(onClick={onAction(PreferencesAction.Help)},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.a079_guide)) }
        if(state.canManageStorage) OutlinedButton(onClick={onAction(PreferencesAction.Storage)},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.a079_storage)) }
        if(state.canShowNotices) OutlinedButton(onClick={onAction(PreferencesAction.Notices)},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.a079_notices)) }
        else Text(stringResource(R.string.a079_notice_unavailable),style=MaterialTheme.typography.bodySmall)
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text(stringResource(R.string.a079_reset_title))},text={Text(stringResource(R.string.a079_reset_body))},confirmButton={TextButton(enabled=!state.busy,onClick={onAction(PreferencesAction.Reset);reset=false}) { Text(stringResource(R.string.a079_reset)) }},dismissButton={TextButton(onClick={reset=false}) { Text(stringResource(R.string.a079_cancel)) }})
}
