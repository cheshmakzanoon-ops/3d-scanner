package com.realityforge.scanner.engine.pipeline

import org.junit.Assert.*
import org.junit.Test

/** Portable production identity regressions; native byte migration is tested by CTest. */
class SurfaceCacheIdentityTest {
    @Test fun threeCornerPolicyPreservesParentIdentitiesAndInvalidatesSurfaceAndTexture() {
        for (profile in DensePreviewProfile.entries) {
            val current = PipelineInputSnapshot("synthetic-surface-migration", 1, listOf(
                PipelinePhotoInput("photo-a", sha256Utf8("a"), 104, 76, 1000, 26.0),
                PipelinePhotoInput("photo-b", sha256Utf8("b"), 104, 76, 1000, 26.0)),
                sha256Utf8("calibration"), "unchanged-engine",
                PipelineStageSettings("features", "matching", "sparse", DenseExecutionPolicy.depth(profile),
                    DenseExecutionPolicy.SURFACE_ENGINE, DenseExecutionPolicy.texture(profile), "export"))
            val previous = current.copy(settings = current.settings.copy(
                surface = "rf-observed-open-mesh-v8-exact-cleanup-provenance"))
            for (stage in PipelineStage.entries) {
                if (stage.ordinal <= PipelineStage.DENSE.ordinal)
                    assertEquals(previous.identity(stage), current.identity(stage))
                else assertNotEquals(previous.identity(stage), current.identity(stage))
            }
            assertEquals(current.identity(PipelineStage.SURFACE).digest,
                current.identity(PipelineStage.TEXTURE).parentDigest)
        }
    }

    @Test fun threeCornerNamespaceChangesWithoutChangingWireFormat() {
        assertEquals("rf-observed-open-mesh-v9-three-observed-corners", DenseExecutionPolicy.SURFACE_ENGINE)
        assertEquals("surface-v3-fusion-v9.rfmesh", DenseExecutionPolicy.SURFACE_CHECKPOINT_FILENAME)
        assertNotEquals("surface-v3-fusion-v8.rfmesh", DenseExecutionPolicy.SURFACE_CHECKPOINT_FILENAME)
    }
}
