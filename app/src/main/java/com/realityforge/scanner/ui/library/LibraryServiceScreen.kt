package com.realityforge.scanner.ui.library
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Compose binding when the application has installed Agent019's transactional port. */
@Composable
fun LibraryServiceScreen(presenter:LibraryPresenter,onHostAction:(LibraryAction)->Unit,modifier:Modifier=Modifier) {
    val state by presenter.state.collectAsState()
    val scope=rememberCoroutineScope()
    var queryJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(presenter) { presenter.refresh() }
    LibraryScreen(state,onAction={ action -> when(action) {
        is LibraryAction.Rename,is LibraryAction.Duplicate->scope.launch { presenter.mutate(action) }
        LibraryAction.LoadMore->scope.launch { presenter.loadMore() }
        LibraryAction.Retry->scope.launch { presenter.reload() }
        else->onHostAction(action)
    } },modifier=modifier,onQueryChange={query,sort,filter->queryJob?.cancel();queryJob=scope.launch { presenter.refresh(query,sort,filter) }})
}
