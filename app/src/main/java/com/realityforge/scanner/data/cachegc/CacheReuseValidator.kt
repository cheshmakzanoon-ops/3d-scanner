package com.realityforge.scanner.data.cachegc

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

private const val DEFAULT_MAX_REUSE_BYTES = 2L * 1024L * 1024L * 1024L

data class CacheReuseRequest(
    val projectId: String,
    val manifestId: String,
    val artifact: CacheArtifactDescriptor,
) {
    init {
        require(projectId.isNotBlank() && projectId.length <= 128)
        require(manifestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(artifact.category !in setOf(
            CacheArtifactCategory.ORIGINAL,
            CacheArtifactCategory.TEMPORARY,
            CacheArtifactCategory.UNKNOWN,
        )) { "Only completed derived artifacts can be reused." }
        require(artifact.state in setOf(CacheArtifactState.CURRENT, CacheArtifactState.REUSABLE)) {
            "Only current or reusable artifacts can be validated for reuse."
        }
        require(artifact.identity != null) { "Reusable artifacts require an identity." }
        require(artifact.expectedBytes != null) { "Reusable artifacts require an expected byte count." }
        require(artifact.expectedSha256 != null) { "Reusable artifacts require an expected SHA-256." }
    }
}

enum class CacheReuseRejectionCode {
    MISSING,
    UNSAFE_PATH,
    NOT_REGULAR_FILE,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    CHANGED_DURING_VALIDATION,
    RESOURCE_LIMIT,
    CANCELLED,
    IO_FAILURE,
}

sealed class CacheReuseValidation {
    data class Valid(
        val relativePath: String,
        val identity: String,
        val bytes: Long,
        val sha256: String,
    ) : CacheReuseValidation()

    data class Rejected(
        val relativePath: String,
        val code: CacheReuseRejectionCode,
        val detail: String,
    ) : CacheReuseValidation()
}

/**
 * Verifies a reusable artifact against its owner-supplied manifest identity,
 * bounded size, and content hash. Hash validity is an integrity prerequisite;
 * it is not a claim that the geometry itself meets quality gates.
 */
class CacheReuseValidator(
    private val maxBytes: Long = DEFAULT_MAX_REUSE_BYTES,
) {
    init {
        require(maxBytes > 0L)
    }

    fun validate(
        projectRoot: Path,
        request: CacheReuseRequest,
        cancellation: CacheCleanupCancellation = CacheCleanupCancellation.NEVER,
    ): CacheReuseValidation {
        val relative = request.artifact.relativePath
        val root = projectRoot.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return rejected(relative, CacheReuseRejectionCode.UNSAFE_PATH, "Project root is not a safe directory.")
        }
        val path = try {
            resolveNoFollow(root, relative)
        } catch (_: UnsafeReusePathException) {
            return rejected(relative, CacheReuseRejectionCode.UNSAFE_PATH, "Artifact path traverses an unsafe filesystem entry.")
        }
        if (path == null) {
            return rejected(relative, CacheReuseRejectionCode.MISSING, "Artifact is missing.")
        }
        val before = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return rejected(relative, CacheReuseRejectionCode.IO_FAILURE, "Artifact metadata could not be read.")
        } catch (_: SecurityException) {
            return rejected(relative, CacheReuseRejectionCode.IO_FAILURE, "Artifact metadata access was denied.")
        }
        if (before.isSymbolicLink || !before.isRegularFile) {
            return rejected(relative, CacheReuseRejectionCode.NOT_REGULAR_FILE, "Artifact is not a regular file.")
        }
        val expectedBytes = requireNotNull(request.artifact.expectedBytes)
        if (expectedBytes > maxBytes) {
            return rejected(relative, CacheReuseRejectionCode.RESOURCE_LIMIT, "Artifact exceeds the validation byte limit.")
        }
        if (before.size() != expectedBytes) {
            return rejected(relative, CacheReuseRejectionCode.SIZE_MISMATCH, "Artifact byte count differs from the manifest.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (true) {
                    if (cancellation.isCancelled()) {
                        return rejected(relative, CacheReuseRejectionCode.CANCELLED, "Validation was cancelled.")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expectedBytes || total > maxBytes) {
                        return rejected(relative, CacheReuseRejectionCode.SIZE_MISMATCH, "Artifact grew during validation.")
                    }
                    digest.update(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            return rejected(relative, CacheReuseRejectionCode.IO_FAILURE, "Artifact bytes could not be read.")
        } catch (_: SecurityException) {
            return rejected(relative, CacheReuseRejectionCode.IO_FAILURE, "Artifact byte access was denied.")
        }
        if (total != expectedBytes) {
            return rejected(relative, CacheReuseRejectionCode.SIZE_MISMATCH, "Artifact was truncated during validation.")
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actualHash != request.artifact.expectedSha256) {
            return rejected(relative, CacheReuseRejectionCode.HASH_MISMATCH, "Artifact SHA-256 differs from the manifest.")
        }

        val after = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return rejected(relative, CacheReuseRejectionCode.CHANGED_DURING_VALIDATION, "Artifact changed during validation.")
        }
        if (!sameFileSnapshot(before, after)) {
            return rejected(relative, CacheReuseRejectionCode.CHANGED_DURING_VALIDATION, "Artifact changed during validation.")
        }
        return CacheReuseValidation.Valid(
            relativePath = relative,
            identity = requireNotNull(request.artifact.identity),
            bytes = total,
            sha256 = actualHash,
        )
    }

    private fun resolveNoFollow(root: Path, relativePath: String): Path? {
        val target = CachePathPolicy.resolve(root, relativePath)
        var cursor = root
        val segments = root.relativize(target).iterator().asSequence().toList()
        for ((index, segment) in segments.withIndex()) {
            cursor = cursor.resolve(segment)
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) return null
            val attributes = try {
                Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                throw UnsafeReusePathException()
            }
            if (attributes.isSymbolicLink) throw UnsafeReusePathException()
            if (index < segments.lastIndex && !attributes.isDirectory) throw UnsafeReusePathException()
        }
        return cursor
    }

    private fun sameFileSnapshot(first: BasicFileAttributes, second: BasicFileAttributes): Boolean {
        val firstKey = first.fileKey()?.toString()
        val secondKey = second.fileKey()?.toString()
        return second.isRegularFile &&
            first.size() == second.size() &&
            first.lastModifiedTime() == second.lastModifiedTime() &&
            (firstKey == null || secondKey == null || firstKey == secondKey)
    }

    private fun rejected(
        relativePath: String,
        code: CacheReuseRejectionCode,
        detail: String,
    ) = CacheReuseValidation.Rejected(relativePath, code, detail)

    private class UnsafeReusePathException : RuntimeException()
}
