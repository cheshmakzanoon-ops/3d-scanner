package com.realityforge.scanner.engine.artifacts

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Streaming integrity probe that never follows an artifact symlink outside private project storage. */
object ArtifactIntegrity {
    private const val BUFFER_BYTES = 64 * 1024

    fun probe(projectRoot: Path, reference: ArtifactRef): ArtifactProbe {
        val root = projectRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Project artifact root is unavailable" }
        val resolved = root.resolve(reference.relativePath).normalize()
        require(resolved.startsWith(root) && resolved != root) { "Artifact path escapes project storage" }
        rejectSymbolicPath(root, resolved)
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) { "Artifact is not a regular file" }
        val declared = Files.size(resolved)
        require(declared == reference.byteSize) { "Artifact byte size differs from its manifest" }
        require(declared in 1..ArtifactRef.MAX_ARTIFACT_BYTES) { "Artifact byte size is out of bounds" }

        val digest = MessageDigest.getInstance("SHA-256")
        var observed = 0L
        BufferedInputStream(Files.newInputStream(resolved), BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                observed += count
                require(observed <= reference.byteSize) { "Artifact grew while it was being verified" }
                digest.update(buffer, 0, count)
            }
        }
        require(observed == reference.byteSize) { "Artifact was truncated while it was being verified" }
        return ArtifactProbe(observed, Sha256.fromBytes(digest.digest()))
    }

    private fun rejectSymbolicPath(root: Path, target: Path) {
        var current = root
        val relative = root.relativize(target)
        relative.forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Symbolic artifact paths are not reusable" }
        }
    }
}
