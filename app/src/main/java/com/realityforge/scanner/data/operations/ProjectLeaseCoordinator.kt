package com.realityforge.scanner.data.operations

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Nested operations borrow the exact structured owner, never another coroutine's busy project. */
class ProjectLeaseCoordinator(private val store: FileProjectLeaseStore) {
    private data class Scope(val lease: ProjectLease, val active: AtomicBoolean = AtomicBoolean(true))
    private val current = ThreadLocal<Scope?>()

    fun acquire(request: ProjectOperationRequest): ProjectLease {
        val owner = current.get()
        if (owner != null && owner.active.get() && owner.lease.request.projectId == request.projectId &&
            permits(owner.lease.request, request)) {
            return object : ProjectLease {
                override val request = request
                override val acquiredAtEpochMillis = owner.lease.acquiredAtEpochMillis
                override fun close() = Unit
            }
        }
        return store.acquire(request)
    }

    suspend fun <T> within(lease: ProjectLease, block: suspend () -> T): T {
        val scope = Scope(lease)
        return try { withContext(current.asContextElement(scope)) { block() } }
        finally { scope.active.set(false) }
    }

    fun activeCount(projectId: String): Int = store.activeCount(projectId)

    private fun permits(owner: ProjectOperationRequest, child: ProjectOperationRequest): Boolean {
        if (owner.kind.accessMode != ProjectAccessMode.EXCLUSIVE) return child.kind.accessMode == ProjectAccessMode.SHARED
        if (child.job != null && child.job != owner.job) return false
        return when (owner.kind) {
            ProjectOperationKind.RECONSTRUCT -> child.kind in setOf(
                ProjectOperationKind.READ_INPUTS, ProjectOperationKind.READ_CURRENT_RESULT,
                ProjectOperationKind.ATOMIC_PROJECT_WRITE, ProjectOperationKind.LEGACY_PROCESS_STATE,
                ProjectOperationKind.LEGACY_RESULT_PUBLICATION, ProjectOperationKind.PUBLISH_RESULT)
            ProjectOperationKind.IMPORT_PHOTO, ProjectOperationKind.CAPTURE_PHOTO -> child.kind in setOf(
                ProjectOperationKind.READ_INPUTS, ProjectOperationKind.IMPORT_PHOTO,
                ProjectOperationKind.CAPTURE_PHOTO, ProjectOperationKind.ATOMIC_PROJECT_WRITE)
            ProjectOperationKind.CLEANUP -> child.kind == ProjectOperationKind.READ_INPUTS || child.kind == ProjectOperationKind.ATOMIC_PROJECT_WRITE
            else -> child.kind.accessMode == ProjectAccessMode.SHARED
        }
    }

    companion object {
        fun shared(paths: ProjectPathPolicy) = ProjectLeaseCoordinator(FileProjectLeaseStore.shared(paths))
    }
}
