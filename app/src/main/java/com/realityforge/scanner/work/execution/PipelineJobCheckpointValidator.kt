package com.realityforge.scanner.work.execution

import com.realityforge.scanner.data.InputPhotoIdentity
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import com.realityforge.scanner.engine.pipeline.*
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** A durable cursor is reusable only when its complete owned dependency closure still verifies. */
class PipelineJobCheckpointValidator(private val repository: ProjectRepository) : JobCheckpointValidator {
    override suspend fun validate(snapshot: JobSnapshot): CheckpointRecoveryDecision {
        if (snapshot.cursor.acceptedStage == ReconstructionStage.CREATED)
            return CheckpointRecoveryDecision.RestartFromBeginning()
        return try {
            val input = repository.inputSnapshot(snapshot.input.projectId)
            require(input.revision == snapshot.input.projectRevision &&
                InputPhotoIdentity.snapshotSha256(input) == snapshot.input.inputSha256)
            require(snapshot.input.engineVersion == ReconstructionExecutionController.ENGINE_VERSION &&
                snapshot.input.engineSha256 == sha256Utf8(ReconstructionExecutionController.ENGINE_VERSION))
            val calibration = LensCalibration.file(repository, input.projectId)
            val calibrationHash = sha256Utf8(if (calibration.isFile) calibration.readText() else "pinhole-no-profile-v1")
            require(calibrationHash == snapshot.input.calibrationSha256)
            val settings = JSONObject(snapshot.input.settingsJson)
            val resources = when (settings.getInt("schema")) {
                1 -> ResourceWorkloadSelection.COMPACT
                2 -> ResourceWorkloadSelection.decode(settings.getJSONObject("resourceSelection"))
                else -> error("Unsupported persisted settings.")
            }
            val current = ProductionPipelineSnapshot.create(input, calibrationHash, settings.getDouble("focal35"), resources)
            validateArtifacts(repository.directory(input.projectId), snapshot, current)
            CheckpointRecoveryDecision.ResumeFrom(snapshot.cursor)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            CheckpointRecoveryDecision.RestartFromBeginning("CHECKPOINT_REVALIDATION_FAILED")
        }
    }

    companion object {
        /** Pure file-level seam used by actual recovery and corruption/restart JVM fixtures. */
        fun validateArtifacts(root: File, job: JobSnapshot, current: PipelineInputSnapshot) {
            val cursor = job.cursor
            val hash = Sha256.parse(requireNotNull(cursor.checkpointSha256))
            val reader = PipelineManifestReader(root)
            val manifest = if (cursor.acceptedStage == ReconstructionStage.PREPARED) null else reader.manifest(hash)
            require(cursor.checkpointIdentity == "${if (manifest == null) "input" else "manifest"}-${hash.hex}")
            val source = reader.input(manifest?.inputSnapshotRecordHash ?: hash)
            require(source.binding.projectId == job.input.projectId && source.binding.projectRevision == job.input.projectRevision &&
                source.binding.jobId == job.jobId.value && source.binding.attempt in 1..job.attempt)
            val plan = PipelineArtifactSession.identityPlan(current, source.binding)
            require(source.contentHash == plan.snapshot.contentHash)
            if (manifest == null) return
            require(PipelineArtifactSession.durableStage(manifest.identity.stage) == cursor.acceptedStage)
            val closure = reader.verify(manifest, allowUnavailable = true)
            for ((stage, recorded) in closure) require(recorded.identity == plan[stage])
            closure[ArtifactStage.ALIGNMENT]?.let { alignment ->
                val reference = alignment.artifacts.single { it.representation == RepresentationKind.CAMERA_ALIGNMENT }
                val full = AlignmentCheckpointCodec.decode(reader.readBounded(File(root, reference.relativePath), 16 * 1024 * 1024))
                AlignmentCheckpointValidator.validateForReuse(full, source.binding, source.recordHash,
                    plan[ArtifactStage.ALIGNMENT]).requireReusable()
                require(full.cameras.all { camera -> source.selectedPhotos.any { it.photoId == camera.photoId } })
                val summary = alignment.artifacts.single { it.representation == RepresentationKind.DIAGNOSTIC_REPORT }
                val geometry = JSONObject(reader.readBounded(File(root, summary.relativePath), 16 * 1024 * 1024).toString(Charsets.UTF_8))
                MeasuredAlignmentCheckpoint.requireSameGeometry(geometry, full)
            }
            if (cursor.acceptedStage.order >= ReconstructionStage.DENSE.order) {
                val reference = manifest.artifacts.single { it.representation == RepresentationKind.DIAGNOSTIC_REPORT }
                val preview = JSONObject(reader.readBounded(File(root, reference.relativePath), PreviewSchemaValidator.MAX_BYTES).toString(Charsets.UTF_8))
                require(PreviewSchemaValidator.validate(preview, source.binding.projectRevision,
                    preview.getString("artifactKey")) is ReopenResult.Ready)
                val name = when (manifest.identity.stage) {
                    ArtifactStage.DENSE_DEPTH -> "dense"
                    ArtifactStage.SURFACE -> "mesh"
                    ArtifactStage.TEXTURE -> "texture"
                    else -> error("Unsupported result checkpoint stage.")
                }
                require((preview.optString("${name}Status") == "ready") == (manifest.completion == CompletionState.COMPLETE))
            }
        }
    }
}
