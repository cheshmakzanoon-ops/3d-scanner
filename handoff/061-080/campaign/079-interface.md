package com.realityforge.scanner.preferences

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

enum class DisplayTheme { SYSTEM, LIGHT, DARK }
data class UserPreferences(val fallbackFocal35mm:Double=26.0,val theme:DisplayTheme=DisplayTheme.SYSTEM,
    val onboardingCompletedVersion:Int=0) {
    init { require(fallbackFocal35mm.isFinite() && fallbackFocal35mm in 10.0..200.0); require(onboardingCompletedVersion in 0..1000000) }
}
data class PreferenceRead(val preferences:UserPreferences,val issue:PreferenceIssue?=null)
enum class PreferenceIssue { CORRUPT, UNSUPPORTED_VERSION, UNREADABLE }
object PreferenceCodec {
    const val VERSION=1
    const val MAX_BYTES=4096
    fun encode(value:UserPreferences):String="schema=1\nfocal=${value.fallbackFocal35mm}\ntheme=${value.theme.name}\nonboarding=${value.onboardingCompletedVersion}\n"
    fun decode(text:String):UserPreferences {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Preference file exceeds budget" }
        val pairs=text.lineSequence().filter { it.isNotEmpty() }.map { row ->
            require(row.count { it=='=' }==1); val parts=row.split('=',limit=2); parts[0] to parts[1]
        }.toList()
        require(pairs.map { it.first }.distinct().size==pairs.size) { "Duplicate preference field" }
        val fields=pairs.toMap()
        val version=fields["schema"]?.toIntOrNull()?:error("Missing preference schema")
        if(version!=VERSION) throw UnsupportedPreferenceVersion()
        require(fields.keys==setOf("schema","focal","theme","onboarding")) { "Unknown or missing preference field" }
        return UserPreferences(fields.getValue("focal").toDouble(),DisplayTheme.valueOf(fields.getValue("theme")),fields.getValue("onboarding").toInt())
    }
}
class UnsupportedPreferenceVersion:IllegalArgumentException("Unsupported preference schema")

/** Private local defaults only. Saving never changes a project/job's recorded input settings. */
class PreferencesStore(directory:File) {
    constructor(context:Context):this(File(context.filesDir,"user-preferences"))
    private val directory=directory.canonicalFile
    private val file=File(this.directory,"preferences.v1")
    private val guard=locks.computeIfAbsent(file.path) { Any() }
    fun read():PreferenceRead=synchronized(guard) {
        if(!file.exists()) return@synchronized PreferenceRead(UserPreferences())
        try {
            require(file.length()<=PreferenceCodec.MAX_BYTES)
            PreferenceRead(PreferenceCodec.decode(file.readText(Charsets.UTF_8)))
        } catch(_:UnsupportedPreferenceVersion) { PreferenceRead(UserPreferences(),PreferenceIssue.UNSUPPORTED_VERSION) }
        catch(_:IllegalArgumentException) { PreferenceRead(UserPreferences(),PreferenceIssue.CORRUPT) }
        catch(_:IllegalStateException) { PreferenceRead(UserPreferences(),PreferenceIssue.CORRUPT) }
        catch(_:java.io.IOException) { PreferenceRead(UserPreferences(),PreferenceIssue.UNREADABLE) }
    }
    fun save(value:UserPreferences) = synchronized(guard) {
        check(read().issue==null) { "Resolve unreadable or unsupported settings before saving" }
        write(value)
    }
    /** Explicit recovery preserves the rejected bytes for diagnosis, without sending them anywhere. */
    fun reset() = synchronized(guard) {
        if(file.exists()) {
            val backup=File(directory,"preferences.rejected.${System.currentTimeMillis()}")
            Files.copy(file.toPath(),backup.toPath())
        }
        write(UserPreferences())
    }
    private fun write(value:UserPreferences) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create private preferences directory" }
        val temporary=File.createTempFile("preferences-",".partial",directory)
        try {
            FileOutputStream(temporary).use { it.write(PreferenceCodec.encode(value).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}
package com.realityforge.scanner.ui.preferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
        Button(enabled=!state.busy && state.issue==null && value!=null,onClick={onAction(PreferencesAction.Save(state.preferences.copy(fallbackFocal35mm=value!!,theme=theme)))},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.a079_save)) }
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
