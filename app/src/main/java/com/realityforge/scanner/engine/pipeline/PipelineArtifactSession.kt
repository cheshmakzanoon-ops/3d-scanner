package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.cachegc.CacheStageManifestStore
import com.realityforge.scanner.data.jobs.JobStageCursor
import com.realityforge.scanner.data.jobs.ReconstructionStage
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Each callback follows synced artifact and manifest writes. It never marks a project result current. */
fun interface PipelineCheckpointSink {
    suspend fun accepted(cursor: JobStageCursor)
    suspend fun started(stage: ReconstructionStage) {}
}

data class PipelineStageFile(val file: File, val representation: RepresentationKind,
    val mediaType: String = "application/octet-stream")

/** The production adapter writes exactly the owner-009 contracts consumed by recovery and owner-020. */
class PipelineArtifactSession(
    val directory: File,
    val pipeline: PipelineInputSnapshot,
    binding: ArtifactBinding,
    private val sink: PipelineCheckpointSink = PipelineCheckpointSink {},
) {
    val plan = identityPlan(pipeline, binding)
    private val manifests = linkedMapOf<ArtifactStage, StageManifest>()

    suspend fun prepared() {
        coroutineContext.ensureActive()
        sink.started(ReconstructionStage.PREPARED)
        val bytes = InputSnapshotCodec.encode(plan.snapshot)
        writeImmutable(inputFile(directory, plan.snapshot.recordHash), bytes)
        sink.accepted(JobStageCursor(ReconstructionStage.PREPARED,
            "input-${plan.snapshot.recordHash.hex}", plan.snapshot.recordHash.hex))
    }

    suspend fun starting(stage: ArtifactStage) { sink.started(durableStage(stage)) }

    suspend fun record(
        stage: ArtifactStage,
        files: List<PipelineStageFile>,
        validatorId: String,
        accepted: Boolean = true,
        evidenceHash: Sha256? = null,
    ): StageManifest {
        coroutineContext.ensureActive()
        val identity = plan[stage]
        val artifacts = files.map { owned ->
            val relative = relativePath(directory, owned.file)
            val size = owned.file.length()
            require(size > 0)
            ArtifactRef(relative, owned.representation, owned.mediaType, size, hashFile(owned.file),
                identity.identityHash, CompletionState.COMPLETE, requiredForReuse = accepted)
        }
        require(artifacts.isNotEmpty())
        val dependencies = identity.parents.map { parent ->
            val manifest = requireNotNull(manifests[parent.stage]) { "Missing accepted parent ${parent.stage}." }
            if (accepted) require(manifest.completion == CompletionState.COMPLETE) {
                "A completed stage cannot depend on an unavailable parent."
            }
            ManifestDependency(parent.stage, parent.identityHash, StageManifestCodec.hash(manifest))
        }
        val evidence = evidenceHash ?: Sha256.digestUtf8(artifacts.joinToString("\n") {
            "${it.relativePath}:${it.byteSize}:${it.contentHash.hex}"
        })
        val manifest = StageManifest(plan.snapshot.binding, plan.snapshot.recordHash, identity,
            if (accepted) CompletionState.COMPLETE else CompletionState.FAILED,
            StageAcceptance(StageManifest.requiredAcceptance(stage), validatorId, "2", evidence, accepted),
            dependencies, artifacts)
        CacheStageManifestStore(directory).record(manifest)
        manifests[stage] = manifest
        sink.accepted(JobStageCursor(durableStage(stage), "manifest-${StageManifestCodec.hash(manifest).hex}",
            StageManifestCodec.hash(manifest).hex))
        return manifest
    }

    /** Rebind an independently verified parent to this exact job/revision/attempt without rewriting its bytes. */
    suspend fun reuse(stage: ArtifactStage, source: StageManifest): StageManifest {
        require(source.identity.identityHash == plan[stage].identityHash)
        require(source.completion == CompletionState.COMPLETE && source.acceptance?.accepted == true)
        source.artifacts.forEach { reference ->
            val probe = ArtifactIntegrity.probe(directory.toPath(), reference)
            require(probe.byteSize == reference.byteSize && probe.contentHash == reference.contentHash)
        }
        return record(stage, source.artifacts.map { reference ->
            PipelineStageFile(File(directory, reference.relativePath), reference.representation, reference.mediaType)
        }, requireNotNull(source.acceptance).validatorId, evidenceHash = source.acceptance.evidenceHash)
    }

    fun manifest(stage: ArtifactStage): StageManifest? = manifests[stage]

    companion object {
        const val INPUT_DIRECTORY = "pipeline-inputs-v1"
        const val MANIFEST_DIRECTORY = "cache-stage-manifests-v1"
        const val MAX_FILE_BYTES = 128L * 1024 * 1024

        fun identityPlan(pipeline: PipelineInputSnapshot, binding: ArtifactBinding): ArtifactIdentityPlan {
            require(binding.projectId == pipeline.projectId && binding.projectRevision == pipeline.revision)
            val input = InputSnapshot(binding, pipeline.photos.map { photo ->
                SelectedPhotoInput(photo.id, Sha256.parse(photo.contentSha256),
                    Sha256.parse(photo.captureMetadataSha256 ?: sha256Utf8("legacy-metadata:${photo.focal35mm}")),
                    photo.width, photo.height)
            }, Sha256.parse(pipeline.calibrationIdentity),
                "x-right/y-down/z-forward;world-to-camera-row-major;arbitrary-scale", pipeline.engineVersion)
            fun version(stage: ArtifactStage): String = when (stage) {
                ArtifactStage.FEATURES -> pipeline.settings.features
                ArtifactStage.MATCHES -> pipeline.settings.matching
                ArtifactStage.ALIGNMENT -> pipeline.settings.sparse
                ArtifactStage.DENSE_DEPTH -> pipeline.settings.dense
                ArtifactStage.SURFACE -> pipeline.settings.surface
                ArtifactStage.TEXTURE -> pipeline.settings.texture
                ArtifactStage.LOD -> "unimplemented-lod-v1"
                ArtifactStage.EXPORT -> pipeline.settings.export
            }
            fun adapter(stage: ArtifactStage): PipelineStage? = when (stage) {
                ArtifactStage.FEATURES -> PipelineStage.FEATURES
                ArtifactStage.MATCHES -> PipelineStage.MATCHING
                ArtifactStage.ALIGNMENT -> PipelineStage.SPARSE
                ArtifactStage.DENSE_DEPTH -> PipelineStage.DENSE
                ArtifactStage.SURFACE -> PipelineStage.SURFACE
                ArtifactStage.TEXTURE -> PipelineStage.TEXTURE
                else -> null
            }
            val algorithms = PipelineAlgorithms(ArtifactStage.entries.associateWith { stage ->
                // This is the exact versioned implementation descriptor. Source checksums are retained
                // separately in the repository's mechanical build manifest.
                AlgorithmSpec("production-${stage.wireId}", "m19-c8-adapter-2",
                    Sha256.digestUtf8("${pipeline.engineVersion}|${version(stage)}"))
            })
            val settings = PipelineSettings(ArtifactStage.entries.associateWith { stage ->
                CanonicalParameters.of(mapOf("settings" to version(stage), "adapter_identity" to
                    (adapter(stage)?.let { pipeline.identity(it).digest } ?: sha256Utf8(version(stage)))))
            })
            return ArtifactIdentityPolicy.build(input, algorithms, settings, setOf(ArtifactStage.ALIGNMENT,
                ArtifactStage.DENSE_DEPTH, ArtifactStage.SURFACE, ArtifactStage.TEXTURE))
        }

        fun durableStage(stage: ArtifactStage): ReconstructionStage = when (stage) {
            ArtifactStage.FEATURES -> ReconstructionStage.FEATURES
            ArtifactStage.MATCHES -> ReconstructionStage.MATCHES
            ArtifactStage.ALIGNMENT -> ReconstructionStage.SPARSE
            ArtifactStage.DENSE_DEPTH -> ReconstructionStage.DENSE
            ArtifactStage.SURFACE -> ReconstructionStage.SURFACE
            ArtifactStage.TEXTURE -> ReconstructionStage.TEXTURE
            ArtifactStage.EXPORT -> ReconstructionStage.PUBLISHING
            ArtifactStage.LOD -> error("No LOD implementation is claimed by this adapter.")
        }

        fun inputFile(root: File, hash: Sha256): File = File(File(root, INPUT_DIRECTORY), "${hash.hex}.rfis")
        fun manifestFile(root: File, hash: Sha256): File = File(File(root, MANIFEST_DIRECTORY), "${hash.hex}.rfsm")

        fun relativePath(root: File, file: File): String {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val target = file.toPath().toAbsolutePath().normalize()
            require(target.startsWith(rootPath) && target != rootPath)
            var cursor = rootPath
            rootPath.relativize(target).forEach { segment ->
                cursor = cursor.resolve(segment); require(!Files.isSymbolicLink(cursor))
            }
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            return rootPath.relativize(target).joinToString("/")
        }

        fun hashFile(file: File): Sha256 = file.inputStream().use { input ->
            val expected = file.length()
            require(expected in 1..MAX_FILE_BYTES) { "Production checkpoint file exceeds its bounded reader." }
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            var observed = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (count > 0) { observed += count; require(observed <= expected); digest.update(buffer, 0, count) }
            }
            require(observed == expected) { "Checkpoint changed while hashing." }
            Sha256.fromBytes(digest.digest())
        }

        fun writeImmutable(target: File, bytes: ByteArray) {
            val directory = requireNotNull(target.parentFile)
            check(directory.isDirectory || directory.mkdirs())
            require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(target.toPath()))
            if (target.exists()) {
                require(target.length() == bytes.size.toLong() && hashFile(target) == Sha256.digest(bytes)) {
                    "An immutable checkpoint path already contains different bytes."
                }
                return
            }
            val temporary = File.createTempFile("checkpoint-", ".partial", directory)
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
    }
}
