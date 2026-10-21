package com.realityforge.scanner.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.realityforge.scanner.BuildConfig
import com.realityforge.scanner.R
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.preferences.*
import com.realityforge.scanner.ui.preferences.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PreferencesRoute(busy:Boolean,modifier:Modifier,onHelp:()->Unit,
    read:PreferenceRead,onSaved:(PreferenceRead)->Unit) {
    val context=LocalContext.current
    val store=remember(context) {PreferencesStore(context)}
    val scope=rememberCoroutineScope()
    var saving by remember {mutableStateOf(false)}
    var saved by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val failure=stringResource(R.string.a071_error)
    PreferencesScreen(PreferencesUiState(read.preferences,BuildConfig.VERSION_NAME,busy=saving || busy,
        issue=read.issue,error=error,saved=saved),onAction={action->when(action) {
        PreferencesAction.Help -> onHelp()
        is PreferencesAction.Save, PreferencesAction.Reset -> if(!saving) {
            saving=true;scope.launch {
                val outcome=withContext(Dispatchers.IO) {runCatching {
                    if(action is PreferencesAction.Save) store.save(action.preferences) else store.reset()
                    store.read()
                }}
                outcome.onSuccess {onSaved(it);saved=true;error=null}.onFailure {error=failure}
                saving=false
            }
        }
        else -> Unit // Storage/notices stay unavailable until their owners publish working routes.
    }},modifier=modifier)
}
