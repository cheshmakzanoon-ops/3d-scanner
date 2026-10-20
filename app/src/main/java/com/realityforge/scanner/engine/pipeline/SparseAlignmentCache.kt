package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.cachegc.CacheStageManifestStore
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import java.io.File
import org.json.JSONObject

/** Full sparse sessions only: valid JSON or a checksum without accepted observations is not reusable. */
class SparseAlignmentCache(private val projectDirectory: File) {
    data class Restored(val geometry: JSONObject, val checkpoint: AlignmentCheckpoint,
        val parents: Map<ArtifactStage, StageManifest>)

    fun load(plan: ArtifactIdentityPlan): Restored? {
        val reader = PipelineManifestReader(projectDirectory)
        val inventory = try { CacheStageManifestStore(projectDirectory).read() }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        val candidates = inventory
            .filter { it.identity.stage == ArtifactStage.ALIGNMENT && it.completion == CompletionState.COMPLETE &&
                it.identity.identityHash == plan[ArtifactStage.ALIGNMENT].identityHash &&
                it.binding.projectId == plan.snapshot.binding.projectId }
            .sortedWith(compareByDescending<StageManifest> { it.binding.projectRevision }.thenByDescending { it.binding.attempt })
        for (manifest in candidates.take(16)) {
            val restored = try {
                val oldSnapshot = reader.input(manifest.inputSnapshotRecordHash)
                require(oldSnapshot.contentHash == plan.snapshot.contentHash)
                val parents = reader.verify(manifest)
                val full = manifest.artifacts.single { it.representation == RepresentationKind.CAMERA_ALIGNMENT }
                val summary = manifest.artifacts.single { it.representation == RepresentationKind.DIAGNOSTIC_REPORT }
                val source = AlignmentCheckpointCodec.decode(reader.readBounded(File(projectDirectory, full.relativePath), MAX_ALIGNMENT_BYTES))
                AlignmentCheckpointValidator.validateForReuse(source, oldSnapshot.binding, oldSnapshot.recordHash,
                    plan[ArtifactStage.ALIGNMENT]).requireReusable()
                val selectedIds = plan.snapshot.selectedPhotos.map { it.photoId }.toSet()
                require(source.cameras.all { it.photoId in selectedIds })
                val geometry = JSONObject(reader.readBounded(File(projectDirectory, summary.relativePath), MAX_SUMMARY_BYTES).toString(Charsets.UTF_8))
                MeasuredAlignmentCheckpoint.requireSameGeometry(geometry, source)
                // The old immutable record remains intact. This explicit rebind is serialized into
                // a new attempt record before it is acknowledged to the current durable worker.
                val rebound = AlignmentCheckpoint(plan.snapshot.binding, plan.snapshot.recordHash,
                    plan[ArtifactStage.ALIGNMENT], source.acceptance, source.cameras, source.verifiedMatchGraph, source.tracks)
                Restored(geometry, rebound, parents)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            if (restored != null) return restored
        }
        return null
    }

    fun save(plan: ArtifactIdentityPlan, geometry: JSONObject, checkpoint: AlignmentCheckpoint): List<PipelineStageFile> {
        AlignmentCheckpointValidator.validateForReuse(checkpoint, plan.snapshot.binding, plan.snapshot.recordHash,
            plan[ArtifactStage.ALIGNMENT]).requireReusable()
        MeasuredAlignmentCheckpoint.requireSameGeometry(geometry, checkpoint)
        val checkpointBytes = AlignmentCheckpointCodec.encode(checkpoint)
        val summaryBytes = geometry.toString().toByteArray(Charsets.UTF_8)
        require(checkpointBytes.size <= MAX_ALIGNMENT_BYTES && summaryBytes.size <= MAX_SUMMARY_BYTES)
        val prefix = "alignment-${plan[ArtifactStage.ALIGNMENT].identityHash.hex}-${plan.snapshot.recordHash.hex.take(16)}"
        val full = File(projectDirectory, "$prefix.rfac")
        val summary = File(projectDirectory, "$prefix.json")
        PipelineArtifactSession.writeImmutable(full, checkpointBytes)
        PipelineArtifactSession.writeImmutable(summary, summaryBytes)
        return listOf(PipelineStageFile(full, RepresentationKind.CAMERA_ALIGNMENT),
            PipelineStageFile(summary, RepresentationKind.DIAGNOSTIC_REPORT, "application/json"))
    }

    companion object {
        private const val MAX_ALIGNMENT_BYTES = 16 * 1024 * 1024
        private const val MAX_SUMMARY_BYTES = 16 * 1024 * 1024
    }
}
