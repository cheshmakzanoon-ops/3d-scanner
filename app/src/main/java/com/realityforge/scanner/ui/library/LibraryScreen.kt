package com.realityforge.scanner.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import java.text.DateFormat
import java.util.Date

/** Host owns persistence and operation admission. Only supported commands are offered. */
@Composable
fun LibraryScreen(state: LibraryUiState, onAction: (LibraryAction) -> Unit, modifier: Modifier = Modifier, onQueryChange:((String,LibrarySort,LibraryFilter)->Unit)?=null) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var create by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val visible = remember(state.projects, query, sort, filter) { LibraryQuery.apply(state.projects, query, sort, filter) }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.a072_library), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(query, { query = it.take(200);onQueryChange?.invoke(query,sort,filter) }, label = { Text(stringResource(R.string.a072_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Column {
                LibrarySort.entries.forEach { value -> FilterChip(sort == value, { sort = value;onQueryChange?.invoke(query,sort,filter) }, { Text(stringResource(when(value) { LibrarySort.NEWEST -> R.string.a072_newest; LibrarySort.OLDEST -> R.string.a072_oldest; LibrarySort.NAME -> R.string.a072_sort_name })) }) }
            }
            Column {
                LibraryFilter.entries.forEach { value -> FilterChip(filter == value, { filter = value;onQueryChange?.invoke(query,sort,filter) }, { Text(stringResource(when(value) { LibraryFilter.ALL -> R.string.a072_all; LibraryFilter.RESULTS -> R.string.a072_results; LibraryFilter.NEEDS_ATTENTION -> R.string.a072_attention })) }) }
            }
            Button(enabled = !state.busy, onClick = { name = ""; create = true },modifier=Modifier.testTag("a072-create")) { Text(stringResource(R.string.a072_create)) }
            if (state.canRestoreArchive) OutlinedButton(enabled = !state.busy, onClick = { onAction(LibraryAction.RestoreArchive) }) { Text(stringResource(R.string.a072_restore)) }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(state.copyingBytes!=null && state.totalCopyBytes!=null) item { Text(stringResource(R.string.a072_copy_progress,state.copyingBytes,state.totalCopyBytes)) }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { onAction(LibraryAction.Retry) }, enabled = !state.busy) { Text(stringResource(R.string.a072_retry)) } } }
        if (!state.loading && visible.isEmpty()) item { Text(stringResource(R.string.a072_empty)) }
        items(visible, key = { it.id }) { project ->
            Card(onClick = { onAction(LibraryAction.Open(project.id)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge)
                    Text(project.message)
                    Text(stringResource(if (project.hasCurrentResult) R.string.a072_ready else R.string.a072_not_ready))
                    project.representation?.let { Text(it) }
                    project.photoCount?.let { Text(pluralStringResource(R.plurals.a072_photo_count, it, it)) }
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.createdAt)), style = MaterialTheme.typography.bodySmall)
                    if (state.canRename) TextButton(enabled = !state.busy, onClick = { name = project.name; renameId = project.id }) { Text(stringResource(R.string.a072_rename)) }
                    if (state.canDuplicate) TextButton(enabled = !state.busy, onClick = { name=project.name;duplicateId=project.id }) { Text(stringResource(R.string.a072_duplicate)) }
                    TextButton(enabled = !state.busy, onClick = { deleteId = project.id }) { Text(stringResource(R.string.a072_delete)) }
                }
            }
        }
        if(state.canLoadMore) item { OutlinedButton(enabled=!state.busy && !state.loading,onClick={onAction(LibraryAction.LoadMore)}) { Text(stringResource(R.string.a072_load_more)) } }
    }
    val duplicate=state.projects.find { it.id==duplicateId }
    val rename = state.projects.find { it.id == renameId }
    if (create || rename != null || duplicate!=null) AlertDialog(onDismissRequest = { create = false; renameId = null;duplicateId=null }, title = { Text(stringResource(if (create) R.string.a072_create else if(duplicate!=null) R.string.a072_duplicate else R.string.a072_rename)) }, text = {
        Column { OutlinedTextField(name, { name = LibraryQuery.capName(it) }, modifier=Modifier.testTag("a072-name"), label = { Text(stringResource(R.string.a072_name)) }, singleLine = true, isError = !LibraryQuery.validName(name)); if (!LibraryQuery.validName(name)) Text(stringResource(R.string.a072_invalid_name)) }
    }, confirmButton = { TextButton(modifier=Modifier.testTag("a072-save"),enabled = !state.busy && LibraryQuery.validName(name), onClick = { if (create) onAction(LibraryAction.Create(name.trim())) else if(duplicate!=null) onAction(LibraryAction.Duplicate(duplicate.id,duplicate.revision,name.trim())) else rename?.let { onAction(LibraryAction.Rename(it.id, it.revision, name.trim())) }; create = false; renameId = null;duplicateId=null }) { Text(stringResource(R.string.a072_save)) } }, dismissButton = { TextButton(onClick = { create = false; renameId = null;duplicateId=null }) { Text(stringResource(R.string.a072_cancel)) } })
    state.projects.find { it.id == deleteId }?.let { project -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.a072_delete_title, project.name)) }, text = { Text(stringResource(R.string.a072_delete_body)) }, confirmButton = { TextButton(enabled = !state.busy, onClick = { onAction(LibraryAction.Delete(project.id, project.revision)); deleteId = null }) { Text(stringResource(R.string.a072_delete)) } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.a072_cancel)) } }) }
}
