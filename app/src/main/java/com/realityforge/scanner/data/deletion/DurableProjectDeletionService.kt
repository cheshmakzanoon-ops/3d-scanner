package com.realityforge.scanner.data.deletion

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.operations.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Owns the deletion reservation. The repository facade must not acquire a second nested lease. */
class DurableProjectDeletionService(
    private val rawDao: ScanDao,
    private val persistence: RoomDeletionPersistence,
    private val coordinator: ProjectLeaseCoordinator,
    private val paths: ProjectPathPolicy,
) : ProjectDeletionService {
    private val service = DeletionService(persistence, ProjectDeletionLeases(coordinator), PrivateFileCleaner(paths.root.toPath()))

    override suspend fun delete(projectId: String) {
        val existing = persistence.pendingProject(projectId)
        if (existing != null) {
            finish(service.retry(existing.operationId))
            if (existing.scope is DeletionScope.Project) return
        }
        val initial = run {
            val project = rawDao.project(projectId) ?: return
            val now = System.currentTimeMillis()
            service.confirmAndRun(DeletionIntent(UUID.randomUUID().toString(), DeletionScope.Project(projectId),
                project.revision, now).confirm(now))
        }
        finish(initial)
    }

    suspend fun deletePhoto(projectId: String, photoId: String, expectedRevision: Long,
        expectedRemovalRequestId: String? = null, expectedOriginalSha256: String? = null) = withContext(Dispatchers.IO) {
        persistence.pendingProject(projectId)?.let { pending ->
            require(pending.scope == DeletionScope.Photo(projectId, photoId) && pending.expectedRevision == expectedRevision &&
                (expectedRemovalRequestId == null || expectedRemovalRequestId == pending.expectedRemovalRequestId) &&
                (expectedOriginalSha256 == null || expectedOriginalSha256 == pending.expectedOriginalSha256)) {
                "A different deletion is pending. Finish it before removing this photograph."
            }
            finish(service.retry(pending.operationId))
            return@withContext
        }
        val operationId = UUID.randomUUID().toString()
        val lease = coordinator.acquire(ProjectOperationRequest(projectId, operationId, ProjectOperationKind.MUTATE_INPUTS,
            expectedRevision))
        try {
            val project = requireNotNull(rawDao.project(projectId)) { "Project no longer exists." }
            require(project.revision == expectedRevision && project.state != "DELETING" && project.state != "DELETING_PHOTO")
            val photo = requireNotNull(rawDao.photos(projectId).singleOrNull { it.id == photoId }) { "Photograph no longer exists." }
            require(expectedOriginalSha256 == null || expectedOriginalSha256 == photo.sha256) { "Original identity changed." }
            val file = paths.photoFile(photo)
            require(file.isFile && file.length() == photo.bytes && photo.bytes in 1..150L * 1024 * 1024)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0)
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == photo.sha256) {
                "Original photograph failed integrity verification; no file was removed."
            }
            val now = System.currentTimeMillis()
            persistence.stageConfirmed(DeletionIntent(operationId, DeletionScope.Photo(projectId, photoId), expectedRevision,
                now, photo.sha256, expectedRemovalRequestId).confirm(now))
        } finally { lease.close() }
        finish(service.retry(operationId))
    }

    suspend fun recoverPending(): List<DeletionRunResult> = service.recoverPending()

    private suspend fun finish(initial: DeletionRunResult) {
        var outcome = initial
        repeat(1024) {
            coroutineContext.ensureActive()
            when (val result = outcome) {
                is DeletionRunResult.Completed -> return
                is DeletionRunResult.MoreWork -> { yield(); outcome = service.retry(result.deletion.operationId) }
                is DeletionRunResult.Waiting -> error("Deletion is waiting for an active scan operation. Retry when it stops.")
                is DeletionRunResult.RetryableFailure -> error(result.deletion.failure?.detail ?: "Local cleanup needs a retry.")
                else -> error("Deletion changed concurrently. Refresh the project and retry cleanup.")
            }
        }
        error("Large deletion is still pending. Retry cleanup to continue safely.")
    }
}

private class ProjectDeletionLeases(private val coordinator: ProjectLeaseCoordinator) : DeletionLeaseCoordinator {
    private val held = ConcurrentHashMap<String, ProjectLease>()
    override suspend fun acquire(operationId: String, scope: DeletionScope): DeletionLeaseResult = try {
        val lease = coordinator.acquire(ProjectOperationRequest(scope.projectId, operationId, ProjectOperationKind.DELETE_PROJECT))
        val token = UUID.randomUUID().toString()
        held[token] = lease
        DeletionLeaseResult.Acquired(token)
    } catch (_: ProjectOperationConflictException) {
        DeletionLeaseResult.Blocked(setOf(DeletionBlocker.ACTIVE_RECONSTRUCTION))
    }
    override suspend fun release(operationId: String, leaseId: String) { held.remove(leaseId)?.close() }
}
