package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.sqrt

/** Builds sparse provenance from actual matched features and accepted world geometry. */
object MeasuredAlignmentCheckpoint {
    fun build(plan: ArtifactIdentityPlan, geometry: JSONObject, cameras: List<AlignmentCamera>,
        observations: Map<Int, List<TrackObservation>>): AlignmentCheckpoint {
        val points = geometry.getJSONArray("points")
        require(points.length() == geometry.getInt("pointCount") && points.length() in 20..15_000)
        val pairErrors = linkedMapOf<Pair<Int, Int>, MutableList<Double>>()
        val tracks = (0 until points.length()).map { index ->
            val point = points.getJSONArray(index)
            require(point.length() >= 9)
            val id = point.getInt(8)
            val x = point.getDouble(0); val y = point.getDouble(1); val z = point.getDouble(2)
            val seen = requireNotNull(observations[id]) { "Accepted point has no observed feature track." }
            val usable = seen.mapNotNull { observation ->
                val camera = cameras.getOrNull(observation.cameraIndex) ?: return@mapNotNull null
                val r = camera.rotationWorldToCamera; val t = camera.translationWorldToCamera
                val px = r[0] * x + r[1] * y + r[2] * z + t[0]
                val py = r[3] * x + r[4] * y + r[5] * z + t[1]
                val pz = r[6] * x + r[7] * y + r[8] * z + t[2]
                if (pz <= 1e-9 || !listOf(px, py, pz).all(Double::isFinite)) return@mapNotNull null
                val error = hypot(px / pz - observation.xNormalized, py / pz - observation.yNormalized)
                if (!error.isFinite() || error > 0.05) return@mapNotNull null
                observation to error
            }.sortedBy { it.first.cameraIndex }
            require(usable.size >= 2) { "Accepted sparse point lacks two positive-depth reprojection observations." }
            for (first in usable.indices) for (second in first + 1 until usable.size) {
                val edge = usable[first].first.cameraIndex to usable[second].first.cameraIndex
                pairErrors.getOrPut(edge) { ArrayList() } += maxOf(usable[first].second, usable[second].second)
            }
            SparseTrack("track-$id", x, y, z, point.getInt(3), point.getInt(4), point.getInt(5),
                sqrt(usable.sumOf { it.second * it.second } / usable.size), usable.map { it.first })
        }
        val edges = pairErrors.filterValues { it.size >= 12 }.map { (edge, values) ->
            val sorted = values.sorted()
            VerifiedMatchEdge(edge.first, edge.second, values.size, values.size, sorted[sorted.size / 2])
        }
        val evidenceHash = Sha256.digestUtf8(buildString {
            append("measured-alignment-evidence-v2\n")
            cameras.forEach { camera ->
                append(camera.photoId).append('|').append(camera.rotationWorldToCamera.joinToString(","))
                    .append('|').append(camera.translationWorldToCamera.joinToString(",")).append('\n')
            }
            tracks.forEach { track ->
                append(track.trackId).append('|').append(track.x).append('|').append(track.y).append('|').append(track.z)
                    .append('|').append(track.reprojectionRmsNormalized).append('|')
                track.observations.forEach { observation -> append(observation).append(';') }
                append('\n')
            }
        })
        val result = AlignmentCheckpoint(plan.snapshot.binding, plan.snapshot.recordHash, plan[ArtifactStage.ALIGNMENT],
            StageAcceptance(AcceptanceKind.GEOMETRY, "positive-depth-reprojection-and-connected-tracks", "2", evidenceHash, true),
            cameras, edges, tracks)
        AlignmentCheckpointValidator.validateForReuse(result, plan.snapshot.binding, plan.snapshot.recordHash,
            plan[ArtifactStage.ALIGNMENT]).requireReusable()
        return result
    }

    /** Geometry and its reusable observations must describe exactly the same accepted points and poses. */
    fun requireSameGeometry(geometry: JSONObject, checkpoint: AlignmentCheckpoint) {
        fun number(value: Any): Double = (value as? Number)?.toDouble()?.also {
            require(it.isFinite()) { "A geometry coordinate must be a finite JSON number." }
        } ?: throw IllegalArgumentException("A geometry coordinate is not a JSON number.")
        fun integer(value: Any): Int = number(value).let {
            require(it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0)
            it.toInt()
        }
        val points = geometry.getJSONArray("points")
        val cameras = geometry.getJSONArray("cameras")
        require(points.length() == checkpoint.tracks.size && integer(geometry.get("pointCount")) == points.length())
        require(cameras.length() == checkpoint.cameras.size && integer(geometry.get("registeredCameras")) == cameras.length())
        val tracks = checkpoint.tracks.associateBy { it.trackId }
        val seen = hashSetOf<String>()
        for (index in 0 until points.length()) {
            val point = points.getJSONArray(index)
            require(point.length() >= 9)
            val track = requireNotNull(tracks["track-${integer(point.get(8))}"])
            require(seen.add(track.trackId)) { "A summary cannot repeat one track in place of another." }
            require(number(point.get(0)) == track.x && number(point.get(1)) == track.y && number(point.get(2)) == track.z)
            require(integer(point.get(3)) == track.red && integer(point.get(4)) == track.green && integer(point.get(5)) == track.blue)
        }
        checkpoint.cameras.forEachIndexed { index, camera ->
            val actual = cameras.getJSONObject(index)
            require(actual.getString("photoId") == camera.photoId)
            val rotation = actual.getJSONArray("rotation"); val translation = actual.getJSONArray("translation")
            require(rotation.length() == 9 && translation.length() == 3)
            require((0..8).all { number(rotation.get(it)) == camera.rotationWorldToCamera[it] })
            require((0..2).all { number(translation.get(it)) == camera.translationWorldToCamera[it] })
        }
    }
}
