package com.realityforge.scanner.data.publication

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class VerifiedManifestFiles(
    val manifest: ArtifactSetManifest,
    val manifestFileSha256: String,
)

internal class PublicationStorage(rootDirectory: File) {
    private val root: Path = rootDirectory.toPath().toAbsolutePath().normalize()
    private val stagingRoot = root.resolve("staging")
    private val setsRoot = root.resolve("sets")
    private val journalsRoot = root.resolve("journals")
    private val quarantineRoot = root.resolve("quarantine")

    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Publication root must be a trusted, non-symlink directory."
        }
        Files.createDirectories(stagingRoot)
        Files.createDirectories(setsRoot)
        Files.createDirectories(journalsRoot)
        Files.createDirectories(quarantineRoot)
    }

    fun stageRelativeDirectory(identity: PublicationIdentity): String =
        "staging/${identity.projectId}/${identity.operationId}-a${identity.attempt}"

    fun finalRelativeDirectory(identity: PublicationIdentity, setId: String): String {
        requireSha256(setId, "setId")
        return "sets/${identity.projectId}/$setId"
    }

    fun journalFile(identity: PublicationIdentity): Path = safeResolve(
        "journals/${identity.projectId}/${identity.operationId}-a${identity.attempt}.rfpj",
    )

    fun resolve(relativePath: String): Path = safeResolve(relativePath)

    fun writeJournal(journal: PublicationJournal) {
        writeAtomicFile(journalFile(journal.identity), PublicationCodec.encodeJournal(journal))
    }

    fun readJournal(path: Path): PublicationJournal = PublicationCodec.decodeJournal(readBounded(path, MAX_JOURNAL_BYTES))

    fun listJournalFiles(): List<Path> {
        if (!Files.isDirectory(journalsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val result = ArrayList<Path>()
        var visited = 0
        Files.walkFileTree(journalsRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symlink found in publication journal tree." }
                require(++visited <= MAX_JOURNAL_TREE_ENTRIES) { "Publication journal tree is too large to reconcile safely." }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) throw PublicationException("Symlink found in publication journal tree.")
                require(++visited <= MAX_JOURNAL_TREE_ENTRIES) { "Publication journal tree is too large to reconcile safely." }
                if (file.fileName.toString().endsWith(".rfpj")) {
                    require(result.size < MAX_JOURNAL_COUNT) { "Too many publication journals to reconcile safely." }
                    result.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        return result.sortedBy { root.relativize(it).toString() }
    }

    fun quarantineJournal(path: Path, reason: String): String {
        ensureOwned(path)
        Files.createDirectories(quarantineRoot)
        val safeReason = reason.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "invalid" }
        val destination = quarantineRoot.resolve("${path.fileName}.$safeReason.${UUID.randomUUID()}.corrupt")
        return try {
            Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(quarantineRoot)
            root.relativize(destination).toString().replace(File.separatorChar, '/')
        } catch (failure: Exception) {
            throw PublicationException("Cannot quarantine corrupt publication journal.", failure)
        }
    }

    fun writeArtifact(stageDirectory: Path, declaration: ArtifactDeclaration, source: InputStream): ArtifactManifestEntry {
        ensureOwned(stageDirectory)
        require(declaration.relativePath != MANIFEST_FILENAME) { "Artifact path is reserved for publication metadata." }
        val target = resolveInside(stageDirectory, declaration.relativePath)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Artifact was already staged: ${declaration.logicalName}" }
        Files.createDirectories(target.parent)
        val temporary = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            BufferedInputStream(source, COPY_BUFFER_BYTES).use { input ->
                FileOutputStream(temporary.toFile()).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= declaration.maxBytes) { "Artifact exceeds its declared byte limit." }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            atomicMove(temporary, target, replaceExisting = false)
            syncDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return ArtifactManifestEntry(
            declaration = declaration,
            presence = ArtifactPresence.PRESENT,
            bytes = count,
            sha256 = digest.digest().toHex(),
        )
    }

    fun writeManifest(stageDirectory: Path, manifest: ArtifactSetManifest): String {
        ensureOwned(stageDirectory)
        Files.createDirectories(stageDirectory)
        val encoded = PublicationCodec.encodeManifest(manifest)
        val target = stageDirectory.resolve(MANIFEST_FILENAME)
        writeAtomicFile(target, encoded)
        return PublicationCodec.sha256(encoded)
    }

    fun verifyArtifactSet(directory: Path, expectedSetId: String? = null, expectedManifestFileSha256: String? = null): VerifiedManifestFiles {
        ensureOwned(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
            "Artifact-set directory is missing or unsafe."
        }
        val manifestPath = directory.resolve(MANIFEST_FILENAME)
        val manifestBytes = readBounded(manifestPath, MAX_MANIFEST_BYTES)
        val manifestFileSha = PublicationCodec.sha256(manifestBytes)
        if (expectedManifestFileSha256 != null) {
            requireSha256(expectedManifestFileSha256, "expected manifest hash")
            require(manifestFileSha == expectedManifestFileSha256) { "Manifest file hash does not match the committed pointer." }
        }
        val manifest = PublicationCodec.decodeManifest(manifestBytes)
        if (expectedSetId != null) require(manifest.setId == expectedSetId) { "Artifact-set identity mismatch." }

        val expectedPaths = manifest.entries
            .filter { it.presence == ArtifactPresence.PRESENT }
            .associateBy { it.declaration.relativePath }
        val observedPaths = LinkedHashSet<String>()
        var visitedEntries = 0
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symlink directory in artifact set." }
                require(++visitedEntries <= MAX_ARTIFACT_TREE_ENTRIES) { "Artifact-set tree is too large to validate safely." }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && attrs.isRegularFile && !Files.isSymbolicLink(file)) {
                    "Non-regular file in artifact set."
                }
                require(++visitedEntries <= MAX_ARTIFACT_TREE_ENTRIES) { "Artifact-set tree is too large to validate safely." }
                val relative = directory.relativize(file).toString().replace(File.separatorChar, '/')
                if (relative != MANIFEST_FILENAME) observedPaths += relative
                return FileVisitResult.CONTINUE
            }
        })
        require(observedPaths == expectedPaths.keys) {
            val extra = observedPaths - expectedPaths.keys
            val missing = expectedPaths.keys - observedPaths
            "Artifact-set members differ from the manifest (extra=$extra, missing=$missing)."
        }

        var total = 0L
        for (entry in manifest.entries) {
            val path = resolveInside(directory, entry.declaration.relativePath)
            when (entry.presence) {
                ArtifactPresence.ABSENT -> require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Manifest marks an optional artifact absent but a file exists."
                }
                ArtifactPresence.PRESENT -> {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                        "Manifest artifact is missing or unsafe: ${entry.declaration.logicalName}"
                    }
                    val size = Files.size(path)
                    require(size == entry.bytes && size <= entry.declaration.maxBytes) {
                        "Artifact byte count mismatch: ${entry.declaration.logicalName}"
                    }
                    val digest = sha256File(path, entry.declaration.maxBytes)
                    require(digest == entry.sha256) { "Artifact checksum mismatch: ${entry.declaration.logicalName}" }
                    total += size
                    require(total <= MAX_SET_BYTES) { "Artifact set exceeds the total verification budget." }
                }
            }
        }
        return VerifiedManifestFiles(manifest, manifestFileSha)
    }

    fun promoteDirectory(stageDirectory: Path, finalDirectory: Path): PublicationDurability {
        ensureOwned(stageDirectory)
        ensureOwned(finalDirectory)
        if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(finalDirectory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(finalDirectory)) {
                "Existing final artifact-set path is unsafe."
            }
            return PublicationDurability(
                artifactContentsSynced = true,
                manifestSynced = true,
                atomicDirectoryPromotion = true,
                stagingDirectorySynced = syncDirectory(stageDirectory),
                destinationDirectorySynced = syncDirectory(finalDirectory.parent),
            )
        }
        require(Files.isDirectory(stageDirectory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(stageDirectory)) {
            "Staged artifact-set directory is unavailable."
        }
        Files.createDirectories(finalDirectory.parent)
        val stageSynced = syncTreeDirectories(stageDirectory)
        try {
            Files.move(stageDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw PublicationException("Atomic artifact-set directory promotion is not supported on this filesystem.", unsupported)
        }
        val destinationSynced = syncDirectory(finalDirectory.parent)
        syncDirectory(stageDirectory.parent)
        return PublicationDurability(
            artifactContentsSynced = true,
            manifestSynced = true,
            atomicDirectoryPromotion = true,
            stagingDirectorySynced = stageSynced,
            destinationDirectorySynced = destinationSynced,
        )
    }

    fun deleteOwnedTree(path: Path) {
        ensureOwned(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(path != root && path != stagingRoot && path != setsRoot && path != journalsRoot && path != quarantineRoot) {
            "Refusing to delete a publication root."
        }
        var visitedEntries = 0
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Refusing to follow a symlink directory during cleanup." }
                require(++visitedEntries <= MAX_CLEANUP_TREE_ENTRIES) { "Publication cleanup tree is unexpectedly large." }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Refusing to follow a symlink during cleanup." }
                require(++visitedEntries <= MAX_CLEANUP_TREE_ENTRIES) { "Publication cleanup tree is unexpectedly large." }
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                require(!Files.isSymbolicLink(dir)) { "Refusing to follow a symlink directory during cleanup." }
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun manifestFileSha256(directory: Path): String = PublicationCodec.sha256(readBounded(directory.resolve(MANIFEST_FILENAME), MAX_MANIFEST_BYTES))

    fun relative(path: Path): String {
        ensureOwned(path)
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/')
    }

    private fun writeAtomicFile(target: Path, bytes: ByteArray) {
        ensureOwned(target)
        Files.createDirectories(target.parent)
        val temporary = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            atomicMove(temporary, target, replaceExisting = true)
            syncDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun atomicMove(source: Path, target: Path, replaceExisting: Boolean) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source, target, *options)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw PublicationException("Atomic same-filesystem move is required for publication.", unsupported)
        }
    }

    private fun syncTreeDirectories(rootDirectory: Path): Boolean {
        var complete = true
        val directories = ArrayList<Path>()
        Files.walkFileTree(rootDirectory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symlink directory in staged artifact set." }
                directories.add(dir)
                return FileVisitResult.CONTINUE
            }
        })
        for (directory in directories.asReversed()) complete = syncDirectory(directory) && complete
        return complete
    }

    private fun syncDirectory(directory: Path): Boolean = try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (_: Exception) {
        false
    }

    private fun sha256File(path: Path, maximumBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileInputStream(path.toFile()).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maximumBytes) { "Artifact exceeds its verification byte limit." }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun readBounded(path: Path, maximumBytes: Int): ByteArray {
        ensureOwned(path)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Publication metadata file is missing or unsafe."
        }
        val size = Files.size(path)
        require(size in 1..maximumBytes.toLong()) { "Publication metadata file size is invalid." }
        val bytes = ByteArray(size.toInt())
        FileInputStream(path.toFile()).use { input ->
            var cursor = 0
            while (cursor < bytes.size) {
                val read = input.read(bytes, cursor, bytes.size - cursor)
                require(read > 0) { "Publication metadata file is truncated." }
                cursor += read
            }
            require(input.read() == -1) { "Publication metadata file changed while reading." }
        }
        return bytes
    }

    private fun safeResolve(relativePath: String): Path {
        requireSafeRelativePath(relativePath)
        val resolved = root.resolve(relativePath.replace('/', File.separatorChar)).normalize()
        ensureOwned(resolved)
        return resolved
    }

    private fun resolveInside(directory: Path, relativePath: String): Path {
        requireSafeRelativePath(relativePath)
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        ensureOwned(normalizedDirectory)
        val resolved = normalizedDirectory.resolve(relativePath.replace('/', File.separatorChar)).normalize()
        require(resolved.startsWith(normalizedDirectory) && resolved != normalizedDirectory) { "Artifact path escapes its set root." }
        return resolved
    }

    private fun ensureOwned(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path is outside the publication root." }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Publication root is unavailable or unsafe."
        }
        var cursor = root
        for (component in root.relativize(normalized)) {
            cursor = cursor.resolve(component)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Symlink component is forbidden in publication storage." }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_FILENAME = ".publication-manifest.rfpm"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_MANIFEST_BYTES = 512 * 1024
        const val MAX_JOURNAL_BYTES = 128 * 1024
        const val MAX_JOURNAL_COUNT = 4096
        const val MAX_JOURNAL_TREE_ENTRIES = 8192
        const val MAX_ARTIFACT_TREE_ENTRIES = 8192
        const val MAX_CLEANUP_TREE_ENTRIES = 16384
    }
}
