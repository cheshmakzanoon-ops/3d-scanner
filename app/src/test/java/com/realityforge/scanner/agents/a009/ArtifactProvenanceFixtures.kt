package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.engine.artifacts.*

internal object ArtifactProvenanceFixtures {
    fun hash(value: String): Sha256 = Sha256.digestUtf8(value)
    fun binding(revision: Long = 7, attempt: Long = 2) = ArtifactBinding("project-9", revision, "job-9", attempt)
    fun photo(id: String, suffix: String = id) = SelectedPhotoInput(
        id, hash("photo-$suffix"), hash("meta-$suffix"), 4032, 3024,
    )
    fun snapshot(
        binding: ArtifactBinding = binding(),
        photos: List<SelectedPhotoInput> = listOf(photo("a"), photo("b"), photo("c")),
        calibration: Sha256 = hash("calibration"),
        coordinate: String = "x-right/y-down/z-forward;world-to-camera-row-major;arbitrary-scale",
        engine: String = "rf-engine-0.19.0-m19",
    ) = InputSnapshot(binding, photos, calibration, coordinate, engine)

    fun algorithms(changed: PipelineStage? = null) = PipelineAlgorithms(
        PipelineStage.entries.associateWith { stage ->
            val suffix = if (stage == changed) 2 else 1
            AlgorithmSpec("rf-${stage.wireId}", suffix.toString(), hash("implementation-${stage.wireId}-$suffix"))
        },
    )

    fun settings(changed: PipelineStage? = null) = PipelineSettings(
        PipelineStage.entries.associateWith { stage ->
            CanonicalParameters.of(mapOf("profile" to if (stage == changed) "changed" else "baseline"))
        },
    )

    fun plan(
        snapshot: InputSnapshot = snapshot(),
        algorithmChange: PipelineStage? = null,
        settingChange: PipelineStage? = null,
    ) = ArtifactIdentityPolicy.build(snapshot, algorithms(algorithmChange), settings(settingChange))

    fun acceptance(kind: AcceptanceKind) = StageAcceptance(
        kind, "rf-validator", "1", hash("evidence-$kind"), true,
    )

    private fun representation(stage: PipelineStage) = when (stage) {
        PipelineStage.FEATURES -> RepresentationKind.FEATURE_SET
        PipelineStage.MATCHES -> RepresentationKind.MATCH_GRAPH
        PipelineStage.ALIGNMENT -> RepresentationKind.CAMERA_ALIGNMENT
        PipelineStage.DENSE_DEPTH -> RepresentationKind.DEPTH_MAP_SET
        PipelineStage.SURFACE -> RepresentationKind.TRIANGLE_MESH
        PipelineStage.TEXTURE -> RepresentationKind.TEXTURED_MESH
        PipelineStage.LOD -> RepresentationKind.LOD_SET
        PipelineStage.EXPORT -> RepresentationKind.EXPORT_PACKAGE
    }

    fun manifest(
        stage: PipelineStage,
        plan: ArtifactIdentityPlan,
        parents: Map<PipelineStage, StageManifest> = emptyMap(),
        binding: ArtifactBinding = plan.snapshot.binding,
    ): StageManifest {
        val identity = plan[stage]
        val dependencies = identity.parents.map { parent ->
            val parentManifest = requireNotNull(parents[parent.stage])
            ManifestDependency(parent.stage, parent.identityHash, StageManifestCodec.hash(parentManifest))
        }
        val bytes = "artifact-${stage.wireId}".toByteArray()
        return StageManifest(
            binding = binding,
            inputSnapshotRecordHash = plan.snapshot.recordHash,
            identity = identity,
            completion = CompletionState.COMPLETE,
            acceptance = acceptance(StageManifest.requiredAcceptance(stage)),
            dependencies = dependencies,
            artifacts = listOf(
                ArtifactRef(
                    "artifacts/${stage.wireId}.bin",
                    representation(stage),
                    "application/octet-stream",
                    bytes.size.toLong(),
                    Sha256.digest(bytes),
                    identity.identityHash,
                    CompletionState.COMPLETE,
                ),
            ),
        )
    }

    fun manifests(plan: ArtifactIdentityPlan): Map<PipelineStage, StageManifest> {
        val result = linkedMapOf<PipelineStage, StageManifest>()
        PipelineStage.entries.forEach { stage -> result[stage] = manifest(stage, plan, result) }
        return result
    }

    fun checkpoint(
        plan: ArtifactIdentityPlan = plan(),
        invalidRotation: Boolean = false,
        rms: Double = .001,
    ): AlignmentCheckpoint {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val cameras = listOf(
            AlignmentCamera.of(
                "a", 640, 480, 600.0, 600.0, 319.5, 239.5,
                if (invalidRotation) DoubleArray(9) else identity,
                doubleArrayOf(0.0, 0.0, 0.0),
            ),
            AlignmentCamera.of(
                "b", 640, 480, 600.0, 600.0, 319.5, 239.5,
                identity, doubleArrayOf(-.5, 0.0, 0.0),
            ),
        )
        val tracks = (0 until 24).map { index ->
            SparseTrack(
                "track-$index", index * .01, (index % 5) * .02, 3.0 + index * .001,
                90, 120, 150, rms,
                listOf(
                    TrackObservation(0, index, index * .001, index * .002),
                    TrackObservation(1, index, index * .001 - .01, index * .002),
                ),
            )
        }
        return AlignmentCheckpoint(
            plan.snapshot.binding,
            plan.snapshot.recordHash,
            plan[PipelineStage.ALIGNMENT],
            acceptance(AcceptanceKind.GEOMETRY),
            cameras,
            listOf(VerifiedMatchEdge(0, 1, 40, 32, .001)),
            tracks,
        )
    }
}
