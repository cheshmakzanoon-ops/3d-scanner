package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import com.realityforge.scanner.engine.dense.DenseViewSelection
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

/** Dense overlap and priors come from accepted, independently reprojected observations,
 * not descriptor similarity, an unvalidated JSON support list, or frontier candidates. */
internal object VerifiedDenseAlignment {
    const val POLICY = "verified-alignment-observations-v2|residual=per-camera-anisotropic-3px|max-normalized=.05|no-sharpness-ranking"
    data class Prepared(val selection: DenseViewSelection.Plan,
        val support: Map<Int, Set<String>>, val thresholdNormalized: Double)

    fun prepare(plan: ArtifactIdentityPlan, geometry: JSONObject, checkpoint: AlignmentCheckpoint,
        maximumViews: Int, checkActive: () -> Unit = {}): Prepared {
        checkActive()
        AlignmentCheckpointValidator.validateForReuse(checkpoint, plan.snapshot.binding,
            plan.snapshot.recordHash, plan[ArtifactStage.ALIGNMENT]).requireReusable()
        MeasuredAlignmentCheckpoint.requireSameGeometry(geometry, checkpoint)
        val cameras = checkpoint.cameras
        val selectedPhotoIds = plan.snapshot.selectedPhotos.map { it.photoId }.toSet()
        require(cameras.all { it.photoId in selectedPhotoIds })
        // Conservative normalized uncertainty bound for dense priors only. Observation
        // acceptance below uses each camera's pixel axes, never this global maximum.
        val threshold = min(.05, 3.0 / cameras.minOf { min(it.fx, it.fy) })
        val sourceIds = geometry.optJSONArray("sparseSourceTrackIds")?.let { array ->
            require(array.length() <= 15_000)
            List(array.length()) { array.getString(it).also { id ->
                require(id.matches(Regex("[0-9a-f]{64}")))
            } }.also { require(it.distinct().size == it.size) }
        }
        val tracksByCamera = List(cameras.size) { linkedSetOf<String>() }
        val support = linkedMapOf<Int, Set<String>>()
        for (track in checkpoint.tracks) {
            checkActive()
            val id = track.trackId.removePrefix("track-").toIntOrNull()
            require(id != null && id >= 0 && track.trackId == "track-$id")
            // For anchor-only geometry this is a namespaced identity of an EXISTING
            // checkpoint track, not an invented observation or global source-track claim.
            val stableId = if (sourceIds != null) sourceIds.getOrNull(id).also { require(it != null) }!!
                else Sha256.digestUtf8("alignment-track|${checkpoint.identity.identityHash.hex}|${track.trackId}").hex
            val accepted = track.observations.filter { observation ->
                val camera = cameras[observation.cameraIndex]
                val r = camera.rotationWorldToCamera; val t = camera.translationWorldToCamera
                val xyz = listOf(track.x, track.y, track.z)
                val q = (0..2).map { row -> (0..2).sumOf { col -> r[row * 3 + col] * xyz[col] } + t[row] }
                if (!q.all(Double::isFinite) || q[2] <= 1e-9) false else {
                    val dx = q[0] / q[2] - observation.xNormalized
                    val dy = q[1] / q[2] - observation.yNormalized
                    hypot(dx, dy) <= .05 && hypot(dx * camera.fx, dy * camera.fy) <= 3.0
                }
            }
            if (accepted.size < 2) continue
            require(support.put(id, accepted.map { cameras[it.cameraIndex].photoId }.toSet()) == null)
            accepted.forEach { tracksByCamera[it.cameraIndex].add(stableId) }
        }
        val selection = DenseViewSelection.select(cameras.mapIndexed { index, camera ->
            val r = camera.rotationWorldToCamera; val t = camera.translationWorldToCamera
            val centre = (0..2).map { col -> -(0..2).sumOf { row -> r[row * 3 + col] * t[row] } }
            // No measured sharpness is persisted in this checkpoint: disable that ranking term.
            DenseViewSelection.Camera(camera.photoId, centre, tracksByCamera[index], 0.0)
        }, referenceCap = maximumViews, cancelled = { checkActive(); false })
        require(selection.referencePhotoIds.size >= 2 && selection.sourcePhotoIds.values.all { it.isNotEmpty() }) {
            "Accepted alignment has insufficient verified dense overlap."
        }
        checkActive()
        return Prepared(selection, support, threshold)
    }
}
