package com.realityforge.scanner.domain

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

/** Clear synchronously on every identity boundary, even before an old read finishes cancelling.
 * Null is an observable boundary, not an event to filter out. Reads must still fence publication.
 */
internal suspend fun Flow<PresentationReadIdentity?>.observePresentationReads(
    onBoundary: (PresentationReadIdentity?) -> Unit,
    read: suspend (PresentationReadIdentity) -> Unit,
) {
    distinctUntilChanged().onEach { onBoundary(it) }.collectLatest { identity ->
        if (identity != null) read(identity)
    }
}

/** Finishes can occur on an IO dispatcher (SAF). Resolve selection on the owner's dispatcher,
 * not when the operation began; the refresh itself still checks operation/identity admission.
 */
internal fun kotlinx.coroutines.CoroutineScope.refreshPresentationAfterOperation(
    selectedId: () -> String?,
    refresh: (String) -> Unit,
): kotlinx.coroutines.Job = launch { selectedId()?.let(refresh) }
