package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.ProjectInputSnapshot
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection

/** One production identity factory is shared by execution and independent restart validation. */
object ProductionPipelineSnapshot {
    const val ENGINE_COMPATIBILITY = ProductionEngineCompatibility.VALUE

    /** The persisted job binds exact bounded profile bytes; absent and corrupt are distinct. */
    fun calibrationSha256(file: java.io.File): String {
        val path = file.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return sha256Utf8("pinhole-no-profile-v1")
        require(java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !java.nio.file.Files.isSymbolicLink(path)) { "The optical profile is not a regular owned file." }
        require(file.length() in 30L..com.realityforge.scanner.capture.calibration.CalibrationProfileCodec.MAX_PROFILE_BYTES.toLong()) {
            "The optical profile exceeds its bounded format."
        }
        return PipelineArtifactSession.hashFile(file).hex
    }

    fun create(input: ProjectInputSnapshot, calibrationSha256: String, assumedFocal35: Double,
        resources: ResourceWorkloadSelection,
        executionProfile: DensePreviewProfile = DensePreviewProfile.Compatibility): PipelineInputSnapshot = PipelineInputSnapshot(
        projectId = input.projectId,
        revision = input.revision,
        photos = input.photos.map { photo -> PipelinePhotoInput(photo.id, photo.sha256, photo.width,
            photo.height, photo.bytes, photo.focal35mm, photo.metadataSha256) },
        calibrationIdentity = calibrationSha256,
        engineVersion = ENGINE_COMPATIBILITY,
        settings = PipelineStageSettings(
            features = com.realityforge.scanner.engine.features.SparseFeatureCache.STAGE_SETTINGS,
            matching = "rf-bf-l2-reciprocal-v2|ratio-.67|opencv-4.12.0|summary-ranked-exhaustive-admitted12-v1|pair-cache-v3|ray-focal35=$assumedFocal35",
            sparse = "incremental-frontier-v1|complete-source-window-typed-ba-frontier-admitted12-v4|${com.realityforge.scanner.engine.CalibratedInitializerInput.POLICY}|assumed-focal35=$assumedFocal35",
            dense = "native-reference-depth-v4|observed-alternative-uniqueness-v2|observed-peak-support-v3|${VerifiedDenseAlignment.POLICY}|${DenseExecutionPolicy.depth(executionProfile)}|planes-56|resource=${resources.geometryIdentityText}",
            surface = "${DenseExecutionPolicy.SURFACE_ENGINE}|quality-gated",
            texture = "${DenseExecutionPolicy.ATLAS_ENGINE}|${DenseExecutionPolicy.texture(executionProfile)}|resource=${resources.appearanceIdentityText}",
            export = "diagnostic-sparse-and-dense-glb-ply-v1",
        ),
    )
}
