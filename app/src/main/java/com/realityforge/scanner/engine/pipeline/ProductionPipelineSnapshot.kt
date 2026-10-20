package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.ProjectInputSnapshot
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection

/** One production identity factory is shared by execution and independent restart validation. */
object ProductionPipelineSnapshot {
    const val ENGINE_COMPATIBILITY = "m19-c8-production-2|m16-registration-frontier-1|m18-depth-checkpoint-1"

    fun create(input: ProjectInputSnapshot, calibrationSha256: String, assumedFocal35: Double,
        resources: ResourceWorkloadSelection): PipelineInputSnapshot = PipelineInputSnapshot(
        projectId = input.projectId,
        revision = input.revision,
        photos = input.photos.map { photo -> PipelinePhotoInput(photo.id, photo.sha256, photo.width,
            photo.height, photo.bytes, photo.focal35mm, photo.metadataSha256) },
        calibrationIdentity = calibrationSha256,
        engineVersion = ENGINE_COMPATIBILITY,
        settings = PipelineStageSettings(
            features = "opencv-4.12.0|sift-3500|edge-1600|cache-v2-sha",
            matching = "bf-l2|reciprocal-ratio-.67|gap-3|candidate-cap-24|ray-focal35=$assumedFocal35",
            sparse = "incremental-frontier-v1|joint-ba-v1|assumed-focal35=$assumedFocal35",
            dense = "native-reference-depth-v1|planes-56|resource=${resources.geometryIdentityText}",
            surface = "observed-open-mesh-v1|quality-gated",
            texture = "photographic-atlas-v1|resource=${resources.appearanceIdentityText}",
            export = "diagnostic-sparse-and-dense-glb-ply-v1",
        ),
    )
}
