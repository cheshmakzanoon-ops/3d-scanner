package com.realityforge.scanner.export

import com.realityforge.scanner.engine.artifacts.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** A published023 lease, never a directory scan. The producer must atomically fence current ownership. */
fun interface ManifestExportLeaseProvider {
    fun current(projectId: String): ManifestExportLease?
}

/** Copies wire records defensively; expected inputs come from the admitted job, not from its output. */
class ManifestExportLease(
    val publicationId: String,
    val projectRoot: File,
    val expectedBinding: ArtifactBinding,
    snapshot: InputSnapshot,
    expectedIdentity: StageIdentity,
    encodedManifest: ByteArray,
    val publishedManifestHash: Sha256,
    acceptedParentManifests: Map<PipelineStage, ByteArray> = emptyMap(),
) {
    private val expectedStage = copyIdentity(expectedIdentity)
    val expectedIdentity: StageIdentity get() = copyIdentity(expectedStage)
    private val snapshotBytes = InputSnapshotCodec.encode(snapshot)
    private val manifestBytes = encodedManifest.copyOf()
    private val parents = acceptedParentManifests.mapValues { it.value.copyOf() }
    init {
        require(publicationId.isNotBlank() && publicationId.length <= 180)
        require(snapshot.binding == expectedBinding)
        require(manifestBytes.size <= StageManifestCodec.MAX_MANIFEST_BYTES + 128)
        require(parents.size <= PipelineStage.entries.size)
        require(parents.values.all { it.size <= StageManifestCodec.MAX_MANIFEST_BYTES + 128 })
    }
    private fun copyIdentity(value: StageIdentity) = StageIdentity(value.stage, value.algorithm,
        CanonicalParameters.of(value.parameters.entries), value.inputComponents, value.parents)
    internal fun snapshot(): InputSnapshot = InputSnapshotCodec.decode(snapshotBytes)
    internal fun manifest(): ByteArray = manifestBytes.copyOf()
    internal fun parents(): Map<PipelineStage, ByteArray> = parents.mapValues { it.value.copyOf() }
}

/** Validators inspect actual bytes; registration alone never makes an unknown representation exportable. */
data class ManifestExportPair(val representation: RepresentationKind, val mediaType: String)
fun interface ManifestArtifactValidator {
    fun validate(reference: ArtifactRef, file: File, checkpoint: () -> Unit)
}

/** Copy-only adapter for accepted C8 artifacts. It does not manufacture formats or infer geometry from paths. */
class ManifestExportSource(
    private val leases: ManifestExportLeaseProvider,
    validators: Map<ManifestExportPair, ManifestArtifactValidator>,
    private val maxBytes: Long = 512L * 1024 * 1024,
) : ExportSource {
    private val validators = validators.toMap()
    init { require(maxBytes > 0) }

    /** Snapshot hook for a complete CanonicalExportServices provider installed by001. */
    fun identity(projectId: String): ArtifactIdentity? = safely { checked(projectId).identity }
    override suspend fun isCurrent(identity: ArtifactIdentity): Boolean = safely { current(identity); true } ?: false
    override suspend fun available(identity: ArtifactIdentity): List<ExportOption> = safely {
        val current = current(identity)
        current.manifest.artifacts.mapNotNull { reference ->
            val pair = ManifestExportPair(reference.representation, reference.mediaType)
            val format = supported(pair) ?: return@mapNotNull null
            val validator = validators[pair] ?: return@mapNotNull null
            if (reference.completion != CompletionState.COMPLETE || reference.byteSize !in 1..maxBytes) return@mapNotNull null
            val file = checkedFile(current.lease.projectRoot, reference)
            verifyProbe(current.lease.projectRoot, reference)
            validateBytes(reference, validator, file) {}
            option(reference, format)
        }.also { require(it.map(ExportOption::id).distinct().size == it.size) }
    } ?: emptyList()

    override fun materialize(request: ExportRequest, target: File, checkpoint: () -> Unit) {
        var ownsTarget = false
        try {
            checkpoint()
            val current = current(request.artifact)
            val (reference, validator) = selected(current, request)
            val root = checkedRoot(current.lease.projectRoot)
            val destination = target.toPath().toAbsolutePath().normalize()
            require(!destination.startsWith(root)) { "UNSAFE_EXPORT_TARGET" }
            require(destination.parent.toRealPath() == destination.parent && !Files.isSymbolicLink(destination)) { "UNSAFE_EXPORT_TARGET" }
            if (Files.exists(destination, NOFOLLOW_LINKS)) require(Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.size(destination) == 0L) { "EXPORT_TARGET_NOT_EMPTY" }
            val input = checkedFile(current.lease.projectRoot, reference)
            ownsTarget = true
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(input.toPath(), NOFOLLOW_LINKS).use { source ->
                Files.newOutputStream(destination, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkpoint()
                        val n = source.read(buffer)
                        if (n < 0) break
                        require(n > 0 && count <= reference.byteSize - n && count <= maxBytes - n) { "ARTIFACT_BYTES_CHANGED" }
                        output.write(buffer, 0, n)
                        hash.update(buffer, 0, n)
                        count += n
                    }
                }
            }
            require(count == reference.byteSize && Sha256.fromBytes(hash.digest()) == reference.contentHash) { "ARTIFACT_BYTES_CHANGED" }
            checkpoint()
            validateBytes(reference, validator, target, checkpoint)
            current(request.artifact)
            checkpoint()
        } catch (failure: Exception) {
            if (ownsTarget) target.delete()
            throw failure
        }
    }

    override fun validate(request: ExportRequest, file: File) {
        val current = current(request.artifact)
        val (reference, validator) = selected(current, request)
        validateBytes(reference, validator, file) {}
        current(request.artifact)
    }

    private data class Checked(val lease: ManifestExportLease, val manifest: StageManifest, val identity: ArtifactIdentity)
    private fun current(identity: ArtifactIdentity): Checked = checked(identity.projectId).also {
        check(it.identity == identity) { "STALE_ARTIFACT" }
    }
    private fun checked(projectId: String): Checked {
        val lease = checkNotNull(leases.current(projectId)) { "STALE_ARTIFACT" }
        require(lease.expectedBinding.projectId == projectId) { "STALE_ARTIFACT" }
        checkedRoot(lease.projectRoot)
        val wire = lease.manifest()
        require(Sha256.digest(wire) == lease.publishedManifestHash) { "MANIFEST_BYTES_CHANGED" }
        val manifest = decodeCanonical(wire)
        val snapshot = lease.snapshot()
        val parents = lease.parents().mapValues { (stage, bytes) ->
            decodeCanonical(bytes).also { require(it.identity.stage == stage) }
        }
        var checkedBytes = 0L
        val checkedStages = mutableSetOf<PipelineStage>()
        fun validate(stageManifest: StageManifest) {
            require(checkedStages.add(stageManifest.identity.stage)) { "MANIFEST_DEPENDENCY_CYCLE" }
            stageManifest.dependencies.forEach { dependency ->
                val parent = requireNotNull(parents[dependency.stage]) { "MANIFEST_PARENT_MISSING" }
                if (parent.identity.stage !in checkedStages) validate(parent)
            }
            val probes = stageManifest.artifacts.filter { it.requiredForReuse }.associate { reference ->
                require(reference.byteSize <= maxBytes - checkedBytes) { "ARTIFACT_LIMIT" }
                checkedBytes += reference.byteSize
                reference.relativePath to verifyProbe(lease.projectRoot, reference)
            }
            StageManifestValidator.validateForReuse(stageManifest, lease.expectedBinding, snapshot.recordHash,
                if (stageManifest === manifest) lease.expectedIdentity else stageManifest.identity, parents, probes).requireReusable()
        }
        validate(manifest)
        val publicationHash = Sha256.digestUtf8(lease.publicationId).hex.take(16)
        return Checked(lease, manifest, ArtifactIdentity(projectId, lease.expectedBinding.projectRevision,
            "c8:${manifest.identity.identityHash.hex}:$publicationHash", lease.publishedManifestHash.hex))
    }
    private fun selected(current: Checked, request: ExportRequest): Pair<ArtifactRef, ManifestArtifactValidator> {
        require(request.options.editIdentity == null && request.options.lodIdentity == null) { "OPTION_UNAVAILABLE" }
        val reference = requireNotNull(current.manifest.artifacts.singleOrNull { id(it) == request.option.id }) { "OPTION_UNAVAILABLE" }
        val pair = ManifestExportPair(reference.representation, reference.mediaType)
        val format = requireNotNull(supported(pair)) { "OPTION_UNAVAILABLE" }
        val validator = requireNotNull(validators[pair]) { "OPTION_UNAVAILABLE" }
        require(reference.completion == CompletionState.COMPLETE && reference.byteSize in 1..maxBytes && request.option == option(reference, format)) { "OPTION_UNAVAILABLE" }
        return reference to validator
    }
    private fun validateBytes(reference: ArtifactRef, validator: ManifestArtifactValidator, file: File, checkpoint: () -> Unit) {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == reference.byteSize && file.length() <= maxBytes) { "INVALID_SIZE" }
        require(ExportCoordinator.digest(file, checkpoint) == reference.contentHash.hex) { "ARTIFACT_BYTES_CHANGED" }
        validator.validate(reference, file, checkpoint)
        checkpoint()
        require(ExportCoordinator.digest(file, checkpoint) == reference.contentHash.hex) { "ARTIFACT_BYTES_CHANGED" }
    }
    private fun verifyProbe(root: File, reference: ArtifactRef): ArtifactProbe {
        require(reference.byteSize in 1..maxBytes) { "ARTIFACT_LIMIT" }
        checkedFile(root, reference)
        return ArtifactIntegrity.probe(root.toPath(), reference).also {
            require(it.byteSize == reference.byteSize && it.contentHash == reference.contentHash) { "ARTIFACT_BYTES_CHANGED" }
        }
    }
    private fun option(reference: ArtifactRef, format: ExportFormat): ExportOption {
        val representation = when (reference.representation) {
            RepresentationKind.SPARSE_POINT_CLOUD -> AssetRepresentation.SPARSE_POINTS
            RepresentationKind.DENSE_POINT_CLOUD -> AssetRepresentation.DENSE_POINTS
            RepresentationKind.TRIANGLE_MESH -> AssetRepresentation.OBSERVED_SURFACE
            RepresentationKind.TEXTURED_MESH -> AssetRepresentation.PHOTOGRAPHIC_SURFACE
            RepresentationKind.TEXTURE_ATLAS -> AssetRepresentation.PHOTOGRAPHIC_ATLAS
            RepresentationKind.DIAGNOSTIC_REPORT -> AssetRepresentation.DIAGNOSTICS
            else -> error("OPTION_UNAVAILABLE")
        }
        return ExportOption(id(reference), format, representation, reference.byteSize, arbitraryScale = true,
            photographic = representation in setOf(AssetRepresentation.PHOTOGRAPHIC_SURFACE, AssetRepresentation.PHOTOGRAPHIC_ATLAS), contentSha256 = reference.contentHash.hex)
    }
    private fun id(reference: ArtifactRef): String = "c8." + Sha256.digestUtf8(reference.relativePath).hex
    private fun supported(pair: ManifestExportPair): ExportFormat? = when {
        pair.mediaType == "model/gltf-binary" && pair.representation in setOf(RepresentationKind.SPARSE_POINT_CLOUD, RepresentationKind.DENSE_POINT_CLOUD, RepresentationKind.TRIANGLE_MESH, RepresentationKind.TEXTURED_MESH) -> ExportFormat.GLB
        pair.mediaType in setOf("application/ply", "application/x-ply", "application/octet-stream") && pair.representation in setOf(RepresentationKind.SPARSE_POINT_CLOUD, RepresentationKind.DENSE_POINT_CLOUD, RepresentationKind.TRIANGLE_MESH) -> ExportFormat.PLY
        pair.mediaType == "model/stl" && pair.representation == RepresentationKind.TRIANGLE_MESH -> ExportFormat.STL
        pair.mediaType == "image/png" && pair.representation == RepresentationKind.TEXTURE_ATLAS -> ExportFormat.PNG
        pair.mediaType == "application/json" && pair.representation == RepresentationKind.DIAGNOSTIC_REPORT -> ExportFormat.JSON
        else -> null
    }
    private fun decodeCanonical(bytes: ByteArray): StageManifest = StageManifestCodec.decode(bytes).also {
        require(StageManifestCodec.encode(it).contentEquals(bytes)) { "NON_CANONICAL_MANIFEST" }
    }
    private fun checkedRoot(root: File): java.nio.file.Path = root.toPath().toAbsolutePath().normalize().also {
        require(Files.isDirectory(it, NOFOLLOW_LINKS) && it.toRealPath() == it) { "UNSAFE_ARTIFACT_ROOT" }
    }
    private fun checkedFile(root: File, reference: ArtifactRef): File {
        val path = checkedRoot(root)
        val resolved = path.resolve(reference.relativePath).normalize()
        require(resolved.startsWith(path) && resolved != path) { "UNSAFE_ARTIFACT_PATH" }
        var cursor = path
        for (part in path.relativize(resolved)) { cursor = cursor.resolve(part); require(!Files.isSymbolicLink(cursor)) { "UNSAFE_ARTIFACT_PATH" } }
        require(Files.isRegularFile(resolved, NOFOLLOW_LINKS) && resolved.toRealPath() == resolved) { "UNSAFE_ARTIFACT_PATH" }
        return resolved.toFile()
    }
    private inline fun <T> safely(block: () -> T): T? = try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
}
