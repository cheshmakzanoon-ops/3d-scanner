package com.realityforge.scanner.data.operations

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Own the reservation before returning across a cancellable dispatcher boundary. */
internal suspend fun <T> withAcquiredProjectLease(
    coordinator: ProjectLeaseCoordinator,
    request: ProjectOperationRequest,
    acquisitionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend (ProjectLease) -> T,
): T {
    var acquired: ProjectLease? = null
    try {
        // withContext may discard its return value when cancellation wins the dispatch
        // back to the caller. Assign inside the block so finally still owns that lease.
        withContext(acquisitionDispatcher) { acquired = coordinator.acquire(request) }
        return block(requireNotNull(acquired))
    } finally {
        acquired?.let { lease ->
            withContext(NonCancellable + Dispatchers.IO) { lease.close() }
        }
    }
}
