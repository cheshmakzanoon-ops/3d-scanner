package com.realityforge.scanner.data.cachegc

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.EnumMap

private val NO_FOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

enum class InventoryFileType {
    REGULAR_FILE,
    SYMBOLIC_LINK,
    OTHER,
}

enum class ManifestMatch {
    MATCHED,
    UNDECLARED,
    SIZE_MISMATCH,
    TYPE_MISMATCH,
}

enum class InventoryIssueCode {
    UNDECLARED_FILE_PRESERVED,
    DECLARED_FILE_MISSING,
    MANIFEST_SIZE_MISMATCH,
    SYMBOLIC_LINK_PRESERVED,
    UNSUPPORTED_FILE_TYPE_PRESERVED,
    DIRECTORY_DEPTH_LIMIT,
    FILE_COUNT_LIMIT,
    BYTE_ACCOUNTING_OVERFLOW,
    READ_FAILURE,
}

data class InventoryIssue(
    val code: InventoryIssueCode,
    val relativePath: String?,
    val detail: String,
)

data class CacheFileFingerprint(
    val bytes: Long,
    val lastModifiedMillis: Long,
    val fileKeySha256: String?,
) {
    init {
        require(bytes >= 0L)
        require(lastModifiedMillis >= 0L)
        require(fileKeySha256 == null || CacheArtifactDescriptor.SHA256.matches(fileKeySha256))
    }
}

data class CacheInventoryEntry(
    val relativePath: String,
    val type: InventoryFileType,
    val category: CacheArtifactCategory,
    val state: CacheArtifactState,
    val identity: String?,
    val revision: Long?,
    val bytes: Long,
    val lastModifiedMillis: Long,
    val expectedSha256: String?,
    val manifestMatch: ManifestMatch,
    val preserveByManifest: Boolean,
    val protections: Set<CacheProtectionReason>,
    val fingerprint: CacheFileFingerprint,
) {
    init {
        CachePathPolicy.requireCanonical(relativePath)
        require(bytes >= 0L && lastModifiedMillis >= 0L)
    }

    val isManifestDeclared: Boolean get() = manifestMatch != ManifestMatch.UNDECLARED
}

data class CacheInventory(
    val projectId: String,
    val projectRevision: Long,
    val manifestId: String,
    val rootIdentity: String,
    val referenceGeneration: Long,
    val scannedAtMillis: Long,
    val entries: List<CacheInventoryEntry>,
    val missingDeclaredPaths: List<String>,
    val issues: List<InventoryIssue>,
    val totalBytes: Long,
    val bytesByCategory: Map<CacheArtifactCategory, Long>,
) {
    init {
        require(projectId.isNotBlank())
        require(projectRevision >= 0L && referenceGeneration >= 0L && scannedAtMillis >= 0L)
        require(CacheArtifactDescriptor.SHA256.matches(rootIdentity))
        require(totalBytes >= 0L)
        require(entries.map { it.relativePath }.toSet().size == entries.size)
        missingDeclaredPaths.forEach(CachePathPolicy::requireCanonical)
    }
}

/**
 * Builds a bounded, no-follow filesystem inventory. Only manifest entries get
 * a deletable category. Unknown and non-regular entries remain visible but are
 * deliberately preserved by the retention planner.
 */
class CacheInventoryBuilder(
    private val maxFiles: Int = CacheArtifactManifest.MAX_ARTIFACTS,
    private val maxDepth: Int = 32,
) {
    init {
        require(maxFiles in 1..CacheArtifactManifest.MAX_ARTIFACTS)
        require(maxDepth in 1..128)
    }

    fun build(
        projectRoot: Path,
        manifest: CacheArtifactManifest,
        protections: CacheProtectionSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): CacheInventory {
        require(nowMillis >= 0L)
        require(manifest.projectId == protections.projectId) {
            "Manifest and reference snapshot belong to different projects."
        }
        val root = projectRoot.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root)) { "Project root cannot be a symbolic link." }
        require(Files.isDirectory(root, *NO_FOLLOW)) { "Project root is not a directory." }

        val declared = manifest.artifacts.associateBy { it.relativePath }
        val discovered = LinkedHashMap<String, CacheInventoryEntry>()
        val issues = ArrayList<InventoryIssue>()
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(root to 0)
        var stop = false
        var visitedEntries = 0

        while (pending.isNotEmpty() && !stop) {
            val (directory, depth) = pending.removeFirst()
            if (depth > maxDepth) {
                val relative = if (directory == root) null else CachePathPolicy.fromRoot(root, directory)
                issues += InventoryIssue(
                    InventoryIssueCode.DIRECTORY_DEPTH_LIMIT,
                    relative,
                    "Directory depth exceeded the configured inventory limit.",
                )
                continue
            }
            val children = try {
                Files.newDirectoryStream(directory).use { stream: DirectoryStream<Path> ->
                    // Bound enumeration itself, including directories. A single enormous folder
                    // must not allocate an unbounded list before the inventory limit is checked.
                    stream.asSequence().take(maxFiles - visitedEntries + 1)
                        .toList().sortedBy { it.fileName.toString() }
                }
            } catch (_: IOException) {
                val relative = if (directory == root) null else CachePathPolicy.fromRoot(root, directory)
                issues += InventoryIssue(
                    InventoryIssueCode.READ_FAILURE,
                    relative,
                    "Directory could not be read; no contents were considered reclaimable.",
                )
                continue
            } catch (_: SecurityException) {
                val relative = if (directory == root) null else CachePathPolicy.fromRoot(root, directory)
                issues += InventoryIssue(
                    InventoryIssueCode.READ_FAILURE,
                    relative,
                    "Directory access was denied; no contents were considered reclaimable.",
                )
                continue
            }

            for (child in children) {
                if (visitedEntries >= maxFiles) {
                    issues += InventoryIssue(InventoryIssueCode.FILE_COUNT_LIMIT, null,
                        "Inventory stopped at the configured filesystem-entry limit.")
                    stop = true
                    break
                }
                visitedEntries++
                val relative = try {
                    CachePathPolicy.fromRoot(root, child)
                } catch (_: IllegalArgumentException) {
                    issues += InventoryIssue(
                        InventoryIssueCode.READ_FAILURE,
                        null,
                        "An entry could not be represented as a safe relative path.",
                    )
                    continue
                }
                val attributes = try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, *NO_FOLLOW)
                } catch (_: IOException) {
                    issues += InventoryIssue(
                        InventoryIssueCode.READ_FAILURE,
                        relative,
                        "Entry attributes could not be read; it was preserved.",
                    )
                    continue
                } catch (_: SecurityException) {
                    issues += InventoryIssue(
                        InventoryIssueCode.READ_FAILURE,
                        relative,
                        "Entry attributes were inaccessible; it was preserved.",
                    )
                    continue
                }

                if (attributes.isDirectory && !attributes.isSymbolicLink) {
                    if (depth == maxDepth) {
                        issues += InventoryIssue(
                            InventoryIssueCode.DIRECTORY_DEPTH_LIMIT,
                            relative,
                            "Directory was not traversed because it reached the configured depth limit.",
                        )
                    } else {
                        pending.add(child to depth + 1)
                    }
                    continue
                }

                if (discovered.size >= maxFiles) {
                    issues += InventoryIssue(
                        InventoryIssueCode.FILE_COUNT_LIMIT,
                        relative,
                        "Inventory stopped at the configured file-count limit.",
                    )
                    stop = true
                    break
                }

                val declaration = declared[relative]
                val type = when {
                    attributes.isSymbolicLink -> InventoryFileType.SYMBOLIC_LINK
                    attributes.isRegularFile -> InventoryFileType.REGULAR_FILE
                    else -> InventoryFileType.OTHER
                }
                val match = when {
                    declaration == null -> ManifestMatch.UNDECLARED
                    type != InventoryFileType.REGULAR_FILE -> ManifestMatch.TYPE_MISMATCH
                    declaration.expectedBytes != null && declaration.expectedBytes != attributes.size() -> ManifestMatch.SIZE_MISMATCH
                    else -> ManifestMatch.MATCHED
                }
                val category = declaration?.category ?: CacheArtifactCategory.UNKNOWN
                val state = declaration?.state ?: CacheArtifactState.UNKNOWN
                val modified = attributes.lastModifiedTime().toMillis().coerceAtLeast(0L)
                val fileKeyHash = attributes.fileKey()?.toString()?.let(CacheHashes::sha256)
                val entry = CacheInventoryEntry(
                    relativePath = relative,
                    type = type,
                    category = category,
                    state = state,
                    identity = declaration?.identity,
                    revision = declaration?.revision,
                    bytes = attributes.size().coerceAtLeast(0L),
                    lastModifiedMillis = modified,
                    expectedSha256 = declaration?.expectedSha256,
                    manifestMatch = match,
                    preserveByManifest = declaration?.preserve == true,
                    protections = protections.reasonsFor(relative),
                    fingerprint = CacheFileFingerprint(
                        bytes = attributes.size().coerceAtLeast(0L),
                        lastModifiedMillis = modified,
                        fileKeySha256 = fileKeyHash,
                    ),
                )
                discovered[relative] = entry

                when {
                    type == InventoryFileType.SYMBOLIC_LINK -> issues += InventoryIssue(
                        InventoryIssueCode.SYMBOLIC_LINK_PRESERVED,
                        relative,
                        "Symbolic links are never followed or deleted by cache cleanup.",
                    )
                    type == InventoryFileType.OTHER -> issues += InventoryIssue(
                        InventoryIssueCode.UNSUPPORTED_FILE_TYPE_PRESERVED,
                        relative,
                        "Non-regular filesystem entries are never deleted by cache cleanup.",
                    )
                    declaration == null -> issues += InventoryIssue(
                        InventoryIssueCode.UNDECLARED_FILE_PRESERVED,
                        relative,
                        "File is absent from the artifact manifest and was preserved.",
                    )
                    match == ManifestMatch.SIZE_MISMATCH -> issues += InventoryIssue(
                        InventoryIssueCode.MANIFEST_SIZE_MISMATCH,
                        relative,
                        "File size differs from the manifest; cleanup requires a fresh manifest.",
                    )
                    match == ManifestMatch.TYPE_MISMATCH -> issues += InventoryIssue(
                        InventoryIssueCode.UNSUPPORTED_FILE_TYPE_PRESERVED,
                        relative,
                        "Declared artifact is not a regular file and was preserved.",
                    )
                }
            }
        }

        val missing = declared.keys.minus(discovered.keys).sorted()
        missing.forEach { path ->
            issues += InventoryIssue(
                InventoryIssueCode.DECLARED_FILE_MISSING,
                path,
                "Manifest entry is absent; inventory records the discrepancy without creating it.",
            )
        }

        var totalBytes = 0L
        var overflowed = false
        val byCategory = EnumMap<CacheArtifactCategory, Long>(CacheArtifactCategory::class.java)
        for (entry in discovered.values) {
            val previousTotal = totalBytes
            totalBytes = saturatingAdd(totalBytes, entry.bytes)
            if (totalBytes == Long.MAX_VALUE && previousTotal != Long.MAX_VALUE && entry.bytes > Long.MAX_VALUE - previousTotal) {
                overflowed = true
            }
            val previous = byCategory[entry.category] ?: 0L
            byCategory[entry.category] = saturatingAdd(previous, entry.bytes)
        }
        if (overflowed) {
            issues += InventoryIssue(
                InventoryIssueCode.BYTE_ACCOUNTING_OVERFLOW,
                null,
                "Inventory byte accounting saturated at Long.MAX_VALUE.",
            )
        }

        return CacheInventory(
            projectId = manifest.projectId,
            projectRevision = manifest.projectRevision,
            manifestId = manifest.manifestId,
            rootIdentity = CacheHashes.rootIdentity(root),
            referenceGeneration = protections.generation,
            scannedAtMillis = nowMillis,
            entries = discovered.values.sortedBy { it.relativePath },
            missingDeclaredPaths = missing,
            issues = issues.sortedWith(compareBy({ it.relativePath ?: "" }, { it.code.name })),
            totalBytes = totalBytes,
            bytesByCategory = CacheArtifactCategory.entries.associateWith { byCategory[it] ?: 0L },
        )
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
}
