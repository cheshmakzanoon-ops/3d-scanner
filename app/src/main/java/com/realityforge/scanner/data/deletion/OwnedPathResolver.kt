package com.realityforge.scanner.data.deletion

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class OwnedPathViolation(
    val code: DeletionFailureCode,
    val safeDetail: String,
) : IllegalArgumentException(safeDetail)

data class ResolvedDeletionTarget internal constructor(
    val target: OwnedDeletionTarget,
    val path: Path,
    val projectRoot: Path,
    val projectsRoot: Path,
    val safeRelativePath: String,
)

/**
 * Resolves persisted project-relative targets without canonicalizing through symlinks.
 * Intermediate symlinks are rejected; a final symlink is safe because cleanup deletes the
 * link itself and never follows it.
 */
class OwnedPathResolver(projectsRoot: Path) {
    val root: Path = projectsRoot.toAbsolutePath().normalize()

    fun resolve(target: OwnedDeletionTarget): ResolvedDeletionTarget {
        validateRoot()
        val projectRoot = root.resolve(target.projectId).normalize()
        ensureInsideRoot(projectRoot)

        val path = when (target) {
            is OwnedDeletionTarget.ProjectTree -> projectRoot
            is OwnedDeletionTarget.ProjectRelativeNode ->
                projectRoot.resolve(target.relativePath).normalize()
        }
        ensureInsideProject(path, projectRoot)
        rejectIntermediateSymlinks(path, allowFinalSymlink = true)

        val safeRelative = safeRelative(path)
        return ResolvedDeletionTarget(target, path, projectRoot, root, safeRelative)
    }

    internal fun safeRelative(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        ensureInsideRoot(normalized)
        return root.relativize(normalized).joinToString("/") { it.toString() }
    }

    private fun validateRoot() {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)) {
                throw OwnedPathViolation(
                    DeletionFailureCode.PATH_COMPONENT_SYMLINK,
                    "The private projects root is a symbolic link.",
                )
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw OwnedPathViolation(
                    DeletionFailureCode.ROOT_NOT_DIRECTORY,
                    "The private projects root is not a directory.",
                )
            }
        }
    }

    private fun ensureInsideRoot(candidate: Path) {
        if (!candidate.startsWith(root) || candidate == root) {
            throw OwnedPathViolation(
                DeletionFailureCode.PATH_OUTSIDE_ROOT,
                "Deletion target is outside the private projects root.",
            )
        }
    }

    private fun ensureInsideProject(candidate: Path, projectRoot: Path) {
        if (!candidate.startsWith(projectRoot)) {
            throw OwnedPathViolation(
                DeletionFailureCode.PATH_OUTSIDE_ROOT,
                "Deletion target is outside its project directory.",
            )
        }
    }

    /**
     * Files APIs with NOFOLLOW_LINKS still resolve symlinks in parent components. Verify every
     * existing parent from the trusted root before touching a nested target.
     */
    internal fun rejectIntermediateSymlinks(path: Path, allowFinalSymlink: Boolean) {
        val normalized = path.toAbsolutePath().normalize()
        ensureInsideRoot(normalized)
        val relative = root.relativize(normalized)
        var current = root
        for (index in 0 until relative.nameCount) {
            current = current.resolve(relative.getName(index))
            val isFinal = index == relative.nameCount - 1
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
            if (Files.isSymbolicLink(current) && !(allowFinalSymlink && isFinal)) {
                throw OwnedPathViolation(
                    DeletionFailureCode.PATH_COMPONENT_SYMLINK,
                    "A deletion target parent is a symbolic link.",
                )
            }
        }
    }
}
