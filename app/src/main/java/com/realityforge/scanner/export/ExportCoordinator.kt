package com.realityforge.scanner.export

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Validates private staged bytes before opening a provider destination. Runs on the caller's IO dispatcher. */
class ExportCoordinator(private val source: ExportSource, private val stagingRoot: File, private val maxBytes: Long = 512L * 1024 * 1024) {
    private val active = AtomicBoolean(false)
    init { require(maxBytes > 0) }
    suspend fun catalog(identity: ArtifactIdentity): List<ExportOption> {
        check(source.isCurrent(identity)) { "STALE_ARTIFACT" }
        val options = source.available(identity)
        require(options.map { it.id }.distinct().size == options.size)
        return options.filter { it.bytes in 1..maxBytes }.toList()
    }
    suspend fun execute(request: ExportRequest, destination: ExportDestination, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): ExportOutcome {
        if (!active.compareAndSet(false, true)) return ExportOutcome.Failed(request.requestId, "BUSY", false)
        var opened = false
        var target: File? = null
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive(); if (cancelled()) throw ExportCancelled(); Unit }
        try {
            checkpoint()
            check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
            require(catalog(request.artifact).contains(request.option)) { "OPTION_UNAVAILABLE" }
            require(stagingRoot.isDirectory || stagingRoot.mkdirs())
            val staged = File.createTempFile("export-", ".partial", stagingRoot)
            target = staged
            source.materialize(request, staged, checkpoint)
            checkpoint()
            require(staged.length() == request.option.bytes && staged.length() <= maxBytes) { "INVALID_SIZE" }
            source.validate(request, staged)
            val expected = digest(staged, checkpoint)
            check(request.option.contentSha256 == null || expected == request.option.contentSha256) { "ARTIFACT_BYTES_CHANGED" }
            check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
            val actual = MessageDigest.getInstance("SHA-256")
            var total = 0L
            // Even an open() failure may leave a newly created provider document.
            opened = true
            destination.open().use { out ->
                staged.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(n > 0 && total <= maxBytes - n) { "INVALID_SIZE" }
                        out.write(buffer, 0, n)
                        actual.update(buffer, 0, n)
                        total += n
                        progress(total, request.option.bytes)
                    }
                }
                checkpoint()
                out.flush()
            }
            check(total == request.option.bytes && actual.digest().hex() == expected) { "STAGED_BYTES_CHANGED" }
            checkpoint()
            check(source.isCurrent(request.artifact)) { "STALE_ARTIFACT" }
            destination.commit()
            return ExportOutcome.Completed(request.requestId, expected, total)
        } catch (_: ExportCancelled) {
            return ExportOutcome.Cancelled(request.requestId, opened && !runCatching { destination.abort() }.getOrDefault(false))
        } catch (_: kotlinx.coroutines.CancellationException) {
            return ExportOutcome.Cancelled(request.requestId, opened && !runCatching { destination.abort() }.getOrDefault(false))
        } catch (e: Exception) {
            val code = e.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "EXPORT_FAILED"
            return ExportOutcome.Failed(request.requestId, code, opened && !runCatching { destination.abort() }.getOrDefault(false))
        } finally {
            target?.delete()
            active.set(false)
        }
    }
    companion object {
        fun digest(file: File, checkpoint: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().hex()
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Offline OBJ/MTL/image file sets remain relocatable; inputs are never modified. */
object ExportPackage {
    fun write(members: Map<String, File>, target: File, maxBytes: Long, checkpoint: () -> Unit = {}) {
        val inputs = members.toMap()
        require(maxBytes > 0 && inputs.size in 1..64)
        val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
        require(inputs.keys.all { name ->
            name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) && !name.endsWith('.') &&
                name.substringBefore('.').uppercase(java.util.Locale.ROOT) !in reserved
        })
        require(inputs.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == inputs.size)
        val outputPath = target.toPath().toAbsolutePath().normalize()
        require(!Files.exists(outputPath, NOFOLLOW_LINKS)) { "PACKAGE_TARGET_EXISTS" }
        require(Files.isDirectory(outputPath.parent, NOFOLLOW_LINKS) && outputPath.parent.toRealPath() == outputPath.parent) { "UNSAFE_PACKAGE_TARGET" }
        var declaredBytes = 0L
        val sourcePaths = inputs.mapValues { (_, source) ->
            source.toPath().toAbsolutePath().normalize().also { path ->
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "UNSAFE_PACKAGE_SOURCE" }
                val size = Files.size(path)
                require(size <= maxBytes - declaredBytes) { "PACKAGE_LIMIT" }
                declaredBytes += size
            }
        }
        var count = 0L
        var ownedOutput = false
        try {
            checkpoint()
            Files.newOutputStream(outputPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS).also { ownedOutput = true }.use { file ->
                ZipOutputStream(file).use { zip ->
                    val buffer = ByteArray(65536)
                    for ((name, source) in sourcePaths.toSortedMap()) {
                        checkpoint()
                        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                        Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                            while (true) {
                                checkpoint(); val n = input.read(buffer); if (n < 0) break
                                require(count <= maxBytes - n) { "PACKAGE_LIMIT" }; count += n
                                zip.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(target.length() <= maxBytes)
            ZipFile(target).use { zip ->
                require(zip.size() == inputs.size)
                val buffer = ByteArray(65536)
                for ((name, file) in inputs) {
                    val entry = requireNotNull(zip.getEntry(name))
                    require(entry.size == file.length())
                    val hash = MessageDigest.getInstance("SHA-256")
                    val crc = java.util.zip.CRC32()
                    zip.getInputStream(entry).use { input ->
                        while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); crc.update(buffer, 0, n) }
                    }
                    require(crc.value == entry.crc && hash.digest().hex() == ExportCoordinator.digest(file, checkpoint))
                }
            }
        } catch (e: Exception) { if (ownedOutput) target.delete(); throw e }
    }
}
