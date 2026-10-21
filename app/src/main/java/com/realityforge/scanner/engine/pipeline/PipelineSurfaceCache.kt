package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.cachegc.CacheStageManifestStore
import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import java.io.File
import org.json.JSONObject

/** Constructed only after the full C8 dependency closure and current sparse frame verify. */
class VerifiedSurfaceCheckpoint internal constructor(
    val surfaceFile: File,
    val depthDirectory: File,
    val depthIdentity: String,
    val sourceArtifactKey: String,
    val meshGlb: File,
    val meshPly: File,
    val meshGlbSha256: String,
    val meshPlySha256: String,
    val meshGlbBytes: Long,
    val meshPlyBytes: Long,
    val payload: JSONObject,
    val completeTexture: VerifiedTextureCheckpoint?,
)

class VerifiedTextureCheckpoint internal constructor(
    val texturedGlb: File,
    val atlasPng: File,
    val texturedGlbSha256: String,
    val atlasPngSha256: String,
    val texturedGlbBytes: Long,
    val atlasPngBytes: Long,
)

/** A matching settings hash cannot substitute for a measured sparse frame or a full native mesh. */
class PipelineSurfaceCache(private val root: File) {
    fun load(plan: ArtifactIdentityPlan, currentSparse: JSONObject): VerifiedSurfaceCheckpoint? {
        val inventory = try { CacheStageManifestStore(root).read() }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        val reader = PipelineManifestReader(root)
        val candidates = inventory.filter { it.identity.stage == ArtifactStage.SURFACE &&
            it.completion == CompletionState.COMPLETE && it.identity.identityHash == plan[ArtifactStage.SURFACE].identityHash &&
            it.binding.projectId == plan.snapshot.binding.projectId }
            .sortedWith(compareByDescending<StageManifest> { it.binding.projectRevision }.thenByDescending { it.binding.attempt })
        for (manifest in candidates.take(16)) {
            val selected = try {
                val sourceInput = reader.input(manifest.inputSnapshotRecordHash)
                require(sourceInput.contentHash == plan.snapshot.contentHash)
                val closure = reader.verify(manifest)
                closure.forEach { (stage, source) -> require(source.identity == plan[stage]) }
                val alignment = requireNotNull(closure[ArtifactStage.ALIGNMENT])
                val alignmentFile = alignment.artifacts.single { it.representation == RepresentationKind.CAMERA_ALIGNMENT }
                val full = AlignmentCheckpointCodec.decode(reader.readBounded(File(root, alignmentFile.relativePath), 16 * 1024 * 1024))
                AlignmentCheckpointValidator.validateForReuse(full, sourceInput.binding, sourceInput.recordHash,
                    plan[ArtifactStage.ALIGNMENT]).requireReusable()
                require(full.cameras.all { camera -> plan.snapshot.selectedPhotos.any { it.photoId == camera.photoId } })
                MeasuredAlignmentCheckpoint.requireSameGeometry(currentSparse, full)
                val diagnostic = manifest.artifacts.single { it.representation == RepresentationKind.DIAGNOSTIC_REPORT }
                val preview = JSONObject(reader.readBounded(File(root, diagnostic.relativePath), PreviewSchemaValidator.MAX_BYTES).toString(Charsets.UTF_8))
                val artifactKey = preview.getString("artifactKey")
                require(PreviewSchemaValidator.validate(preview, sourceInput.binding.projectRevision, artifactKey) is ReopenResult.Ready)
                MeasuredAlignmentCheckpoint.requireSameGeometry(preview, full)
                require(preview.optString("denseStatus") == "ready" && preview.optString("meshStatus") == "ready")
                val densePreview = preview.getJSONObject("densePreview")
                require(densePreview.optBoolean("surfaceCheckpointAvailable", false))
                val identity = densePreview.getString("depthCheckpointIdentity")
                require(identity.matches(Regex("[0-9a-f]{64}")))
                val depthPath = ".depth-checkpoints-m18/$identity"
                val surface = manifest.artifacts.single { it.relativePath == "$depthPath/surface-v2.rfmesh" &&
                    it.representation == RepresentationKind.TRIANGLE_MESH }
                val depthFiles = requireNotNull(closure[ArtifactStage.DENSE_DEPTH]).artifacts.filter {
                    it.representation == RepresentationKind.DEPTH_MAP_SET }
                require(depthFiles.size >= 2 && depthFiles.all { it.relativePath.startsWith("$depthPath/") })
                val glb = manifest.artifacts.single { it.relativePath == "$artifactKey.mesh.glb" && it.representation == RepresentationKind.TRIANGLE_MESH }
                val ply = manifest.artifacts.single { it.relativePath == "$artifactKey.mesh.ply" && it.representation == RepresentationKind.TRIANGLE_MESH }
                val payload = JSONObject().put("dense", preview.getJSONObject("densePreview"))
                    .put("mesh", preview.getJSONObject("meshPreview"))
                    .put("geometryReused", true)
                val texture = inventory.firstNotNullOfOrNull { candidate ->
                    if (candidate.identity.stage != ArtifactStage.TEXTURE || candidate.completion != CompletionState.COMPLETE ||
                        candidate.identity.identityHash != plan[ArtifactStage.TEXTURE].identityHash ||
                        candidate.binding != manifest.binding ||
                        candidate.dependencies.singleOrNull()?.manifestHash != StageManifestCodec.hash(manifest)) null
                    else try {
                        reader.verify(candidate)
                        val sourceTexture = preview.getJSONObject("texturePreview")
                        require(preview.optString("textureStatus") == "ready")
                        val textured = candidate.artifacts.single { it.relativePath == "$artifactKey.textured.glb" && it.representation == RepresentationKind.TEXTURED_MESH }
                        val atlas = candidate.artifacts.single { it.relativePath == "$artifactKey.texture.png" && it.representation == RepresentationKind.TEXTURE_ATLAS }
                        payload.put("texture", sourceTexture)
                        VerifiedTextureCheckpoint(File(root, textured.relativePath), File(root, atlas.relativePath),
                            textured.contentHash.hex, atlas.contentHash.hex, textured.byteSize, atlas.byteSize)
                    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
                    } catch (_: Exception) { null }
                }
                VerifiedSurfaceCheckpoint(File(root, surface.relativePath), File(root, depthPath), identity,
                    artifactKey, File(root, glb.relativePath), File(root, ply.relativePath), glb.contentHash.hex,
                    ply.contentHash.hex, glb.byteSize, ply.byteSize, payload, texture)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            if (selected != null) return selected
        }
        return null
    }
}
