package com.realityforge.scanner.data.deletion

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Bounded per-attempt work; retries re-enumerate safely and idempotently from the tombstone.
 * The byte limit is soft for one indivisible file: cleanup may remove one file larger than the
 * remaining byte budget, then stops before another entry.
 */
data class CleanupBudget(
    val maxEntries: Int = 256,
    val maxBytes: Long = 64L * 1024L * 1024L,
    val maxVisited: Int = 4096,
) {
    init {
        require(maxEntries > 0) { "Cleanup must permit at least one removed entry." }
        require(maxBytes > 0) { "Cleanup byte budget must be positive." }
        require(maxVisited > 0) { "Cleanup visit budget must be positive." }
    }
}

sealed interface CleanupOutcome {
    val delta: CleanupDelta

    data class Complete(override val delta: CleanupDelta) : CleanupOutcome
    data class MoreWork(override val delta: CleanupDelta) : CleanupOutcome
    data class RetryableFailure(
        override val delta: CleanupDelta,
        val failure: DeletionFailure,
    ) : CleanupOutcome
}

/**
 * Deletes only explicitly staged app-private targets. Traversal never follows symbolic links.
 * A process can die after any individual unlink; the durable tombstone makes the next pass
 * re-enumerate what remains and continue without treating missing entries as failure.
 */
class PrivateFileCleaner(projectsRoot: Path) {
    private val resolver = OwnedPathResolver(projectsRoot)

    fun clean(
        targets: List<OwnedDeletionTarget>,
        budget: CleanupBudget = CleanupBudget(),
        checkpoint: () -> Unit = {},
    ): CleanupOutcome {
        require(targets.size <= PendingDeletion.MAX_TARGETS) { "Deletion target list is too large." }
        val normalizedTargets = normalizeTargets(targets)
        var total = CleanupDelta()

        for (target in normalizedTargets) {
            checkpoint()
            if (quotaReached(total, budget)) return CleanupOutcome.MoreWork(total)
            val resolved = try {
                resolver.resolve(target)
            } catch (violation: OwnedPathViolation) {
                return CleanupOutcome.RetryableFailure(
                    total,
                    DeletionFailure(violation.code, violation.safeDetail),
                )
            } catch (_: IllegalArgumentException) {
                return CleanupOutcome.RetryableFailure(
                    total,
                    DeletionFailure(
                        DeletionFailureCode.INVALID_TARGET,
                        "The persisted deletion target is invalid.",
                    ),
                )
            } catch (_: SecurityException) {
                return CleanupOutcome.RetryableFailure(
                    total,
                    DeletionFailure(
                        DeletionFailureCode.IO_READ_FAILED,
                        "Private deletion targets could not be inspected.",
                    ),
                )
            }

            val remainingBudget = CleanupBudget(
                maxEntries = remainingInt(budget.maxEntries, total.entriesRemoved),
                maxBytes = (budget.maxBytes - total.bytesReclaimed).coerceAtLeast(1L),
                maxVisited = remainingInt(budget.maxVisited, total.entriesVisited),
            )
            when (val outcome = cleanOne(resolved, remainingBudget, checkpoint)) {
                is CleanupOutcome.Complete -> total += outcome.delta
                is CleanupOutcome.MoreWork -> return CleanupOutcome.MoreWork(total + outcome.delta)
                is CleanupOutcome.RetryableFailure ->
                    return CleanupOutcome.RetryableFailure(total + outcome.delta, outcome.failure)
            }
        }
        return CleanupOutcome.Complete(total)
    }

    /** A whole-project target subsumes every relative target for the same project. */
    private fun normalizeTargets(targets: List<OwnedDeletionTarget>): List<OwnedDeletionTarget> {
        val projectTrees = targets.filterIsInstance<OwnedDeletionTarget.ProjectTree>()
            .associateBy { it.projectId }
        return targets
            .filterNot {
                it is OwnedDeletionTarget.ProjectRelativeNode && projectTrees.containsKey(it.projectId)
            }
            .distinct()
            .sortedWith(
                compareBy<OwnedDeletionTarget> { it.projectId }
                    .thenBy { if (it is OwnedDeletionTarget.ProjectTree) 0 else 1 }
                    .thenBy {
                        (it as? OwnedDeletionTarget.ProjectRelativeNode)?.relativePath.orEmpty()
                    },
            )
    }

    private fun cleanOne(
        resolved: ResolvedDeletionTarget,
        budget: CleanupBudget,
        checkpoint: () -> Unit,
    ): CleanupOutcome {
        checkpoint()
        val attributes = try {
            Files.readAttributes(
                resolved.path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return CleanupOutcome.Complete(CleanupDelta())
        } catch (_: SecurityException) {
            return readFailure(resolved.safeRelativePath)
        } catch (_: IOException) {
            return readFailure(resolved.safeRelativePath)
        }

        return if (attributes.isDirectory) {
            cleanTree(resolved, budget, checkpoint)
        } else {
            cleanLeaf(resolved, attributes, checkpoint)
        }
    }

    private fun readFailure(safeRelativePath: String): CleanupOutcome.RetryableFailure =
        CleanupOutcome.RetryableFailure(
            CleanupDelta(entriesVisited = 1),
            DeletionFailure(
                DeletionFailureCode.IO_READ_FAILED,
                "Could not inspect $safeRelativePath.",
            ),
        )

    private fun cleanLeaf(
        resolved: ResolvedDeletionTarget,
        attributes: BasicFileAttributes,
        checkpoint: () -> Unit,
    ): CleanupOutcome {
        val bytes = if (attributes.isRegularFile) attributes.size().coerceAtLeast(0) else 0L
        return try {
            checkpoint()
            // Recheck all parent components immediately before the destructive operation.
            resolver.rejectIntermediateSymlinks(resolved.path, allowFinalSymlink = true)
            val removed = Files.deleteIfExists(resolved.path)
            CleanupOutcome.Complete(
                CleanupDelta(
                    entriesRemoved = if (removed) 1 else 0,
                    bytesReclaimed = if (removed) bytes else 0,
                    entriesVisited = 1,
                    lastRelativePath = if (removed) resolved.safeRelativePath else null,
                ),
            )
        } catch (violation: OwnedPathViolation) {
            CleanupOutcome.RetryableFailure(
                CleanupDelta(entriesVisited = 1),
                DeletionFailure(violation.code, violation.safeDetail),
            )
        } catch (_: DirectoryNotEmptyException) {
            CleanupOutcome.RetryableFailure(
                CleanupDelta(entriesVisited = 1),
                DeletionFailure(
                    DeletionFailureCode.CONCURRENT_MUTATION,
                    "A deletion target changed while cleanup was running.",
                ),
            )
        } catch (_: AccessDeniedException) {
            deleteFailure(resolved.safeRelativePath)
        } catch (_: SecurityException) {
            deleteFailure(resolved.safeRelativePath)
        } catch (_: IOException) {
            deleteFailure(resolved.safeRelativePath)
        }
    }

    private fun deleteFailure(safeRelativePath: String): CleanupOutcome.RetryableFailure =
        CleanupOutcome.RetryableFailure(
            CleanupDelta(entriesVisited = 1),
            DeletionFailure(
                DeletionFailureCode.IO_DELETE_FAILED,
                "Could not remove $safeRelativePath.",
            ),
        )

    private fun cleanTree(
        resolved: ResolvedDeletionTarget,
        budget: CleanupBudget,
        checkpoint: () -> Unit,
    ): CleanupOutcome {
        val state = WalkState(resolved, resolver, budget, checkpoint)
        try {
            Files.walkFileTree(
                resolved.path,
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        checkpoint()
                        if (!state.validateVisitedPath(dir)) return FileVisitResult.TERMINATE
                        state.visited++
                        if (state.hardDiscoveryLimitReached()) {
                            state.failure = DeletionFailure(
                                DeletionFailureCode.INVALID_TARGET,
                                "The private deletion tree exceeds the bounded traversal limit.",
                            )
                            return FileVisitResult.TERMINATE
                        }
                        if (state.visitBudgetReachedAfterProgress()) {
                            state.stoppedByBudget = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        checkpoint()
                        if (!state.validateVisitedPath(file)) return FileVisitResult.TERMINATE
                        state.visited++
                        if (state.hardDiscoveryLimitReached()) {
                            state.failure = DeletionFailure(
                                DeletionFailureCode.INVALID_TARGET,
                                "The private deletion tree exceeds the bounded traversal limit.",
                            )
                            return FileVisitResult.TERMINATE
                        }
                        if (state.quotaReachedBeforeNextDelete()) {
                            state.stoppedByBudget = true
                            return FileVisitResult.TERMINATE
                        }
                        val bytes = if (attrs.isRegularFile) attrs.size().coerceAtLeast(0) else 0L
                        if (!state.delete(file, bytes)) return FileVisitResult.TERMINATE
                        return if (state.quotaReachedAfterDelete()) {
                            state.stoppedByBudget = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        state.failure = DeletionFailure(
                            DeletionFailureCode.IO_READ_FAILED,
                            "Could not inspect a private deletion target.",
                        )
                        return FileVisitResult.TERMINATE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        checkpoint()
                        if (exc != null) {
                            state.failure = DeletionFailure(
                                DeletionFailureCode.IO_READ_FAILED,
                                "Could not finish inspecting a private project directory.",
                            )
                            return FileVisitResult.TERMINATE
                        }
                        if (!state.validateVisitedPath(dir)) return FileVisitResult.TERMINATE
                        if (state.quotaReachedBeforeNextDelete()) {
                            state.stoppedByBudget = true
                            return FileVisitResult.TERMINATE
                        }
                        if (!state.delete(dir, 0L)) return FileVisitResult.TERMINATE
                        return if (state.quotaReachedAfterDelete()) {
                            state.stoppedByBudget = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }
                },
            )
        } catch (_: NoSuchFileException) {
            // Another retry/pass already removed this entry. Missing is an idempotent success.
        } catch (_: DirectoryNotEmptyException) {
            state.failure = DeletionFailure(
                DeletionFailureCode.CONCURRENT_MUTATION,
                "A private project directory changed during cleanup.",
            )
        } catch (_: AccessDeniedException) {
            state.failure = DeletionFailure(
                DeletionFailureCode.IO_DELETE_FAILED,
                "Private-file cleanup was denied.",
            )
        } catch (_: SecurityException) {
            state.failure = DeletionFailure(
                DeletionFailureCode.IO_DELETE_FAILED,
                "Private-file cleanup was denied.",
            )
        } catch (_: IOException) {
            state.failure = DeletionFailure(
                DeletionFailureCode.IO_DELETE_FAILED,
                "Private-file cleanup could not finish.",
            )
        }

        val delta = state.delta()
        state.failure?.let { return CleanupOutcome.RetryableFailure(delta, it) }
        return when (targetPresence(resolved)) {
            TargetPresence.ABSENT -> CleanupOutcome.Complete(delta)
            TargetPresence.PRESENT -> CleanupOutcome.MoreWork(delta)
            TargetPresence.UNKNOWN -> CleanupOutcome.RetryableFailure(
                delta,
                DeletionFailure(
                    DeletionFailureCode.IO_READ_FAILED,
                    "Could not verify private-file cleanup completion.",
                ),
            )
        }
    }

    private enum class TargetPresence { PRESENT, ABSENT, UNKNOWN }

    private fun targetPresence(resolved: ResolvedDeletionTarget): TargetPresence = try {
        Files.readAttributes(
            resolved.path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        TargetPresence.PRESENT
    } catch (_: NoSuchFileException) {
        TargetPresence.ABSENT
    } catch (_: SecurityException) {
        TargetPresence.UNKNOWN
    } catch (_: IOException) {
        TargetPresence.UNKNOWN
    }

    private fun quotaReached(delta: CleanupDelta, budget: CleanupBudget): Boolean =
        delta.entriesRemoved >= budget.maxEntries ||
            (delta.entriesRemoved > 0 && delta.bytesReclaimed >= budget.maxBytes) ||
            (delta.entriesRemoved > 0 && delta.entriesVisited >= budget.maxVisited)

    private fun remainingInt(limit: Int, used: Long): Int =
        (limit.toLong() - used).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private class WalkState(
        private val resolved: ResolvedDeletionTarget,
        private val resolver: OwnedPathResolver,
        private val budget: CleanupBudget,
        private val checkpoint: () -> Unit,
    ) {
        var removed = 0L
        var bytes = 0L
        var visited = 0L
        var lastRelativePath: String? = null
        var failure: DeletionFailure? = null
        var stoppedByBudget = false

        fun validateVisitedPath(path: Path): Boolean {
            val normalized = path.toAbsolutePath().normalize()
            if (!normalized.startsWith(resolved.path.toAbsolutePath().normalize())) {
                failure = DeletionFailure(
                    DeletionFailureCode.PATH_OUTSIDE_ROOT,
                    "Cleanup traversal escaped its staged target.",
                )
                return false
            }
            return try {
                // The visited node itself may be a symlink; no parent component may be one.
                resolver.rejectIntermediateSymlinks(normalized, allowFinalSymlink = true)
                true
            } catch (violation: OwnedPathViolation) {
                failure = DeletionFailure(violation.code, violation.safeDetail)
                false
            } catch (_: SecurityException) {
                failure = DeletionFailure(
                    DeletionFailureCode.IO_READ_FAILED,
                    "Could not validate a private deletion target.",
                )
                false
            }
        }

        fun hardDiscoveryLimitReached(): Boolean =
            removed == 0L && visited > HARD_DISCOVERY_VISITS

        fun visitBudgetReachedAfterProgress(): Boolean =
            removed > 0L && visited >= budget.maxVisited

        fun quotaReachedBeforeNextDelete(): Boolean =
            removed >= budget.maxEntries ||
                (removed > 0 && bytes >= budget.maxBytes) ||
                (removed > 0 && visited > budget.maxVisited)

        fun quotaReachedAfterDelete(): Boolean =
            removed >= budget.maxEntries ||
                (removed > 0 && bytes >= budget.maxBytes) ||
                visited >= budget.maxVisited

        fun delete(path: Path, fileBytes: Long): Boolean {
            return try {
                checkpoint()
                if (!validateVisitedPath(path)) return false
                if (Files.deleteIfExists(path)) {
                    removed = Math.addExact(removed, 1L)
                    bytes = Math.addExact(bytes, fileBytes)
                    lastRelativePath = resolver.safeRelative(path)
                }
                true
            } catch (violation: OwnedPathViolation) {
                failure = DeletionFailure(violation.code, violation.safeDetail)
                false
            } catch (_: DirectoryNotEmptyException) {
                failure = DeletionFailure(
                    DeletionFailureCode.CONCURRENT_MUTATION,
                    "A private project directory changed during cleanup.",
                )
                false
            } catch (_: AccessDeniedException) {
                failure = DeletionFailure(
                    DeletionFailureCode.IO_DELETE_FAILED,
                    "Private-file cleanup was denied.",
                )
                false
            } catch (_: SecurityException) {
                failure = DeletionFailure(
                    DeletionFailureCode.IO_DELETE_FAILED,
                    "Private-file cleanup was denied.",
                )
                false
            } catch (_: IOException) {
                failure = DeletionFailure(
                    DeletionFailureCode.IO_DELETE_FAILED,
                    "Private-file cleanup could not remove an owned entry.",
                )
                false
            }
        }

        fun delta(): CleanupDelta = CleanupDelta(
            entriesRemoved = removed,
            bytesReclaimed = bytes,
            entriesVisited = visited,
            lastRelativePath = lastRelativePath,
        )

        companion object {
            /** Hard cap prevents hostile or corrupt directory trees from causing unbounded scans. */
            private const val HARD_DISCOVERY_VISITS = 32_768L
        }
    }
}
