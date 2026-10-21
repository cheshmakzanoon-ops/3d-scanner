package com.realityforge.scanner.data.operations

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface ProjectLease : Closeable {
    val request: ProjectOperationRequest
    val acquiredAtEpochMillis: Long
}

/**
 * Process-safe project reservations.
 *
 * One OS file lock is held per active project. Shared reservations are multiplexed inside
 * this process; other processes are conservatively excluded, which is safer than exposing a
 * partially changing project. The operating system releases the lock on process death. The
 * small on-disk record is diagnostic only and is replaced when a later process acquires it.
 */
class FileProjectLeaseStore(
    private val paths: ProjectPathPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeMetadataWrite: () -> Unit = {},
) : Closeable {
    private data class HeldProject(
        val randomAccess: RandomAccessFile,
        val channel: FileChannel,
        val lock: FileLock,
        val requests: LinkedHashMap<String, LeaseRecord>,
    )

    private data class LeaseRecord(
        val request: ProjectOperationRequest,
        val acquiredAt: Long,
    )

    private val monitor = Any()
    private val held = linkedMapOf<String, HeldProject>()
    private var closed = false

    fun acquire(request: ProjectOperationRequest): ProjectLease = synchronized(monitor) {
        check(!closed) { "Project reservation store is closed." }
        val active = held[request.projectId]
        if (active != null) {
            if (active.requests.containsKey(request.operationId)) {
                throw conflict(request, "Operation ${request.operationId} already owns a reservation.")
            }
            val compatible = request.kind.accessMode == ProjectAccessMode.SHARED &&
                active.requests.values.all { it.request.kind.accessMode == ProjectAccessMode.SHARED }
            if (!compatible) {
                throw conflict(request, activeSummary(active))
            }
            val acquiredAt = clock()
            active.requests[request.operationId] = LeaseRecord(request, acquiredAt)
            try {
                writeMetadata(active.channel, request.projectId, active.requests.values, releasedAt = null)
            } catch (failure: Throwable) {
                // No handle was returned, so nobody else can release this reservation.
                // Keep existing readers live, but never retain a failed reader as an owner.
                active.requests.remove(request.operationId)
                runCatching { writeMetadata(active.channel, request.projectId, active.requests.values, releasedAt = null) }
                throw failure
            }
            return@synchronized LeaseHandle(request, acquiredAt) { release(request) }
        }

        val lockFile = paths.leaseFile(request.projectId)
        val randomAccess = RandomAccessFile(lockFile, "rw")
        val channel = randomAccess.channel
        val fileLock = try {
            channel.tryLock()
        } catch (overlap: OverlappingFileLockException) {
            null
        } catch (failure: Exception) {
            runCatching { channel.close() }
            runCatching { randomAccess.close() }
            throw ProjectAccessException("Cannot reserve project ${request.projectId}.", failure)
        }
        if (fileLock == null) {
            val owner = runCatching { lockFile.readText(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty() }
                .getOrDefault("")
            runCatching { channel.close() }
            runCatching { randomAccess.close() }
            throw conflict(request, owner.ifBlank { "Another process owns this project reservation." })
        }

        try {
            val acquiredAt = clock()
            val requests = linkedMapOf(request.operationId to LeaseRecord(request, acquiredAt))
            val project = HeldProject(randomAccess, channel, fileLock, requests)
            held[request.projectId] = project
            writeMetadata(channel, request.projectId, requests.values, releasedAt = null)
            LeaseHandle(request, acquiredAt) { release(request) }
        } catch (failure: Throwable) {
            held.remove(request.projectId)
            runCatching { fileLock.release() }
            runCatching { channel.close() }
            runCatching { randomAccess.close() }
            throw failure
        }
    }

    fun activeCount(projectId: String): Int = synchronized(monitor) {
        held[projectId]?.requests?.size ?: 0
    }

    private fun release(request: ProjectOperationRequest) = synchronized(monitor) {
        val project = held[request.projectId] ?: return@synchronized
        if (project.requests.remove(request.operationId) == null) return@synchronized
        if (project.requests.isNotEmpty()) {
            writeMetadata(project.channel, request.projectId, project.requests.values, releasedAt = null)
            return@synchronized
        }
        held.remove(request.projectId)
        runCatching { writeMetadata(project.channel, request.projectId, emptyList(), releasedAt = clock()) }
        runCatching { project.lock.release() }
        runCatching { project.channel.close() }
        runCatching { project.randomAccess.close() }
    }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        val entries = held.toMap()
        held.clear()
        for ((projectId, project) in entries) {
            runCatching { writeMetadata(project.channel, projectId, emptyList(), releasedAt = clock()) }
            runCatching { project.lock.release() }
            runCatching { project.channel.close() }
            runCatching { project.randomAccess.close() }
        }
        unregisterShared(this)
    }

    private fun writeMetadata(
        channel: FileChannel,
        projectId: String,
        records: Collection<LeaseRecord>,
        releasedAt: Long?,
    ) {
        val text = buildString {
            append("project=").append(projectId).append('\n')
            append("schema=1\n")
            if (releasedAt != null) append("releasedAt=").append(releasedAt).append('\n')
            records.sortedBy { it.request.operationId }.forEachIndexed { index, record ->
                val prefix = "lease.$index."
                append(prefix).append("operation=").append(record.request.operationId).append('\n')
                append(prefix).append("kind=").append(record.request.kind.name).append('\n')
                append(prefix).append("mode=").append(record.request.kind.accessMode.name).append('\n')
                append(prefix).append("acquiredAt=").append(record.acquiredAt).append('\n')
                record.request.expectedRevision?.let {
                    append(prefix).append("expectedRevision=").append(it).append('\n')
                }
                record.request.job?.let {
                    append(prefix).append("job=").append(it.jobId).append('\n')
                    append(prefix).append("attempt=").append(it.attemptToken).append('\n')
                }
            }
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        beforeMetadataWrite()
        channel.truncate(0)
        channel.position(0)
        var buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }

    private fun activeSummary(project: HeldProject): String = project.requests.values
        .joinToString(prefix = "Conflicts with ", separator = ", ") {
            "${it.request.kind.name.lowercase()} operation ${it.request.operationId}"
        }

    private fun conflict(request: ProjectOperationRequest, detail: String) =
        ProjectOperationConflictException(
            projectId = request.projectId,
            requestedKind = request.kind,
            message = "Project ${request.projectId} is busy: $detail",
        )

    private class LeaseHandle(
        override val request: ProjectOperationRequest,
        override val acquiredAtEpochMillis: Long,
        private val release: () -> Unit,
    ) : ProjectLease {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    companion object {
        private val shared = ConcurrentHashMap<String, FileProjectLeaseStore>()

        fun shared(paths: ProjectPathPolicy): FileProjectLeaseStore {
            val key = paths.root.canonicalPath
            return shared.compute(key) { _, existing ->
                existing ?: FileProjectLeaseStore(paths)
            }!!
        }

        private fun unregisterShared(store: FileProjectLeaseStore) {
            shared.entries.removeIf { it.value === store }
        }
    }
}
