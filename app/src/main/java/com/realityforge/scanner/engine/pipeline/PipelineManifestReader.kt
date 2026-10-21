package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import java.io.File
import java.nio.file.Files

/** Bounded recursive validation of the exact dependency records named by a durable manifest. */
class PipelineManifestReader(private val root: File) {
    fun input(hash: Sha256): InputSnapshot {
        val file = PipelineArtifactSession.inputFile(root, hash)
        val bytes = readBounded(file, 2 * 1024 * 1024)
        require(Sha256.digest(bytes) == hash)
        return InputSnapshotCodec.decode(bytes).also { require(it.recordHash == hash) }
    }

    fun manifest(hash: Sha256): StageManifest {
        val bytes = readBounded(PipelineArtifactSession.manifestFile(root, hash), StageManifestCodec.MAX_MANIFEST_BYTES)
        require(Sha256.digest(bytes) == hash)
        return StageManifestCodec.decode(bytes)
    }

    fun verify(manifest: StageManifest, allowUnavailable: Boolean = false): Map<ArtifactStage, StageManifest> {
        val snapshot = input(manifest.inputSnapshotRecordHash)
        require(snapshot.binding == manifest.binding && snapshot.binding.projectId == root.name)
        val found = linkedMapOf<ArtifactStage, StageManifest>()
        fun visit(current: StageManifest, depth: Int) {
            require(depth <= ArtifactStage.entries.size)
            require(current.binding == snapshot.binding && current.inputSnapshotRecordHash == snapshot.recordHash)
            if (found.containsKey(current.identity.stage)) {
                require(found[current.identity.stage] == current); return
            }
            val parents = current.dependencies.associate { dependency ->
                val parent = this@PipelineManifestReader.manifest(dependency.manifestHash)
                require(parent.identity.stage == dependency.stage && parent.identity.identityHash == dependency.stageIdentityHash)
                visit(parent, depth + 1)
                dependency.stage to parent
            }
            val probes = current.artifacts.associate { reference ->
                require(reference.byteSize in 1..PipelineArtifactSession.MAX_FILE_BYTES)
                reference.relativePath to ArtifactIntegrity.probe(root.toPath(), reference).also {
                    require(it.byteSize == reference.byteSize && it.contentHash == reference.contentHash)
                }
            }
            if (current.completion == CompletionState.COMPLETE) {
                StageManifestValidator.validateForReuse(current, snapshot.binding, snapshot.recordHash,
                    current.identity, parents, probes).requireReusable()
            } else require(allowUnavailable && current.completion == CompletionState.FAILED && current.acceptance?.accepted == false)
            found[current.identity.stage] = current
        }
        visit(manifest, 0)
        return found
    }

    fun readBounded(file: File, maximum: Int): ByteArray {
        PipelineArtifactSession.relativePath(root, file)
        require(!Files.isSymbolicLink(file.toPath()) && file.length() in 1..maximum.toLong())
        val expected = file.length()
        return file.inputStream().use { input ->
            val bytes = ByteArray(expected.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                require(count > 0) { "Checkpoint truncated while reading." }; offset += count
            }
            require(input.read() == -1) { "Checkpoint grew while reading." }
            bytes
        }
    }
}
