package com.realityforge.scanner.engine.artifacts

/** Per-stage algorithm provenance. All production stages must be explicitly versioned. */
class PipelineAlgorithms(values: Map<PipelineStage, AlgorithmSpec>) {
    val values: Map<PipelineStage, AlgorithmSpec> = values.toMap()
    init {
        require(this.values.keys == PipelineStage.entries.toSet()) {
            "Algorithms must be supplied for every pipeline stage"
        }
    }

    operator fun get(stage: PipelineStage): AlgorithmSpec = requireNotNull(values[stage])
}

/** Per-stage settings. A change affects the stage and only its descendants. */
class PipelineSettings(values: Map<PipelineStage, CanonicalParameters>) {
    val values: Map<PipelineStage, CanonicalParameters> = values.toMap()
    init {
        require(this.values.keys == PipelineStage.entries.toSet()) {
            "Settings must be supplied for every pipeline stage"
        }
    }

    operator fun get(stage: PipelineStage): CanonicalParameters = requireNotNull(values[stage])
}

class ArtifactIdentityPlan(
    val snapshot: InputSnapshot,
    identities: Map<PipelineStage, StageIdentity>,
    exportSources: Set<PipelineStage>,
) {
    val identities: Map<PipelineStage, StageIdentity> = identities.toMap()
    val exportSources: Set<PipelineStage> = exportSources.toSet()
    operator fun get(stage: PipelineStage): StageIdentity = requireNotNull(identities[stage])

    fun invalidatedComparedTo(previous: ArtifactIdentityPlan): Set<PipelineStage> =
        PipelineStage.entries.filterTo(linkedSetOf()) { stage ->
            identities[stage]?.identityHash != previous.identities[stage]?.identityHash
        }
}

/**
 * Canonical descendant invalidation policy. Stage identities are content based; job/revision
 * ownership is enforced by StageManifest/AlignmentCheckpoint validation rather than hidden in
 * these hashes.
 */
object ArtifactIdentityPolicy {
    private val DEFAULT_EXPORT_SOURCES = setOf(PipelineStage.TEXTURE, PipelineStage.LOD)

    fun build(
        snapshot: InputSnapshot,
        algorithms: PipelineAlgorithms,
        settings: PipelineSettings,
        exportSources: Set<PipelineStage> = DEFAULT_EXPORT_SOURCES,
    ): ArtifactIdentityPlan {
        require(exportSources.isNotEmpty()) { "At least one export source stage is required" }
        require(exportSources.all { it != PipelineStage.EXPORT }) { "Export cannot depend on itself" }

        val identities = linkedMapOf<PipelineStage, StageIdentity>()
        fun identity(
            stage: PipelineStage,
            components: Set<InputComponent>,
            parents: Set<PipelineStage>,
        ) {
            val parentRefs = parents.sortedBy { it.ordinal }.map { parent ->
                StageParent(parent, requireNotNull(identities[parent]) {
                    "Parent stage $parent must be constructed before $stage"
                }.identityHash)
            }
            identities[stage] = StageIdentity(
                stage = stage,
                algorithm = algorithms[stage],
                parameters = settings[stage],
                inputComponents = components.associateWith { component ->
                    requireNotNull(snapshot.componentHashes[component])
                },
                parents = parentRefs,
            )
        }

        // Feature descriptors depend on ordered selected photo bytes/metadata and their own engine.
        identity(
            PipelineStage.FEATURES,
            setOf(InputComponent.PHOTOS, InputComponent.ENGINE),
            emptySet(),
        )
        // Matching is a pure descendant of the accepted feature representation plus its algorithm.
        identity(
            PipelineStage.MATCHES,
            setOf(InputComponent.ENGINE),
            setOf(PipelineStage.FEATURES),
        )
        // Camera geometry additionally depends on calibration and the coordinate convention.
        identity(
            PipelineStage.ALIGNMENT,
            setOf(InputComponent.CALIBRATION, InputComponent.COORDINATES, InputComponent.ENGINE),
            setOf(PipelineStage.MATCHES),
        )
        // Dense photo sampling reads the selected photo content again, tied to accepted alignment.
        identity(
            PipelineStage.DENSE_DEPTH,
            setOf(InputComponent.PHOTOS, InputComponent.ENGINE),
            setOf(PipelineStage.ALIGNMENT),
        )
        identity(
            PipelineStage.SURFACE,
            setOf(InputComponent.COORDINATES, InputComponent.ENGINE),
            setOf(PipelineStage.DENSE_DEPTH),
        )
        // Appearance uses originals and calibration, but does not make geometry settings depend on it.
        identity(
            PipelineStage.TEXTURE,
            setOf(
                InputComponent.PHOTOS,
                InputComponent.CALIBRATION,
                InputComponent.COORDINATES,
                InputComponent.ENGINE,
            ),
            setOf(PipelineStage.SURFACE),
        )
        identity(
            PipelineStage.LOD,
            setOf(InputComponent.COORDINATES, InputComponent.ENGINE),
            setOf(PipelineStage.SURFACE),
        )
        identity(
            PipelineStage.EXPORT,
            setOf(InputComponent.COORDINATES, InputComponent.ENGINE),
            exportSources,
        )

        return ArtifactIdentityPlan(snapshot, identities.toMap(), exportSources.toSet())
    }
}
