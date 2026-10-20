package com.realityforge.scanner.engine.artifacts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AlignmentValidationPolicy(
    val minimumCameras: Int = 2,
    val minimumSparseTracks: Int = 20,
    val minimumInliersPerEdge: Int = 12,
    val maximumMedianEdgeErrorNormalized: Double = 0.05,
    val maximumTrackReprojectionRmsNormalized: Double = 0.05,
    val rotationOrthonormalTolerance: Double = 1e-5,
    val rotationDeterminantTolerance: Double = 1e-5,
    val minimumBaseline: Double = 1e-9,
    val maximumAbsoluteCoordinate: Double = 1e12,
    val maximumAbsoluteNormalizedObservation: Double = 100.0,
) {
    init {
        require(minimumCameras in 2..AlignmentCheckpoint.MAX_CAMERAS)
        require(minimumSparseTracks in 1..AlignmentCheckpoint.MAX_TRACKS)
        require(minimumInliersPerEdge >= 0)
        require(maximumMedianEdgeErrorNormalized.isFinite() && maximumMedianEdgeErrorNormalized >= 0.0)
        require(maximumTrackReprojectionRmsNormalized.isFinite() && maximumTrackReprojectionRmsNormalized >= 0.0)
        require(rotationOrthonormalTolerance.isFinite() && rotationOrthonormalTolerance >= 0.0)
        require(rotationDeterminantTolerance.isFinite() && rotationDeterminantTolerance >= 0.0)
        require(minimumBaseline.isFinite() && minimumBaseline > 0.0)
        require(maximumAbsoluteCoordinate.isFinite() && maximumAbsoluteCoordinate > 0.0)
        require(maximumAbsoluteNormalizedObservation.isFinite() && maximumAbsoluteNormalizedObservation > 0.0)
    }
}

enum class AlignmentRejection {
    BINDING_MISMATCH,
    SNAPSHOT_MISMATCH,
    STAGE_IDENTITY_MISMATCH,
    ACCEPTANCE_MISSING_OR_REJECTED,
    CAMERA_COUNT,
    CAMERA_INTRINSICS,
    CAMERA_ROTATION,
    CAMERA_BASELINE,
    MATCH_EDGE_INVALID,
    MATCH_GRAPH_DISCONNECTED,
    TRACK_COUNT,
    TRACK_GEOMETRY,
    TRACK_OBSERVATION_INVALID,
    TRACK_REPROJECTION_ERROR,
    TRACK_BEHIND_CAMERA,
    TRACK_FEATURE_CONFLICT,
    VALIDATION_BUDGET_EXCEEDED,
}

data class AlignmentValidationResult(
    val rejections: Set<AlignmentRejection>,
    val cameraCount: Int,
    val trackCount: Int,
    val observationCount: Long,
    val maximumBaseline: Double,
    val maximumRotationError: Double,
    val maximumTrackReprojectionRms: Double,
) {
    val reusable: Boolean get() = rejections.isEmpty()

    fun requireReusable() {
        if (!reusable) throw ArtifactFormatException("Alignment checkpoint is not reusable: ${rejections.joinToString()}")
    }
}

object AlignmentCheckpointValidator {
    private const val MAX_PAIR_CHECKS = 2_000_000L
    fun validateForReuse(
        checkpoint: AlignmentCheckpoint,
        expectedBinding: ArtifactBinding,
        expectedSnapshotRecordHash: Sha256,
        expectedIdentity: StageIdentity,
        policy: AlignmentValidationPolicy = AlignmentValidationPolicy(),
    ): AlignmentValidationResult {
        val failures = linkedSetOf<AlignmentRejection>()
        if (checkpoint.binding != expectedBinding) failures += AlignmentRejection.BINDING_MISMATCH
        if (checkpoint.inputSnapshotRecordHash != expectedSnapshotRecordHash) {
            failures += AlignmentRejection.SNAPSHOT_MISMATCH
        }
        if (checkpoint.identity.identityHash != expectedIdentity.identityHash ||
            checkpoint.identity.stage != PipelineStage.ALIGNMENT
        ) {
            failures += AlignmentRejection.STAGE_IDENTITY_MISMATCH
        }
        if (!checkpoint.acceptance.accepted || checkpoint.acceptance.kind != AcceptanceKind.GEOMETRY) {
            failures += AlignmentRejection.ACCEPTANCE_MISSING_OR_REJECTED
        }
        if (checkpoint.cameras.size < policy.minimumCameras) failures += AlignmentRejection.CAMERA_COUNT

        var maximumRotationError = 0.0
        checkpoint.cameras.forEach { camera ->
            if (camera.fx <= 0.0 || camera.fy <= 0.0 ||
                camera.fx > 1e9 || camera.fy > 1e9 ||
                camera.cx !in -camera.width.toDouble()..(2.0 * camera.width) ||
                camera.cy !in -camera.height.toDouble()..(2.0 * camera.height)
            ) {
                failures += AlignmentRejection.CAMERA_INTRINSICS
            }
            val rotationError = rotationError(camera.rotationWorldToCamera)
            maximumRotationError = max(maximumRotationError, rotationError.first)
            if (rotationError.first > policy.rotationOrthonormalTolerance ||
                abs(rotationError.second - 1.0) > policy.rotationDeterminantTolerance
            ) {
                failures += AlignmentRejection.CAMERA_ROTATION
            }
        }

        val centers = checkpoint.cameras.map(::cameraCenter)
        var maximumBaseline = 0.0
        for (first in centers.indices) for (second in first + 1 until centers.size) {
            maximumBaseline = max(maximumBaseline, distance(centers[first], centers[second]))
        }
        if (maximumBaseline < policy.minimumBaseline) failures += AlignmentRejection.CAMERA_BASELINE

        val adjacency = Array(checkpoint.cameras.size) { mutableSetOf<Int>() }
        checkpoint.verifiedMatchGraph.forEach { edge ->
            if (edge.firstCamera !in checkpoint.cameras.indices ||
                edge.secondCamera !in checkpoint.cameras.indices ||
                edge.inliers < policy.minimumInliersPerEdge ||
                edge.medianErrorNormalized > policy.maximumMedianEdgeErrorNormalized
            ) {
                failures += AlignmentRejection.MATCH_EDGE_INVALID
            } else {
                adjacency[edge.firstCamera] += edge.secondCamera
                adjacency[edge.secondCamera] += edge.firstCamera
            }
        }
        if (checkpoint.cameras.isNotEmpty()) {
            val visited = BooleanArray(checkpoint.cameras.size)
            val queue = ArrayDeque<Int>()
            visited[0] = true
            queue.add(0)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                adjacency[current].forEach { neighbour ->
                    if (!visited[neighbour]) {
                        visited[neighbour] = true
                        queue.add(neighbour)
                    }
                }
            }
            if (visited.any { !it }) failures += AlignmentRejection.MATCH_GRAPH_DISCONNECTED
        }

        if (checkpoint.tracks.size < policy.minimumSparseTracks) failures += AlignmentRejection.TRACK_COUNT
        var maximumRms = 0.0
        var observations = 0L
        val featureOwners = HashSet<Long>()
        val supportedEdges = HashMap<Pair<Int, Int>, Int>()
        var pairChecks = 0L
        checkpoint.tracks.forEach { track ->
            observations += track.observations.size
            if (abs(track.x) > policy.maximumAbsoluteCoordinate ||
                abs(track.y) > policy.maximumAbsoluteCoordinate ||
                abs(track.z) > policy.maximumAbsoluteCoordinate
            ) {
                failures += AlignmentRejection.TRACK_GEOMETRY
            }
            var squaredError = 0.0
            var usableObservations = 0
            val supportedCameras = ArrayList<Int>(track.observations.size)
            track.observations.forEach { observation ->
                if (observation.cameraIndex !in checkpoint.cameras.indices ||
                    abs(observation.xNormalized) > policy.maximumAbsoluteNormalizedObservation ||
                    abs(observation.yNormalized) > policy.maximumAbsoluteNormalizedObservation
                ) {
                    failures += AlignmentRejection.TRACK_OBSERVATION_INVALID
                    return@forEach
                }
                // A feature can belong to one triangulated landmark only. Bound validator state
                // independently of the decoder's format envelope to resist adversarial records.
                if (featureOwners.size >= 1_000_000) failures += AlignmentRejection.VALIDATION_BUDGET_EXCEEDED
                else if (!featureOwners.add((observation.cameraIndex.toLong() shl 32) or observation.featureIndex.toLong()))
                    failures += AlignmentRejection.TRACK_FEATURE_CONFLICT
                val camera = checkpoint.cameras[observation.cameraIndex]
                val r = camera.rotationWorldToCamera
                val t = camera.translationWorldToCamera
                val x = r[0] * track.x + r[1] * track.y + r[2] * track.z + t[0]
                val y = r[3] * track.x + r[4] * track.y + r[5] * track.z + t[1]
                val z = r[6] * track.x + r[7] * track.y + r[8] * track.z + t[2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 1e-9) {
                    failures += AlignmentRejection.TRACK_BEHIND_CAMERA
                    return@forEach
                }
                val dx = x / z - observation.xNormalized
                val dy = y / z - observation.yNormalized
                val residualSquared = dx * dx + dy * dy
                if (!residualSquared.isFinite()) {
                    failures += AlignmentRejection.TRACK_REPROJECTION_ERROR
                    return@forEach
                }
                squaredError += residualSquared
                usableObservations++
                if (residualSquared <= policy.maximumTrackReprojectionRmsNormalized * policy.maximumTrackReprojectionRmsNormalized)
                    supportedCameras += observation.cameraIndex
            }
            val measuredRms = if (usableObservations == track.observations.size && usableObservations >= 2)
                sqrt(squaredError / usableObservations) else Double.POSITIVE_INFINITY
            maximumRms = max(maximumRms, measuredRms)
            if (!measuredRms.isFinite() || measuredRms > policy.maximumTrackReprojectionRmsNormalized ||
                abs(measuredRms - track.reprojectionRmsNormalized) > 1e-7 + measuredRms * 1e-5) {
                failures += AlignmentRejection.TRACK_REPROJECTION_ERROR
            }
            supportedCameras.sort()
            if (pairChecks <= MAX_PAIR_CHECKS) for (i in supportedCameras.indices) {
                for (j in i + 1 until supportedCameras.size) {
                    if (++pairChecks > MAX_PAIR_CHECKS) { failures += AlignmentRejection.VALIDATION_BUDGET_EXCEEDED; break }
                    val edge = supportedCameras[i] to supportedCameras[j]
                    supportedEdges[edge] = (supportedEdges[edge] ?: 0) + 1
                }
                if (pairChecks > MAX_PAIR_CHECKS) break
            }

        }

        checkpoint.verifiedMatchGraph.forEach { edge ->
            // A connected pose graph must have measured, positive-depth shared landmarks.
            // A fabricated edge count cannot validate a camera with no observed support.
            if ((supportedEdges[edge.firstCamera to edge.secondCamera] ?: 0) < edge.inliers)
                failures += AlignmentRejection.MATCH_EDGE_INVALID
        }
        return AlignmentValidationResult(
            rejections = failures,
            cameraCount = checkpoint.cameras.size,
            trackCount = checkpoint.tracks.size,
            observationCount = observations,
            maximumBaseline = maximumBaseline,
            maximumRotationError = maximumRotationError,
            maximumTrackReprojectionRms = maximumRms,
        )
    }

    private fun rotationError(rotation: List<Double>): Pair<Double, Double> {
        var maximumError = 0.0
        for (row in 0 until 3) for (other in 0 until 3) {
            var dot = 0.0
            for (column in 0 until 3) dot += rotation[row * 3 + column] * rotation[other * 3 + column]
            maximumError = max(maximumError, abs(dot - if (row == other) 1.0 else 0.0))
        }
        val determinant =
            rotation[0] * (rotation[4] * rotation[8] - rotation[5] * rotation[7]) -
                rotation[1] * (rotation[3] * rotation[8] - rotation[5] * rotation[6]) +
                rotation[2] * (rotation[3] * rotation[7] - rotation[4] * rotation[6])
        return maximumError to determinant
    }

    private fun cameraCenter(camera: AlignmentCamera): DoubleArray {
        val r = camera.rotationWorldToCamera
        val t = camera.translationWorldToCamera
        return DoubleArray(3) { column ->
            -(r[column] * t[0] + r[3 + column] * t[1] + r[6 + column] * t[2])
        }
    }

    private fun distance(first: DoubleArray, second: DoubleArray): Double {
        val dx = first[0] - second[0]
        val dy = first[1] - second[1]
        val dz = first[2] - second[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
