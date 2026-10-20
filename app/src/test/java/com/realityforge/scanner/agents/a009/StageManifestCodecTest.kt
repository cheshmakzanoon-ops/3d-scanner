package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.acceptance
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.hash
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.manifests
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.plan
import com.realityforge.scanner.engine.artifacts.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StageManifestCodecTest {
    @Test fun completedManifestRoundTripsAndValidatesActualParentsAndBytes() {
        val plan = plan()
        val all = manifests(plan)
        val manifest = requireNotNull(all[PipelineStage.TEXTURE])
        assertEquals(manifest, StageManifestCodec.decode(StageManifestCodec.encode(manifest)))
        val parents = manifest.dependencies.associate { it.stage to requireNotNull(all[it.stage]) }
        val artifacts = manifest.artifacts.associate {
            it.relativePath to ArtifactProbe(it.byteSize, it.contentHash)
        }
        assertTrue(
            StageManifestValidator.validateForReuse(
                manifest, plan.snapshot.binding, plan.snapshot.recordHash,
                plan[PipelineStage.TEXTURE], parents, artifacts,
            ).reusable,
        )
        val wrong = artifacts.mapValues { ArtifactProbe(it.value.byteSize, hash("wrong")) }
        assertTrue(
            ManifestRejection.ARTIFACT_HASH_MISMATCH in StageManifestValidator.validateForReuse(
                manifest, plan.snapshot.binding, plan.snapshot.recordHash,
                plan[PipelineStage.TEXTURE], parents, wrong,
            ).rejections,
        )
    }

    @Test fun truncationVersionLengthHashPathAndRepresentationAttacksAreRejected() {
        val plan = plan()
        val encoded = StageManifestCodec.encode(requireNotNull(manifests(plan)[PipelineStage.TEXTURE]))
        for (cut in 0 until encoded.size) {
            assertThrows(ArtifactFormatException::class.java) {
                StageManifestCodec.decode(encoded.copyOf(cut))
            }
        }
        val badVersion = encoded.copyOf().also { it[7] = 2 }
        assertThrows(ArtifactFormatException::class.java) { StageManifestCodec.decode(badVersion) }
        val hugeLength = encoded.copyOf().also {
            it[8] = 0x7f
            it[9] = 0xff.toByte()
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
        }
        assertThrows(ArtifactFormatException::class.java) { StageManifestCodec.decode(hugeLength) }
        val corrupt = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(ArtifactFormatException::class.java) { StageManifestCodec.decode(corrupt) }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactRef(
                "../escape", RepresentationKind.FEATURE_SET, "application/octet-stream", 1,
                hash("x"), plan[PipelineStage.FEATURES].identityHash, CompletionState.COMPLETE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StageManifest(
                plan.snapshot.binding,
                plan.snapshot.recordHash,
                plan[PipelineStage.FEATURES],
                CompletionState.COMPLETE,
                acceptance(AcceptanceKind.STRUCTURAL),
                emptyList(),
                listOf(
                    ArtifactRef(
                        "bad.bin", RepresentationKind.TRIANGLE_MESH, "application/octet-stream", 1,
                        hash("x"), plan[PipelineStage.FEATURES].identityHash, CompletionState.COMPLETE,
                    ),
                ),
            )
        }
    }

    @Test fun integrityProbeStreamsARegularFileAndDetectsReplacement() {
        val plan = plan()
        val root = Files.createTempDirectory("rf-artifact-probe")
        try {
            Files.createDirectories(root.resolve("artifacts"))
            val bytes = "verified artifact".toByteArray()
            val target = root.resolve("artifacts/file.bin")
            Files.write(target, bytes)
            val reference = ArtifactRef(
                "artifacts/file.bin", RepresentationKind.FEATURE_SET, "application/octet-stream",
                bytes.size.toLong(), Sha256.digest(bytes),
                plan[PipelineStage.FEATURES].identityHash, CompletionState.COMPLETE,
            )
            assertEquals(
                ArtifactProbe(bytes.size.toLong(), Sha256.digest(bytes)),
                ArtifactIntegrity.probe(root, reference),
            )
            Files.write(target, "wrong".toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactIntegrity.probe(root, reference)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
