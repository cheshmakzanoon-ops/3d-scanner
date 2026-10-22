package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.engine.artifacts.RepresentationKind
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject

/** Records actual solver outputs after schema/geometry acceptance and synced export writes. */
object PipelineDownstreamCheckpoints {
    suspend fun record(session: PipelineArtifactSession, result: JSONObject, artifactKey: String) {
        val validation = PreviewSchemaValidator.validate(result, session.pipeline.revision, artifactKey)
        require(validation is ReopenResult.Ready) {
            val invalid = validation as? ReopenResult.Invalid
            "The solver output failed strict preview validation before checkpoint acceptance: " +
                "${invalid?.code ?: "invalid_preview"}: ${invalid?.detail ?: "Preview was not ready."}"
        }
        val root = session.directory
        val diagnostic = PipelineStageFile(File(root, "$artifactKey.json"), RepresentationKind.DIAGNOSTIC_REPORT, "application/json")
        fun artifact(extension: String, representation: RepresentationKind, mediaType: String) =
            PipelineStageFile(File(root, "$artifactKey.$extension"), representation, mediaType)

        val denseReady = result.optString("denseStatus") == "ready"
        val depthFiles = mutableListOf(diagnostic)
        if (denseReady) {
            val depth = result.getJSONObject("densePreview")
            require(depth.getInt("pointCount") >= 60 && depth.getInt("consistentPixels") >= 60)
            depthFiles += artifact("dense.glb", RepresentationKind.DENSE_POINT_CLOUD, "model/gltf-binary")
            depthFiles += artifact("dense.ply", RepresentationKind.DENSE_POINT_CLOUD, "application/ply")
            // Owner050's binary remains authoritative. Track only complete native files in the
            // exact versioned directory used by this result; never infer ownership from .partial.
            val depthIdentity = depth.optString("depthCheckpointIdentity")
            if (depthIdentity.matches(Regex("[0-9a-f]{64}"))) {
                val directory = File(File(root, ".depth-checkpoints-m18"), depthIdentity)
                for (file in completeNativeDepthReferences(directory))
                    depthFiles += PipelineStageFile(file, RepresentationKind.DEPTH_MAP_SET)
            }
        }
        session.record(ArtifactStage.DENSE_DEPTH, depthFiles, "reciprocal-depth-and-strict-preview-v2", denseReady)

        val meshReady = result.optString("meshStatus") == "ready"
        val meshFiles = mutableListOf(diagnostic)
        if (meshReady) {
            require(denseReady)
            val mesh = result.getJSONObject("meshPreview")
            require(mesh.getInt("vertexCount") >= 30 && mesh.getInt("triangleCount") >= 40)
            meshFiles += artifact("mesh.glb", RepresentationKind.TRIANGLE_MESH, "model/gltf-binary")
            meshFiles += artifact("mesh.ply", RepresentationKind.TRIANGLE_MESH, "application/ply")
            val dense = result.getJSONObject("densePreview")
            val identity = dense.optString("depthCheckpointIdentity")
            // The native solver must have independently accepted this complete binary. A
            // filename left by a failed or interrupted writer does not establish geometry.
            if (dense.optBoolean("surfaceCheckpointAvailable", false) && identity.matches(Regex("[0-9a-f]{64}"))) {
                val fullMesh = File(root, ".depth-checkpoints-m18/$identity/surface-v2.rfmesh")
                require(Files.isRegularFile(fullMesh.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "The accepted native surface checkpoint is missing."
                }
                meshFiles += PipelineStageFile(fullMesh, RepresentationKind.TRIANGLE_MESH)
            }
        }
        session.starting(ArtifactStage.SURFACE)
        session.record(ArtifactStage.SURFACE, meshFiles, "observed-triangle-topology-and-strict-preview-v2", meshReady)

        val textureReady = result.optString("textureStatus") == "ready"
        val textureFiles = mutableListOf(diagnostic)
        if (textureReady) {
            require(meshReady)
            val texture = result.getJSONObject("texturePreview")
            require(texture.getInt("triangleCount") > 0 && texture.getDouble("faceCoverage") > 0.0)
            textureFiles += artifact("textured.glb", RepresentationKind.TEXTURED_MESH, "model/gltf-binary")
            textureFiles += artifact("texture.png", RepresentationKind.TEXTURE_ATLAS, "image/png")
        }
        session.starting(ArtifactStage.TEXTURE)
        session.record(ArtifactStage.TEXTURE, textureFiles, "photograph-projection-and-strict-preview-v2", textureReady)
    }
}
