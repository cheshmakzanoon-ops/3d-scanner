package com.realityforge.scanner.engine.artifacts

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** Optional private intermediates. A checksum proves bytes, never accepted sparse geometry.
 * The caller supplies its canonical private project directory and validates the039 payload.
 * A false publication may have completed its atomic rename before a directory-fsync failure.
 */
class SparseFrontierCheckpointStore(
    privateProjectDirectory: File,
    private val byteBudget: Long = MAX_CACHE_BYTES,
) {
    private val project = privateProjectDirectory.toPath().toAbsolutePath().normalize()
    private val directory = project.resolve(DIRECTORY_NAME)

    init { require(byteBudget in (HEADER_BYTES + 1L)..MAX_CACHE_BYTES) }

    fun load(stageSha256: String, cancelled: () -> Boolean): ByteArray? = optional(null, cancelled) {
        checkpoint(cancelled)
        if (!identity(stageSha256) || !prepareDirectory(create = false)) return@optional null
        val path = directory.resolve("$stageSha256.frontier")
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return@optional null
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            if (size !in (HEADER_BYTES + 1L)..minOf(MAX_ENTRY_BYTES, byteBudget)) return@optional null
            val header = ByteBuffer.allocate(HEADER_BYTES)
            readFully(input, header, cancelled)
            header.flip()
            val magic = ByteArray(MAGIC.size).also { header.get(it) }
            if (!magic.contentEquals(MAGIC) || header.int != VERSION) return@optional null
            val expectedIdentity = stageSha256.toByteArray(Charsets.US_ASCII)
            val actualIdentity = ByteArray(64).also { header.get(it) }
            if (!actualIdentity.contentEquals(expectedIdentity)) return@optional null
            val length = header.int
            if (length !in 1..MAX_PAYLOAD_BYTES || size != HEADER_BYTES.toLong() + length) return@optional null
            val expectedDigest = ByteArray(32).also { header.get(it) }
            // Only a validated length/size/identity can authorize this bounded allocation.
            val payload = ByteArray(length)
            val digest = MessageDigest.getInstance("SHA-256")
            var position = 0
            while (position < length) {
                checkpoint(cancelled)
                val count = minOf(BUFFER_BYTES, length - position)
                readFully(input, ByteBuffer.wrap(payload, position, count), cancelled)
                digest.update(payload, position, count)
                position += count
            }
            checkpoint(cancelled)
            if (input.size() != size || !MessageDigest.isEqual(expectedDigest, digest.digest())) null else payload
        }
    }

    fun publish(stageSha256: String, payload: ByteArray, cancelled: () -> Boolean): Boolean = optional(false, cancelled) {
        checkpoint(cancelled)
        if (!identity(stageSha256) || payload.size !in 1..MAX_PAYLOAD_BYTES) return@optional false
        val incoming = HEADER_BYTES.toLong() + payload.size
        if (incoming > byteBudget || !prepareDirectory(create = true)) return@optional false
        val lockPath = directory.resolve(LOCK_NAME)
        if (Files.isSymbolicLink(lockPath)) return@optional false
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { lockFile ->
            val lease = acquire(lockFile, cancelled) ?: return@optional false
            lease.use {
                forceDirectory(directory)
                val target = directory.resolve("$stageSha256.frontier")
                if (Files.isSymbolicLink(target)) return@optional false
                val entries = inventory(cancelled) ?: return@optional false
                for (entry in entries) {
                    if (ownedTemporary(entry.fileName.toString()) && !Files.isSymbolicLink(entry) && Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                        checkpoint(cancelled)
                        Files.delete(entry)
                    }
                }
                // An oversized exact-owned record can never be a valid envelope. Bound
                // accounting before summing sizes; preserve unknown names and symlinks.
                for (entry in entries) {
                    if (ownedRecord(entry.fileName.toString()) && Files.isRegularFile(entry, NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry) && Files.size(entry) > MAX_ENTRY_BYTES) {
                        checkpoint(cancelled)
                        Files.delete(entry)
                    }
                }
                // Account both retained records and the in-progress temporary. Never remove
                // the previous target merely to admit its replacement.
                val records = entries.filter { ownedRecord(it.fileName.toString()) }
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .map { Record(it, Files.size(it), Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis()) }
                var total = records.sumOf { it.bytes }
                var retained = records.size
                val replaces = records.any { it.path == target }
                val candidates = records.filter { it.path != target }.sortedWith(compareBy({ it.modified }, { it.path.toString() }))
                for (record in candidates) {
                    if (total <= byteBudget - incoming && retained + (if (replaces) 0 else 1) <= MAX_RECORDS) break
                    checkpoint(cancelled)
                    // A last symlink/type check ensures cache eviction never traverses links.
                    if (Files.isSymbolicLink(record.path) || !Files.isRegularFile(record.path, NOFOLLOW_LINKS)) return@optional false
                    Files.delete(record.path)
                    total -= record.bytes
                    retained--
                }
                if (total > byteBudget - incoming || retained + (if (replaces) 0 else 1) > MAX_RECORDS) return@optional false
                val temporary = directory.resolve("$stageSha256.frontier.partial-${UUID.randomUUID().toString().replace("-", "")}")
                try {
                    FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(VERSION)
                            .put(stageSha256.toByteArray(Charsets.US_ASCII)).putInt(payload.size).put(ByteArray(32))
                        header.flip()
                        writeFully(output, header, cancelled)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(minOf(BUFFER_BYTES, payload.size))
                        var position = 0
                        while (position < payload.size) {
                            checkpoint(cancelled)
                            val count = minOf(buffer.size, payload.size - position)
                            // Hash exactly the copied bytes written, even if a caller incorrectly
                            // mutates its input concurrently; no second payload-sized allocation.
                            payload.copyInto(buffer, 0, position, position + count)
                            digest.update(buffer, 0, count)
                            writeFully(output, ByteBuffer.wrap(buffer, 0, count), cancelled)
                            position += count
                        }
                        output.position(DIGEST_OFFSET.toLong())
                        writeFully(output, ByteBuffer.wrap(digest.digest()), cancelled)
                        output.force(true)
                    }
                    checkpoint(cancelled)
                    if (Files.isSymbolicLink(target)) return@optional false
                    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                    forceDirectory(directory)
                    checkpoint(cancelled)
                    true
                } finally {
                    try { Files.deleteIfExists(temporary) } catch (_: IOException) { } catch (_: SecurityException) { }
                }
            }
        }
    }

    private fun prepareDirectory(create: Boolean): Boolean {
        if (project.toFile().canonicalFile.toPath() != project || !Files.isDirectory(project, NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(directory)) return false
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            if (!create) return false
            try { Files.createDirectory(directory) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        }
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toFile().canonicalFile.toPath() != directory) return false
        if (create) forceDirectory(project) // Persist the cache directory's own parent entry too.
        return true
    }

    private fun inventory(cancelled: () -> Boolean): List<Path>? {
        val paths = ArrayList<Path>()
        Files.newDirectoryStream(directory).use { stream ->
            for (path in stream) {
                checkpoint(cancelled)
                if (paths.size == MAX_DIRECTORY_ENTRIES) return null
                paths.add(path)
            }
        }
        return paths
    }

    private fun acquire(channel: FileChannel, cancelled: () -> Boolean): FileLock? {
        repeat(25) {
            checkpoint(cancelled)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock
            try { Thread.sleep(10) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Sparse checkpoint interrupted")
            }
        }
        checkpoint(cancelled)
        return null
    }

    private data class Record(val path: Path, val bytes: Long, val modified: Long)

    companion object {
        const val MAX_PAYLOAD_BYTES = 13_000_000
        const val MAX_CACHE_BYTES = 26_000_224L
        const val DIRECTORY_NAME = "sparse-frontier-checkpoints-v1"
        private const val HEADER_BYTES = 112
        private const val DIGEST_OFFSET = 80
        private const val VERSION = 1
        private const val MAX_ENTRY_BYTES = 13_000_112L
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_RECORDS = 2
        private const val MAX_DIRECTORY_ENTRIES = 1024
        private const val LOCK_NAME = ".frontier-store-v1.lock"
        private val MAGIC = byteArrayOf(82, 70, 70, 82, 78, 84, 48, 49) // RFFRNT01
        private fun identity(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        private fun ownedRecord(name: String) = name.length == 73 && name.endsWith(".frontier") && identity(name.substring(0, 64))
        private fun ownedTemporary(name: String): Boolean = name.length == 114 && name.substring(64, 82) == ".frontier.partial-" &&
            identity(name.substring(0, 64)) && name.substring(82).all { it in '0'..'9' || it in 'a'..'f' }
        private fun checkpoint(cancelled: () -> Boolean) {
            if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("Sparse checkpoint cancelled")
        }
        private fun forceDirectory(path: Path) { FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) } }
        private fun readFully(channel: FileChannel, buffer: ByteBuffer, cancelled: () -> Boolean) {
            var idle = 0
            while (buffer.hasRemaining()) {
                checkpoint(cancelled)
                val count = channel.read(buffer)
                if (count < 0) throw IOException("Truncated sparse checkpoint")
                if (count == 0 && ++idle >= 16) throw IOException("Sparse checkpoint read made no progress")
                if (count > 0) idle = 0
            }
        }
        private fun writeFully(channel: FileChannel, buffer: ByteBuffer, cancelled: () -> Boolean) {
            var idle = 0
            while (buffer.hasRemaining()) {
                checkpoint(cancelled)
                val count = channel.write(buffer)
                if (count == 0 && ++idle >= 16) throw IOException("Sparse checkpoint write made no progress")
                if (count > 0) idle = 0
            }
        }
        private inline fun <T> optional(fallback: T, noinline cancelled: () -> Boolean, work: () -> T): T = try { work() }
            catch (_: IOException) { checkpoint(cancelled); fallback }
            catch (_: SecurityException) { checkpoint(cancelled); fallback }
            catch (_: java.nio.file.DirectoryIteratorException) { checkpoint(cancelled); fallback }
            catch (_: UnsupportedOperationException) { checkpoint(cancelled); fallback }
    }
}
