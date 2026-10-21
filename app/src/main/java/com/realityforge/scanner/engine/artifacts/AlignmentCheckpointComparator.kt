package com.realityforge.scanner.engine.artifacts

import kotlin.math.abs
import kotlin.math.max

data class AlignmentTolerance(
    val cameraRotationAbsolute: Double = 1e-12,
    val cameraTranslationAbsolute: Double = 1e-12,
    val cameraIntrinsicAbsolute: Double = 1e-12,
    val sparsePointAbsolute: Double = 1e-12,
    val reprojectionRmsAbsolute: Double = 1e-12,
) {
    init {
        require(
            listOf(
                cameraRotationAbsolute,
                cameraTranslationAbsolute,
                cameraIntrinsicAbsolute,
                sparsePointAbsolute,
                reprojectionRmsAbsolute,
            ).all { it.isFinite() && it >= 0.0 },
        ) { "Alignment tolerances must be finite and non-negative" }
    }
}

data class AlignmentComparison(
    val missingCameraIds: Set<String>,
    val missingTrackIds: Set<String>,
    val maximumCameraRotationAbsolute: Double,
    val maximumCameraTranslationAbsolute: Double,
    val maximumCameraIntrinsicAbsolute: Double,
    val maximumSparsePointAbsolute: Double,
    val maximumReprojectionRmsAbsolute: Double,
    val withinTolerance: Boolean,
)

object AlignmentCheckpointComparator {
    fun compare(
        expected: AlignmentCheckpoint,
        restored: AlignmentCheckpoint,
        tolerance: AlignmentTolerance = AlignmentTolerance(),
    ): AlignmentComparison {
        val expectedCameras = expected.cameras.associateBy { it.photoId }
        val restoredCameras = restored.cameras.associateBy { it.photoId }
        val missingCameraIds = (expectedCameras.keys - restoredCameras.keys) +
            (restoredCameras.keys - expectedCameras.keys)
        var maxRotation = 0.0
        var maxTranslation = 0.0
        var maxIntrinsic = 0.0
        (expectedCameras.keys intersect restoredCameras.keys).forEach { id ->
            val first = requireNotNull(expectedCameras[id])
            val second = requireNotNull(restoredCameras[id])
            first.rotationWorldToCamera.indices.forEach { index ->
                maxRotation = max(maxRotation, abs(first.rotationWorldToCamera[index] - second.rotationWorldToCamera[index]))
            }
            first.translationWorldToCamera.indices.forEach { index ->
                maxTranslation = max(maxTranslation, abs(first.translationWorldToCamera[index] - second.translationWorldToCamera[index]))
            }
            listOf(first.fx to second.fx, first.fy to second.fy, first.cx to second.cx, first.cy to second.cy).forEach {
                maxIntrinsic = max(maxIntrinsic, abs(it.first - it.second))
            }
            if (first.width != second.width || first.height != second.height) maxIntrinsic = Double.POSITIVE_INFINITY
        }

        val expectedTracks = expected.tracks.associateBy { it.trackId }
        val restoredTracks = restored.tracks.associateBy { it.trackId }
        val missingTrackIds = (expectedTracks.keys - restoredTracks.keys) +
            (restoredTracks.keys - expectedTracks.keys)
        var maxPoint = 0.0
        var maxRms = 0.0
        (expectedTracks.keys intersect restoredTracks.keys).forEach { id ->
            val first = requireNotNull(expectedTracks[id])
            val second = requireNotNull(restoredTracks[id])
            maxPoint = max(maxPoint, abs(first.x - second.x))
            maxPoint = max(maxPoint, abs(first.y - second.y))
            maxPoint = max(maxPoint, abs(first.z - second.z))
            maxRms = max(maxRms, abs(first.reprojectionRmsNormalized - second.reprojectionRmsNormalized))
            if (first.observations != second.observations ||
                first.red != second.red || first.green != second.green || first.blue != second.blue
            ) {
                maxPoint = Double.POSITIVE_INFINITY
            }
        }

        val within = missingCameraIds.isEmpty() && missingTrackIds.isEmpty() &&
            expected.binding == restored.binding &&
            expected.inputSnapshotRecordHash == restored.inputSnapshotRecordHash &&
            expected.identity.identityHash == restored.identity.identityHash &&
            maxRotation <= tolerance.cameraRotationAbsolute &&
            maxTranslation <= tolerance.cameraTranslationAbsolute &&
            maxIntrinsic <= tolerance.cameraIntrinsicAbsolute &&
            maxPoint <= tolerance.sparsePointAbsolute &&
            maxRms <= tolerance.reprojectionRmsAbsolute
        return AlignmentComparison(
            missingCameraIds,
            missingTrackIds,
            maxRotation,
            maxTranslation,
            maxIntrinsic,
            maxPoint,
            maxRms,
            within,
        )
    }
}
