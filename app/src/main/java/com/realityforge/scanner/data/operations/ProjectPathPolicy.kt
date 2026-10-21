package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ScanPhoto
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Canonical, project-owned private-storage paths. No path supplied by stored/user data escapes this root. */
class ProjectPathPolicy(rootDirectory: File) {
    val root: File
    private val rootPath: Path

    init {
        require(!Files.isSymbolicLink(rootDirectory.toPath())) { "Project storage root cannot be a symbolic link." }
        require(rootDirectory.isDirectory || rootDirectory.mkdirs()) { "Cannot create project storage root." }
        root = rootDirectory.canonicalFile
        rootPath = root.toPath()
        require(!Files.isSymbolicLink(rootPath)) { "Project storage root cannot be a symbolic link." }
    }

    fun projectDirectory(projectId: String, create: Boolean = true): File {
        requireCanonicalUuid(projectId, "project identifier")
        val candidate = File(root, projectId)
        ensureOwned(candidate, allowLeafSymlink = false)
        if (create) {
            require(candidate.isDirectory || candidate.mkdirs()) { "Cannot create project directory." }
            ensureOwned(candidate, allowLeafSymlink = false)
        }
        return candidate
    }

    fun originalsDirectory(projectId: String, create: Boolean = true): File {
        val candidate = File(projectDirectory(projectId, create), "originals")
        ensureOwned(candidate, allowLeafSymlink = false)
        if (create) {
            require(candidate.isDirectory || candidate.mkdirs()) { "Cannot create original-photo directory." }
            ensureOwned(candidate, allowLeafSymlink = false)
        }
        return candidate
    }

    fun photoFile(photo: ScanPhoto): File {
        requireCanonicalUuid(photo.id, "photo identifier")
        requireCanonicalUuid(photo.projectId, "project identifier")
        require(photo.filename == "${photo.id}.image") { "Invalid original photograph filename." }
        return ownedChild(originalsDirectory(photo.projectId), photo.filename)
    }

    fun newOriginalFiles(projectId: String, photoId: String): Pair<File, File> {
        requireCanonicalUuid(photoId, "photo identifier")
        val originals = originalsDirectory(projectId)
        return ownedChild(originals, "$photoId.partial") to ownedChild(originals, "$photoId.image")
    }

    fun artifact(projectId: String, key: String, extension: String): File {
        val revision = artifactRevision(key)
        require(revision >= 0L)
        require(extension in ARTIFACT_EXTENSIONS) { "Invalid artifact extension." }
        return ownedChild(projectDirectory(projectId), "$key.$extension")
    }

    fun artifactRevision(key: String): Long {
        val match = ARTIFACT_KEY.matchEntire(key) ?: throw IllegalArgumentException("Invalid artifact key.")
        return match.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid artifact revision.")
    }

    fun projectFile(projectId: String, relativeName: String): File {
        require(relativeName.isNotBlank() && relativeName.length <= 160) { "Invalid project file name." }
        require(!relativeName.contains('/') && !relativeName.contains('\\') && relativeName !in setOf(".", "..")) {
            "Project file name must not contain a path."
        }
        return ownedChild(projectDirectory(projectId), relativeName)
    }

    data class OwnedProjectFile(val projectId: String, val file: File)

    fun requireOwnedFile(file: File): OwnedProjectFile {
        val absolute = file.absoluteFile.normalize()
        ensureOwned(absolute, allowLeafSymlink = false)
        val relative = rootPath.relativize(absolute.toPath().normalize())
        require(relative.nameCount >= 2) { "File is not inside a project directory." }
        val projectId = relative.getName(0).toString()
        requireCanonicalUuid(projectId, "project identifier")
        require(projectId != LEASE_DIRECTORY) { "Lease metadata is not a project artifact." }
        return OwnedProjectFile(projectId, absolute)
    }

    fun leaseFile(projectId: String): File {
        requireCanonicalUuid(projectId, "project identifier")
        val directory = File(root, LEASE_DIRECTORY)
        ensureOwned(directory, allowLeafSymlink = false)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create project reservation directory." }
        ensureOwned(directory, allowLeafSymlink = false)
        return ownedChild(directory, "$projectId.lock")
    }

    private fun ownedChild(parent: File, childName: String): File {
        require(childName.isNotEmpty() && childName != "." && childName != "..") { "Invalid private-storage path." }
        require(!childName.contains('/') && !childName.contains('\\') && childName.indexOf('\u0000') < 0) {
            "Invalid private-storage path."
        }
        return File(parent, childName).also { ensureOwned(it, allowLeafSymlink = false) }
    }

    private fun ensureOwned(candidate: File, allowLeafSymlink: Boolean) {
        val normalized = candidate.absoluteFile.toPath().normalize()
        require(normalized.startsWith(rootPath)) { "Path escapes private project storage." }
        var cursor = rootPath
        val relative = rootPath.relativize(normalized)
        for (index in 0 until relative.nameCount) {
            cursor = cursor.resolve(relative.getName(index))
            val isLeaf = index == relative.nameCount - 1
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor) && !(isLeaf && allowLeafSymlink)) {
                throw IllegalArgumentException("Symbolic links are not allowed in private project storage.")
            }
        }
        val canonical = candidate.canonicalFile.toPath()
        require(canonical.startsWith(rootPath)) { "Path resolves outside private project storage." }
    }

    companion object {
        const val LEASE_DIRECTORY = ".project-access"
        val ARTIFACT_EXTENSIONS = setOf(
            "json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply",
            "textured.glb", "texture.png",
        )
        private val ARTIFACT_KEY = Regex("sparse-(?:m[0-9]{2}-)?r([0-9]+)-[0-9a-f]{16}")

        fun newProjectId(): String = UUID.randomUUID().toString()
    }
}

private fun File.normalize(): File = toPath().normalize().toFile()
