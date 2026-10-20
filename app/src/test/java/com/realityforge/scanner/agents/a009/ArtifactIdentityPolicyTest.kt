package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.binding
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.hash
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.photo
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.plan
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.snapshot
import com.realityforge.scanner.engine.artifacts.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtifactIdentityPolicyTest {
    @Test fun canonicalSnapshotRoundTripsAndSeparatesContentFromJobOwnership() {
        val original = snapshot()
        assertEquals(original, InputSnapshotCodec.decode(InputSnapshotCodec.encode(original)))
        assertNotEquals(
            original.componentHashes[InputComponent.PHOTOS],
            snapshot(photos = original.selectedPhotos.reversed()).componentHashes[InputComponent.PHOTOS],
        )
        val newOwner = snapshot(binding = binding(revision = 8, attempt = 3))
        assertEquals(original.contentHash, newOwner.contentHash)
        assertNotEquals(original.recordHash, newOwner.recordHash)
        assertNotEquals(
            original.componentHashes[InputComponent.PHOTOS],
            snapshot(photos = listOf(photo("a", "changed"), photo("b"), photo("c")))
                .componentHashes[InputComponent.PHOTOS],
        )
    }

    @Test fun relevantChangesInvalidateExactlyTheirStageAndDescendants() {
        val base = plan()
        val all = PipelineStage.entries.toSet()
        val alignmentOn = setOf(
            PipelineStage.ALIGNMENT,
            PipelineStage.DENSE_DEPTH,
            PipelineStage.SURFACE,
            PipelineStage.TEXTURE,
            PipelineStage.LOD,
            PipelineStage.EXPORT,
        )
        fun assertInvalid(expected: Set<PipelineStage>, changed: ArtifactIdentityPlan) {
            assertEquals(expected, changed.invalidatedComparedTo(base))
        }
        assertInvalid(all, plan(snapshot(photos = listOf(photo("b"), photo("a"), photo("c")))))
        assertInvalid(alignmentOn, plan(snapshot(calibration = hash("new-calibration"))))
        assertInvalid(alignmentOn, plan(snapshot(coordinate = "new-coordinate-convention")))
        assertInvalid(all, plan(snapshot(engine = "new-engine-compatibility")))

        PipelineStage.entries.forEach { changed ->
            val expected = when (changed) {
                PipelineStage.FEATURES -> all
                PipelineStage.MATCHES -> all - PipelineStage.FEATURES
                PipelineStage.ALIGNMENT -> alignmentOn
                PipelineStage.DENSE_DEPTH -> setOf(
                    PipelineStage.DENSE_DEPTH, PipelineStage.SURFACE,
                    PipelineStage.TEXTURE, PipelineStage.LOD, PipelineStage.EXPORT,
                )
                PipelineStage.SURFACE -> setOf(
                    PipelineStage.SURFACE, PipelineStage.TEXTURE,
                    PipelineStage.LOD, PipelineStage.EXPORT,
                )
                PipelineStage.TEXTURE -> setOf(PipelineStage.TEXTURE, PipelineStage.EXPORT)
                PipelineStage.LOD -> setOf(PipelineStage.LOD, PipelineStage.EXPORT)
                PipelineStage.EXPORT -> setOf(PipelineStage.EXPORT)
            }
            assertInvalid(expected, plan(settingChange = changed))
            assertInvalid(expected, plan(algorithmChange = changed))
        }
    }
}
