package com.realityforge.scanner.data.operations

class ProjectLeaseCoordinator(private val store: FileProjectLeaseStore) {
    fun acquire(request: ProjectOperationRequest): ProjectLease = store.acquire(request)

    fun activeCount(projectId: String): Int = store.activeCount(projectId)

    companion object {
        fun shared(paths: ProjectPathPolicy) = ProjectLeaseCoordinator(FileProjectLeaseStore.shared(paths))
    }
}
