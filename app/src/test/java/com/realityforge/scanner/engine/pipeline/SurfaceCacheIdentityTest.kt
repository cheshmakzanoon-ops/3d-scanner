package com.realityforge.scanner.engine.pipeline

import org.junit.Assert.*
import org.junit.Test

/** Portable production identity regressions; native byte migration is tested by CTest. */
class SurfaceCacheIdentityTest {
    @Test fun topologyAliasPolicyPreservesParentIdentitiesAndInvalidatesSurfaceAndTexture() {
        for (profile in DensePreviewProfile.entries) {
            val current = PipelineInputSnapshot("synthetic-surface-migration", 1, listOf(
                PipelinePhotoInput("photo-a", sha256Utf8("a"), 104, 76, 1000, 26.0),
                PipelinePhotoInput("photo-b", sha256Utf8("b"), 104, 76, 1000, 26.0)),
                sha256Utf8("calibration"), "unchanged-engine",
                PipelineStageSettings("features", "matching", "sparse", DenseExecutionPolicy.depth(profile),
                    DenseExecutionPolicy.SURFACE_ENGINE, DenseExecutionPolicy.texture(profile), "export"))
            val previous = current.copy(settings = current.settings.copy(
                surface = "rf-observed-open-mesh-v12-topology-safe-weld-admission"))
            for (stage in PipelineStage.entries) {
                if (stage.ordinal <= PipelineStage.DENSE.ordinal)
                    assertEquals(previous.identity(stage), current.identity(stage))
                else {
                    assertNotEquals(previous.identity(stage), current.identity(stage))
                    val oldIdentity = previous.identity(stage)
                    val oldArtifact = PipelineArtifactReference.forStage(oldIdentity,
                        "previous-${stage.name}.bin", contentSha256 = "a".repeat(64))
                    val checkpoint = PipelineCheckpoint(oldIdentity, oldArtifact)
                    assertFalse(checkpoint.isReusable(current.identity(stage)))
                    assertThrows(IllegalArgumentException::class.java) {
                        checkpoint.rebound(current.identity(stage))
                    }
                }
            }
            assertEquals(current.identity(PipelineStage.SURFACE).digest,
                current.identity(PipelineStage.TEXTURE).parentDigest)
        }
    }

    @Test fun topologyAliasNamespaceAndWireFormatChange() {
        assertEquals("rf-observed-open-mesh-v13-provenance-topology-alias", DenseExecutionPolicy.SURFACE_ENGINE)
        assertEquals("surface-v4-fusion-v13.rfmesh", DenseExecutionPolicy.SURFACE_CHECKPOINT_FILENAME)
        assertNotEquals("surface-v3-fusion-v12.rfmesh", DenseExecutionPolicy.SURFACE_CHECKPOINT_FILENAME)
    }
    @Test fun projectiveRefinementInvalidatesDenseAndDescendantsButPreservesSparse() {
        val current = PipelineInputSnapshot("synthetic-depth-migration", 1, listOf(
            PipelinePhotoInput("photo-a", sha256Utf8("a"), 104, 76, 1000, 26.0),
            PipelinePhotoInput("photo-b", sha256Utf8("b"), 104, 76, 1000, 26.0)),
            sha256Utf8("calibration"), "unchanged-engine",
            PipelineStageSettings("features", "matching", "sparse",
                DenseExecutionPolicy.depth(DensePreviewProfile.VerifiedRefinedCompact),
                "same-surface", "same-texture", "same-export"))
        val previous = current.copy(settings = current.settings.copy(
            dense = "cpu-refined-v4|local6|normal3|strict1|normalConsistency1|angle35|robustSourceAggregation=false"))
        for (stage in PipelineStage.entries) {
            if (stage.ordinal < PipelineStage.DENSE.ordinal)
                assertEquals(previous.identity(stage), current.identity(stage))
            else assertNotEquals(previous.identity(stage), current.identity(stage))
        }
    }

}
