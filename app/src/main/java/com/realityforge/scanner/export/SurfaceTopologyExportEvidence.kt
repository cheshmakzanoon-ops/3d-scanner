package com.realityforge.scanner.export

import com.realityforge.scanner.data.reopening.ScanDisplayModel
import com.realityforge.scanner.engine.SurfaceTopologySummary

/** Read-only qualification for the exact source artifact, not a serializer/eligibility decision. */
data class SurfaceTopologyExportEvidence(val artifact: ArtifactIdentity, val summary: SurfaceTopologySummary) {
    fun forArtifact(identity: ArtifactIdentity): SurfaceTopologySummary? = summary.takeIf { artifact == identity }

    companion object {
        internal fun fromReopened(identity: ArtifactIdentity, model: ScanDisplayModel): SurfaceTopologyExportEvidence? =
            if (model.previousResult || model.artifactKey != identity.artifactKey || model.revision != identity.revision ||
                model.meshVertices.isEmpty() || model.triangles.isEmpty()) null
            else SurfaceTopologyExportEvidence(identity, model.surfaceTopology)
    }
}

internal fun ExportOption.isSurfaceMesh(): Boolean = representation in setOf(
    AssetRepresentation.OBSERVED_SURFACE, AssetRepresentation.PHOTOGRAPHIC_SURFACE)

/** Current UI selection; prevents a previous catalog flashing after project/result changes. */
internal data class SurfaceExportSelection(val projectId: String, val revision: Long?, val artifactKey: String?) {
    fun matches(identity: ArtifactIdentity): Boolean = projectId == identity.projectId &&
        revision == identity.revision && artifactKey == identity.artifactKey
}
