package com.realityforge.scanner.data.cachegc

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Stable cache-garbage-collection contract consumed by artifact provenance,
 * publication, durable jobs, storage budgets, and storage-management UI.
 *
 * The collector never infers ownership from a filename. Every deletable path
 * must be declared by a bounded [CacheArtifactManifest]. Unlisted files are
 * inventoried as [CacheArtifactCategory.UNKNOWN] and preserved.
 */
enum class CacheArtifactCategory {
    ORIGINAL,
    FEATURE_CACHE,
    DEPTH_CHECKPOINT,
    INTERMEDIATE,
    RESULT,
    TEMPORARY,
    UNKNOWN,
}

enum class CacheArtifactState {
    CURRENT,
    REUSABLE,
    STALE,
    OBSOLETE,
    ABANDONED,
    UNKNOWN,
}

enum class CacheProtectionReason {
    ORIGINAL_INPUT,
    ACTIVE_JOB,
    CURRENT_RESULT,
    RETAINED_RESULT,
    ACTIVE_PUBLICATION,
    USER_PINNED,
}

data class CacheArtifactDescriptor(
    val relativePath: String,
    val category: CacheArtifactCategory,
    val state: CacheArtifactState,
    val identity: String? = null,
    val revision: Long? = null,
    val expectedBytes: Long? = null,
    val expectedSha256: String? = null,
    val preserve: Boolean = false,
) {
    init {
        CachePathPolicy.requireCanonical(relativePath)
        require(category != CacheArtifactCategory.UNKNOWN) {
            "Manifest entries must use a concrete artifact category."
        }
        require(identity == null || (identity.isNotBlank() && identity.length <= MAX_IDENTITY_LENGTH)) {
            "Artifact identity is invalid."
        }
        require(revision == null || revision >= 0L) { "Artifact revision cannot be negative." }
        require(expectedBytes == null || expectedBytes >= 0L) { "Expected byte count cannot be negative." }
        require(expectedSha256 == null || SHA256.matches(expectedSha256)) {
            "Expected SHA-256 must be 64 lowercase hexadecimal characters."
        }
    }

    companion object {
        private const val MAX_IDENTITY_LENGTH = 256
        internal val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class CacheArtifactManifest(
    val schemaVersion: Int,
    val manifestId: String,
    val projectId: String,
    val projectRevision: Long,
    val engineVersion: String,
    val artifacts: List<CacheArtifactDescriptor>,
) {
    init {
        require(schemaVersion in 1..MAX_SUPPORTED_SCHEMA) { "Unsupported cache manifest schema." }
        require(manifestId.matches(MANIFEST_ID)) { "Invalid manifest identity." }
        require(projectId.isNotBlank() && projectId.length <= 128) { "Invalid project identity." }
        require(projectRevision >= 0L) { "Project revision cannot be negative." }
        require(engineVersion.isNotBlank() && engineVersion.length <= 128) { "Invalid engine version." }
        require(artifacts.size <= MAX_ARTIFACTS) { "Cache manifest exceeds the artifact limit." }
        require(artifacts.map { it.relativePath }.toSet().size == artifacts.size) {
            "Cache manifest contains duplicate paths."
        }
    }

    companion object {
        const val MAX_SUPPORTED_SCHEMA = 1
        const val MAX_ARTIFACTS = 100_000
        private val MANIFEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

/**
 * Immutable references captured from the durable job ledger and publication
 * store. A generation change invalidates a cleanup plan and requires a new
 * inventory before any more files are removed.
 */
data class CacheProtectionSnapshot(
    val projectId: String,
    val generation: Long,
    val activeOperationIds: Set<String> = emptySet(),
    val exactPaths: Map<String, Set<CacheProtectionReason>> = emptyMap(),
    val prefixPaths: Map<String, Set<CacheProtectionReason>> = emptyMap(),
) {
    init {
        require(projectId.isNotBlank() && projectId.length <= 128) { "Invalid project identity." }
        require(generation >= 0L) { "Reference generation cannot be negative." }
        require(activeOperationIds.size <= 10_000) { "Too many active operations." }
        activeOperationIds.forEach {
            require(it.isNotBlank() && it.length <= 128) { "Invalid operation identity." }
        }
        require(exactPaths.size + prefixPaths.size <= 100_000) { "Reference snapshot exceeds the path limit." }
        exactPaths.forEach { (path, reasons) ->
            CachePathPolicy.requireCanonical(path)
            require(reasons.isNotEmpty()) { "A protected path needs at least one reason." }
        }
        prefixPaths.forEach { (path, reasons) ->
            CachePathPolicy.requireCanonical(path)
            require(reasons.isNotEmpty()) { "A protected prefix needs at least one reason." }
        }
    }

    fun reasonsFor(relativePath: String): Set<CacheProtectionReason> {
        CachePathPolicy.requireCanonical(relativePath)
        val output = LinkedHashSet<CacheProtectionReason>()
        exactPaths[relativePath]?.let(output::addAll)
        var cursor = relativePath
        while (true) {
            prefixPaths[cursor]?.let(output::addAll)
            val separator = cursor.lastIndexOf('/')
            if (separator < 0) break
            cursor = cursor.substring(0, separator)
        }
        return output
    }

    fun protects(relativePath: String): Boolean = reasonsFor(relativePath).isNotEmpty()
}

/**
 * Project-scoped exclusion supplied by Agent 006/007. Implementations must
 * prevent new conflicting writes/references while the exclusion is held.
 * The collector still re-reads the snapshot before every deletion and aborts
 * when its generation changes.
 */
interface CacheCleanupExclusion : AutoCloseable {
    val projectId: String
    fun snapshot(): CacheProtectionSnapshot
    override fun close()
}

fun interface CacheCleanupCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NEVER = CacheCleanupCancellation { false }
    }
}

internal object CachePathPolicy {
    const val MAX_PATH_LENGTH = 4096
    private const val MAX_SEGMENT_LENGTH = 255

    fun canonical(raw: String): String {
        require(raw.isNotBlank()) { "Artifact path is blank." }
        require(raw.length <= MAX_PATH_LENGTH) { "Artifact path is too long." }
        require(!raw.startsWith('/')) { "Artifact path must be relative." }
        require('\\' !in raw && '\u0000' !in raw) { "Artifact path contains an unsafe character." }
        val segments = raw.split('/')
        require(segments.isNotEmpty() && segments.all {
            it.isNotEmpty() && it != "." && it != ".." && it.length <= MAX_SEGMENT_LENGTH
        }) { "Artifact path contains an unsafe segment." }
        return segments.joinToString("/")
    }

    fun requireCanonical(raw: String) {
        require(canonical(raw) == raw) { "Artifact path is not canonical." }
    }

    fun fromRoot(root: Path, child: Path): String {
        val relative = root.toAbsolutePath().normalize().relativize(child.toAbsolutePath().normalize())
        val joined = relative.iterator().asSequence().joinToString("/") { it.toString() }
        return canonical(joined)
    }

    fun resolve(root: Path, relativePath: String): Path {
        val canonical = canonical(relativePath)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(canonical).normalize()
        require(resolved.startsWith(normalizedRoot) && resolved != normalizedRoot) {
            "Artifact path escapes the project root."
        }
        return resolved
    }
}

internal object CacheHashes {
    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun rootIdentity(root: Path): String = sha256(root.toAbsolutePath().normalize().toString())
}
