package com.realityforge.scanner.data.cachegc

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumMap

private const val JOURNAL_MAGIC = 0x52464347 // RFCG
private const val JOURNAL_VERSION = 1

data class CleanupJournal(
    val planId: String,
    val completedPaths: Set<String>,
) {
    init {
        require(CacheArtifactDescriptor.SHA256.matches(planId))
        require(completedPaths.size <= CacheArtifactManifest.MAX_ARTIFACTS)
        completedPaths.forEach(CachePathPolicy::requireCanonical)
    }
}

interface CleanupJournalStore {
    /** Null for in-memory implementations. A filesystem store must be outside the cleaned project root. */
    val rootDirectory: Path?
    fun load(planId: String): CleanupJournal?
    fun save(journal: CleanupJournal)
    fun clear(planId: String)
}

class FileCleanupJournalStore(
    override val rootDirectory: Path,
) : CleanupJournalStore {
    init {
        require(!Files.exists(rootDirectory) || !Files.isSymbolicLink(rootDirectory)) {
            "Cleanup journal root cannot be a symbolic link."
        }
    }

    override fun load(planId: String): CleanupJournal? {
        val file = journalFile(planId)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(file)) { "Cleanup journal cannot be a symbolic link." }
        DataInputStream(BufferedInputStream(FileInputStream(file.toFile()))).use { input ->
            require(input.readInt() == JOURNAL_MAGIC) { "Invalid cleanup journal header." }
            require(input.readInt() == JOURNAL_VERSION) { "Unsupported cleanup journal version." }
            val storedPlan = input.readUTF()
            require(storedPlan == planId) { "Cleanup journal belongs to a different plan." }
            val count = input.readInt()
            require(count in 0..CacheArtifactManifest.MAX_ARTIFACTS) { "Cleanup journal entry count is invalid." }
            val completed = LinkedHashSet<String>(count)
            repeat(count) {
                val path = input.readUTF()
                CachePathPolicy.requireCanonical(path)
                require(completed.add(path)) { "Cleanup journal contains duplicate paths." }
            }
            require(input.read() == -1) { "Cleanup journal contains trailing data." }
            return CleanupJournal(planId, completed)
        }
    }

    override fun save(journal: CleanupJournal) {
        val directory = prepareRoot()
        val target = journalFile(journal.planId)
        val temporary = directory.resolve("${journal.planId}.journal.partial")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(JOURNAL_MAGIC)
                output.writeInt(JOURNAL_VERSION)
                output.writeUTF(journal.planId)
                output.writeInt(journal.completedPaths.size)
                journal.completedPaths.sorted().forEach(output::writeUTF)
                output.flush()
                stream.fd.sync()
            }
            moveAtomically(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun clear(planId: String) {
        Files.deleteIfExists(journalFile(planId))
    }

    private fun prepareRoot(): Path {
        val normalized = rootDirectory.toAbsolutePath().normalize()
        Files.createDirectories(normalized)
        require(!Files.isSymbolicLink(normalized) && Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "Cleanup journal root is not a safe directory."
        }
        return normalized
    }

    private fun journalFile(planId: String): Path {
        require(CacheArtifactDescriptor.SHA256.matches(planId)) { "Invalid cleanup plan identity." }
        return rootDirectory.toAbsolutePath().normalize().resolve("$planId.journal")
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class InMemoryCleanupJournalStore : CleanupJournalStore {
    override val rootDirectory: Path? = null
    private val journals = LinkedHashMap<String, CleanupJournal>()

    override fun load(planId: String): CleanupJournal? = journals[planId]
    override fun save(journal: CleanupJournal) {
        journals[journal.planId] = journal.copy(completedPaths = LinkedHashSet(journal.completedPaths))
    }
    override fun clear(planId: String) {
        journals.remove(planId)
    }
}

enum class CleanupExecutionState {
    NOTHING_TO_DO,
    COMPLETED,
    CANCELLED,
    PARTIAL_FAILURE,
    REPLAN_REQUIRED,
}

enum class CleanupSkipCode {
    REFERENCE_GENERATION_CHANGED,
    NEW_PROTECTION,
    PATH_CHANGED,
    PATH_UNSAFE,
    CONTENT_HASH_CHANGED,
}

data class CleanupSkip(
    val relativePath: String,
    val code: CleanupSkipCode,
    val detail: String,
)

data class CleanupFailure(
    val relativePath: String?,
    val operation: String,
    val detail: String,
)

data class CleanupExecutionResult(
    val planId: String,
    val state: CleanupExecutionState,
    val deletedPaths: List<String>,
    val reconciledMissingPaths: List<String>,
    val skipped: List<CleanupSkip>,
    val failures: List<CleanupFailure>,
    val deletedBytes: Long,
    val deletedBytesByCategory: Map<CacheArtifactCategory, Long>,
) {
    init {
        require(CacheArtifactDescriptor.SHA256.matches(planId))
        require(deletedBytes >= 0L)
    }
}

/**
 * Resumable, reference-aware executor. It accepts only plans generated from a
 * current typed inventory and requires a project exclusion for execution.
 */
class CacheCollector(
    private val journalStore: CleanupJournalStore,
) {
    fun execute(
        projectRoot: Path,
        plan: CleanupPlan,
        exclusion: CacheCleanupExclusion,
        cancellation: CacheCleanupCancellation = CacheCleanupCancellation.NEVER,
    ): CleanupExecutionResult {
        val root = projectRoot.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Project root is not a safe directory."
        }
        require(CacheHashes.rootIdentity(root) == plan.rootIdentity) {
            "Cleanup plan belongs to a different project root."
        }
        require(exclusion.projectId == plan.projectId) { "Cleanup exclusion belongs to another project." }
        journalStore.rootDirectory?.toAbsolutePath()?.normalize()?.let { journalRoot ->
            require(!journalRoot.startsWith(root)) {
                "Cleanup journals must be stored outside the project being cleaned."
            }
        }

        if (plan.targets.isEmpty()) {
            return try {
                journalStore.clear(plan.planId)
                result(plan, CleanupExecutionState.NOTHING_TO_DO)
            } catch (failure: Exception) {
                result(
                    plan,
                    CleanupExecutionState.PARTIAL_FAILURE,
                    failures = listOf(CleanupFailure(null, "clear_journal", safeDetail(failure))),
                )
            }
        }

        val initial = exclusion.snapshot()
        require(initial.projectId == plan.projectId) { "Protection snapshot belongs to another project." }
        if (initial.generation != plan.referenceGeneration) {
            return result(
                plan,
                CleanupExecutionState.REPLAN_REQUIRED,
                skipped = listOf(
                    CleanupSkip(
                        plan.targets.first().relativePath,
                        CleanupSkipCode.REFERENCE_GENERATION_CHANGED,
                        "Active-reference generation changed before cleanup began.",
                    ),
                ),
            )
        }

        val completed = try {
            LinkedHashSet(journalStore.load(plan.planId)?.completedPaths.orEmpty())
        } catch (failure: Exception) {
            return result(
                plan,
                CleanupExecutionState.PARTIAL_FAILURE,
                failures = listOf(CleanupFailure(null, "load_journal", safeDetail(failure))),
            )
        }
        val deleted = ArrayList<String>()
        val reconciled = ArrayList<String>()
        val failures = ArrayList<CleanupFailure>()
        val categoryBytes = EnumMap<CacheArtifactCategory, Long>(CacheArtifactCategory::class.java)
        var deletedBytes = 0L

        for (target in plan.targets) {
            if (target.relativePath in completed) continue
            if (cancellation.isCancelled()) {
                persistJournal(plan.planId, completed, failures)
                return result(
                    plan,
                    CleanupExecutionState.CANCELLED,
                    deleted,
                    reconciled,
                    failures = failures,
                    deletedBytes = deletedBytes,
                    deletedBytesByCategory = categoryBytes,
                )
            }

            val currentReferences = exclusion.snapshot()
            if (currentReferences.projectId != plan.projectId || currentReferences.generation != plan.referenceGeneration) {
                val skip = CleanupSkip(
                    target.relativePath,
                    CleanupSkipCode.REFERENCE_GENERATION_CHANGED,
                    "Active-reference generation changed; remaining cleanup requires a new inventory.",
                )
                retireJournal(plan.planId, failures)
                return result(
                    plan,
                    CleanupExecutionState.REPLAN_REQUIRED,
                    deleted,
                    reconciled,
                    skipped = listOf(skip),
                    failures = failures,
                    deletedBytes = deletedBytes,
                    deletedBytesByCategory = categoryBytes,
                )
            }
            if (currentReferences.protects(target.relativePath)) {
                val skip = CleanupSkip(
                    target.relativePath,
                    CleanupSkipCode.NEW_PROTECTION,
                    "Artifact became referenced after planning and was preserved.",
                )
                retireJournal(plan.planId, failures)
                return result(
                    plan,
                    CleanupExecutionState.REPLAN_REQUIRED,
                    deleted,
                    reconciled,
                    skipped = listOf(skip),
                    failures = failures,
                    deletedBytes = deletedBytes,
                    deletedBytesByCategory = categoryBytes,
                )
            }

            val inspection = inspectTarget(root, target.relativePath)
            when (inspection) {
                TargetInspection.Missing -> {
                    completed += target.relativePath
                    reconciled += target.relativePath
                    if (!persistJournal(plan.planId, completed, failures)) {
                        return result(
                            plan,
                            CleanupExecutionState.PARTIAL_FAILURE,
                            deleted,
                            reconciled,
                            failures = failures,
                            deletedBytes = deletedBytes,
                            deletedBytesByCategory = categoryBytes,
                        )
                    }
                    continue
                }
                is TargetInspection.Unsafe -> {
                    val skip = CleanupSkip(target.relativePath, CleanupSkipCode.PATH_UNSAFE, inspection.detail)
                    retireJournal(plan.planId, failures)
                    return result(
                        plan,
                        CleanupExecutionState.REPLAN_REQUIRED,
                        deleted,
                        reconciled,
                        skipped = listOf(skip),
                        failures = failures,
                        deletedBytes = deletedBytes,
                        deletedBytesByCategory = categoryBytes,
                    )
                }
                is TargetInspection.Regular -> {
                    if (!fingerprintMatches(target.fingerprint, inspection.attributes)) {
                        val skip = CleanupSkip(
                            target.relativePath,
                            CleanupSkipCode.PATH_CHANGED,
                            "File metadata changed after inventory; it was preserved.",
                        )
                        retireJournal(plan.planId, failures)
                        return result(
                            plan,
                            CleanupExecutionState.REPLAN_REQUIRED,
                            deleted,
                            reconciled,
                            skipped = listOf(skip),
                            failures = failures,
                            deletedBytes = deletedBytes,
                            deletedBytesByCategory = categoryBytes,
                        )
                    }
                    if (target.expectedSha256 != null) {
                        val actualHash = try {
                            sha256(inspection.path, cancellation)
                        } catch (_: CleanupCancelledException) {
                            persistJournal(plan.planId, completed, failures)
                            return result(
                                plan,
                                CleanupExecutionState.CANCELLED,
                                deleted,
                                reconciled,
                                failures = failures,
                                deletedBytes = deletedBytes,
                                deletedBytesByCategory = categoryBytes,
                            )
                        } catch (failure: Exception) {
                            failures += CleanupFailure(target.relativePath, "hash_before_delete", safeDetail(failure))
                            continue
                        }
                        if (actualHash != target.expectedSha256) {
                            val skip = CleanupSkip(
                                target.relativePath,
                                CleanupSkipCode.CONTENT_HASH_CHANGED,
                                "Content hash differs from the manifest; it was preserved.",
                            )
                            retireJournal(plan.planId, failures)
                            return result(
                                plan,
                                CleanupExecutionState.REPLAN_REQUIRED,
                                deleted,
                                reconciled,
                                skipped = listOf(skip),
                                failures = failures,
                                deletedBytes = deletedBytes,
                                deletedBytesByCategory = categoryBytes,
                            )
                        }
                    }

                    if (cancellation.isCancelled()) {
                        persistJournal(plan.planId, completed, failures)
                        return result(
                            plan,
                            CleanupExecutionState.CANCELLED,
                            deleted,
                            reconciled,
                            failures = failures,
                            deletedBytes = deletedBytes,
                            deletedBytesByCategory = categoryBytes,
                        )
                    }
                    val justBeforeDelete = exclusion.snapshot()
                    if (
                        justBeforeDelete.generation != plan.referenceGeneration ||
                        justBeforeDelete.protects(target.relativePath)
                    ) {
                        val skip = CleanupSkip(
                            target.relativePath,
                            CleanupSkipCode.NEW_PROTECTION,
                            "Artifact became protected immediately before deletion and was preserved.",
                        )
                        retireJournal(plan.planId, failures)
                        return result(
                            plan,
                            CleanupExecutionState.REPLAN_REQUIRED,
                            deleted,
                            reconciled,
                            skipped = listOf(skip),
                            failures = failures,
                            deletedBytes = deletedBytes,
                            deletedBytesByCategory = categoryBytes,
                        )
                    }
                    val secondInspection = inspectTarget(root, target.relativePath)
                    if (secondInspection !is TargetInspection.Regular ||
                        !fingerprintMatches(target.fingerprint, secondInspection.attributes)
                    ) {
                        val skip = CleanupSkip(
                            target.relativePath,
                            CleanupSkipCode.PATH_CHANGED,
                            "File changed during cleanup and was preserved.",
                        )
                        retireJournal(plan.planId, failures)
                        return result(
                            plan,
                            CleanupExecutionState.REPLAN_REQUIRED,
                            deleted,
                            reconciled,
                            skipped = listOf(skip),
                            failures = failures,
                            deletedBytes = deletedBytes,
                            deletedBytesByCategory = categoryBytes,
                        )
                    }
                    try {
                        Files.delete(secondInspection.path)
                        deleted += target.relativePath
                        completed += target.relativePath
                        deletedBytes = safeAdd(deletedBytes, target.fingerprint.bytes)
                        categoryBytes[target.category] = safeAdd(
                            categoryBytes[target.category] ?: 0L,
                            target.fingerprint.bytes,
                        )
                        if (!persistJournal(plan.planId, completed, failures)) {
                            return result(
                                plan,
                                CleanupExecutionState.PARTIAL_FAILURE,
                                deleted,
                                reconciled,
                                failures = failures,
                                deletedBytes = deletedBytes,
                                deletedBytesByCategory = categoryBytes,
                            )
                        }
                        pruneEmptyDerivedParents(root, secondInspection.path.parent, target.relativePath)
                    } catch (failure: Exception) {
                        failures += CleanupFailure(target.relativePath, "delete", safeDetail(failure))
                    }
                }
            }
        }

        val state = if (failures.isEmpty()) CleanupExecutionState.COMPLETED else CleanupExecutionState.PARTIAL_FAILURE
        if (failures.isEmpty()) {
            try {
                journalStore.clear(plan.planId)
            } catch (failure: Exception) {
                failures += CleanupFailure(null, "clear_journal", safeDetail(failure))
                return result(
                    plan,
                    CleanupExecutionState.PARTIAL_FAILURE,
                    deleted,
                    reconciled,
                    failures = failures,
                    deletedBytes = deletedBytes,
                    deletedBytesByCategory = categoryBytes,
                )
            }
        } else {
            persistJournal(plan.planId, completed, failures)
        }
        return result(
            plan,
            state,
            deleted,
            reconciled,
            failures = failures,
            deletedBytes = deletedBytes,
            deletedBytesByCategory = categoryBytes,
        )
    }

    private sealed class TargetInspection {
        data object Missing : TargetInspection()
        data class Unsafe(val detail: String) : TargetInspection()
        data class Regular(val path: Path, val attributes: BasicFileAttributes) : TargetInspection()
    }

    private fun inspectTarget(root: Path, relativePath: String): TargetInspection {
        val target = try {
            CachePathPolicy.resolve(root, relativePath)
        } catch (failure: IllegalArgumentException) {
            return TargetInspection.Unsafe(failure.message ?: "Unsafe artifact path.")
        }
        var cursor = root
        val relative = root.relativize(target)
        val segments = relative.iterator().asSequence().toList()
        for ((index, segment) in segments.withIndex()) {
            cursor = cursor.resolve(segment)
            val exists = Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
            if (!exists) return TargetInspection.Missing
            val attributes = try {
                Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return TargetInspection.Unsafe("Artifact attributes could not be read.")
            } catch (_: SecurityException) {
                return TargetInspection.Unsafe("Artifact attributes were inaccessible.")
            }
            if (attributes.isSymbolicLink) return TargetInspection.Unsafe("Symbolic links are never cleanup targets.")
            if (index < segments.lastIndex && !attributes.isDirectory) {
                return TargetInspection.Unsafe("Artifact parent is not a directory.")
            }
            if (index == segments.lastIndex) {
                return if (attributes.isRegularFile) {
                    TargetInspection.Regular(cursor, attributes)
                } else {
                    TargetInspection.Unsafe("Cleanup target is not a regular file.")
                }
            }
        }
        return TargetInspection.Unsafe("Cleanup target resolved to the project root.")
    }

    private fun fingerprintMatches(expected: CacheFileFingerprint, actual: BasicFileAttributes): Boolean {
        val keyHash = actual.fileKey()?.toString()?.let(CacheHashes::sha256)
        return actual.isRegularFile &&
            actual.size() == expected.bytes &&
            actual.lastModifiedTime().toMillis().coerceAtLeast(0L) == expected.lastModifiedMillis &&
            (expected.fileKeySha256 == null || expected.fileKeySha256 == keyHash)
    }

    private fun sha256(path: Path, cancellation: CacheCleanupCancellation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                if (cancellation.isCancelled()) throw CleanupCancelledException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class CleanupCancelledException : RuntimeException()

    private fun persistJournal(
        planId: String,
        completed: Set<String>,
        failures: MutableList<CleanupFailure>,
    ): Boolean = try {
        journalStore.save(CleanupJournal(planId, LinkedHashSet(completed)))
        true
    } catch (failure: Exception) {
        failures += CleanupFailure(null, "save_journal", safeDetail(failure))
        false
    }

    private fun retireJournal(planId: String, failures: MutableList<CleanupFailure>) {
        try {
            journalStore.clear(planId)
        } catch (failure: Exception) {
            failures += CleanupFailure(null, "clear_stale_journal", safeDetail(failure))
        }
    }

    private fun pruneEmptyDerivedParents(root: Path, initial: Path?, relativePath: String) {
        if (relativePath == "originals" || relativePath.startsWith("originals/")) return
        var cursor = initial ?: return
        while (cursor != root && cursor.startsWith(root)) {
            if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) return
            val empty = try {
                Files.newDirectoryStream(cursor).use { !it.iterator().hasNext() }
            } catch (_: Exception) {
                false
            }
            if (!empty) return
            try {
                Files.delete(cursor)
            } catch (_: Exception) {
                return
            }
            cursor = cursor.parent ?: return
        }
    }

    private fun result(
        plan: CleanupPlan,
        state: CleanupExecutionState,
        deleted: List<String> = emptyList(),
        reconciled: List<String> = emptyList(),
        skipped: List<CleanupSkip> = emptyList(),
        failures: List<CleanupFailure> = emptyList(),
        deletedBytes: Long = 0L,
        deletedBytesByCategory: Map<CacheArtifactCategory, Long> = emptyMap(),
    ) = CleanupExecutionResult(
        planId = plan.planId,
        state = state,
        deletedPaths = deleted.sorted(),
        reconciledMissingPaths = reconciled.sorted(),
        skipped = skipped,
        failures = failures,
        deletedBytes = deletedBytes,
        deletedBytesByCategory = CacheArtifactCategory.entries.associateWith {
            deletedBytesByCategory[it] ?: 0L
        },
    )

    private fun safeDetail(failure: Exception): String =
        failure::class.java.simpleName + (failure.message?.let { ": $it" } ?: "")

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
}
