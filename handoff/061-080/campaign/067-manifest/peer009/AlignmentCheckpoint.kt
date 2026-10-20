package com.realityforge.scanner.engine.artifacts

/** A registered camera in the internal x-right/y-down/z-forward world frame. */
class AlignmentCamera(
    val photoId: String,
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    rotationWorldToCamera: List<Double>,
    translationWorldToCamera: List<Double>,
) {
    val rotationWorldToCamera: List<Double> = rotationWorldToCamera.toList()
    val translationWorldToCamera: List<Double> = translationWorldToCamera.toList()

    init {
        validateToken("alignment camera photoId", photoId, 160)
        require(width in 1..200_000 && height in 1..200_000) { "Alignment camera dimensions are out of bounds" }
        require(width.toLong() * height.toLong() <= 4_000_000_000L) { "Alignment camera pixel count is out of bounds" }
        require(listOf(fx, fy, cx, cy).all { it.isFinite() }) { "Camera intrinsics must be finite" }
        require(this.rotationWorldToCamera.size == 9 && this.rotationWorldToCamera.all { it.isFinite() }) {
            "Camera rotation must contain nine finite values"
        }
        require(this.translationWorldToCamera.size == 3 && this.translationWorldToCamera.all { it.isFinite() }) {
            "Camera translation must contain three finite values"
        }
    }

    override fun equals(other: Any?): Boolean = other is AlignmentCamera &&
        photoId == other.photoId && width == other.width && height == other.height &&
        fx == other.fx && fy == other.fy && cx == other.cx && cy == other.cy &&
        rotationWorldToCamera == other.rotationWorldToCamera &&
        translationWorldToCamera == other.translationWorldToCamera

    override fun hashCode(): Int {
        var result = photoId.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + fx.hashCode()
        result = 31 * result + fy.hashCode()
        result = 31 * result + cx.hashCode()
        result = 31 * result + cy.hashCode()
        result = 31 * result + rotationWorldToCamera.hashCode()
        result = 31 * result + translationWorldToCamera.hashCode()
        return result
    }

    override fun toString(): String = "AlignmentCamera(photoId=$photoId, ${width}x$height)"

    companion object {
        fun of(
            photoId: String,
            width: Int,
            height: Int,
            fx: Double,
            fy: Double,
            cx: Double,
            cy: Double,
            rotationWorldToCamera: DoubleArray,
            translationWorldToCamera: DoubleArray,
        ): AlignmentCamera = AlignmentCamera(
            photoId, width, height, fx, fy, cx, cy,
            rotationWorldToCamera.toList(), translationWorldToCamera.toList(),
        )
    }
}

data class VerifiedMatchEdge(
    val firstCamera: Int,
    val secondCamera: Int,
    val verifiedMatches: Int,
    val inliers: Int,
    val medianErrorNormalized: Double,
) {
    init {
        require(firstCamera >= 0 && secondCamera >= 0 && firstCamera < secondCamera) {
            "Match edge camera indices must be canonical and distinct"
        }
        require(verifiedMatches >= 0 && inliers in 0..verifiedMatches) { "Match edge counts are invalid" }
        require(medianErrorNormalized.isFinite() && medianErrorNormalized >= 0.0) {
            "Match edge residual must be finite and non-negative"
        }
    }
}

data class TrackObservation(
    val cameraIndex: Int,
    val featureIndex: Int,
    val xNormalized: Double,
    val yNormalized: Double,
) {
    init {
        require(cameraIndex >= 0 && featureIndex >= 0) { "Track observation indices must be non-negative" }
        require(xNormalized.isFinite() && yNormalized.isFinite()) { "Track observation coordinates must be finite" }
    }
}

class SparseTrack(
    val trackId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val red: Int,
    val green: Int,
    val blue: Int,
    val reprojectionRmsNormalized: Double,
    observations: List<TrackObservation>,
) {
    val observations: List<TrackObservation> = observations.toList()

    init {
        validateToken("sparse track ID", trackId, 160)
        require(listOf(x, y, z, reprojectionRmsNormalized).all { it.isFinite() }) {
            "Sparse track values must be finite"
        }
        require(red in 0..255 && green in 0..255 && blue in 0..255) { "Sparse track colour is invalid" }
        require(reprojectionRmsNormalized >= 0.0) { "Sparse track reprojection RMS must be non-negative" }
        require(this.observations.size in 2..MAX_OBSERVATIONS_PER_TRACK) {
            "Sparse track observation count is out of bounds"
        }
        require(this.observations.map { it.cameraIndex }.toSet().size == this.observations.size) {
            "A sparse track cannot contain two observations from one camera"
        }
    }

    override fun equals(other: Any?): Boolean = other is SparseTrack &&
        trackId == other.trackId && x == other.x && y == other.y && z == other.z &&
        red == other.red && green == other.green && blue == other.blue &&
        reprojectionRmsNormalized == other.reprojectionRmsNormalized && observations == other.observations

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        result = 31 * result + red
        result = 31 * result + green
        result = 31 * result + blue
        result = 31 * result + reprojectionRmsNormalized.hashCode()
        result = 31 * result + observations.hashCode()
        return result
    }

    override fun toString(): String = "SparseTrack(trackId=$trackId, observations=${observations.size})"

    companion object { const val MAX_OBSERVATIONS_PER_TRACK: Int = 4_096 }
}

/**
 * Persisted accepted sparse alignment. The envelope SHA protects bytes; validation below also
 * checks camera/track geometry so a valid checksum is never treated as proof of valid geometry.
 */
class AlignmentCheckpoint(
    val binding: ArtifactBinding,
    val inputSnapshotRecordHash: Sha256,
    val identity: StageIdentity,
    val acceptance: StageAcceptance,
    cameras: List<AlignmentCamera>,
    verifiedMatchGraph: List<VerifiedMatchEdge>,
    tracks: List<SparseTrack>,
) {
    val cameras: List<AlignmentCamera> = cameras.toList()
    val verifiedMatchGraph: List<VerifiedMatchEdge> = verifiedMatchGraph.toList()
    val tracks: List<SparseTrack> = tracks.toList()

    init {
        require(identity.stage == PipelineStage.ALIGNMENT) { "Alignment checkpoint requires an ALIGNMENT identity" }
        require(acceptance.kind == AcceptanceKind.GEOMETRY && acceptance.accepted) {
            "Alignment checkpoint requires accepted geometry evidence"
        }
        require(this.cameras.size in 2..MAX_CAMERAS) { "Alignment camera count is out of bounds" }
        require(this.cameras.map { it.photoId }.toSet().size == this.cameras.size) { "Alignment camera photo IDs must be unique" }
        require(this.verifiedMatchGraph.size <= MAX_MATCH_EDGES) { "Verified match graph is too large" }
        require(this.verifiedMatchGraph.map { it.firstCamera to it.secondCamera }.toSet().size == this.verifiedMatchGraph.size) {
            "Verified match edges must be unique"
        }
        require(this.tracks.size in 1..MAX_TRACKS) { "Sparse track count is out of bounds" }
        require(this.tracks.map { it.trackId }.toSet().size == this.tracks.size) { "Sparse track IDs must be unique" }
        require(this.tracks.sumOf { it.observations.size.toLong() } <= MAX_TOTAL_OBSERVATIONS) {
            "Sparse track observation set is too large"
        }
    }

    override fun equals(other: Any?): Boolean = other is AlignmentCheckpoint &&
        binding == other.binding && inputSnapshotRecordHash == other.inputSnapshotRecordHash &&
        identity == other.identity && acceptance == other.acceptance && cameras == other.cameras &&
        verifiedMatchGraph == other.verifiedMatchGraph && tracks == other.tracks

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + inputSnapshotRecordHash.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + acceptance.hashCode()
        result = 31 * result + cameras.hashCode()
        result = 31 * result + verifiedMatchGraph.hashCode()
        result = 31 * result + tracks.hashCode()
        return result
    }

    override fun toString(): String = "AlignmentCheckpoint(cameras=${cameras.size}, tracks=${tracks.size})"

    companion object {
        const val MAX_CAMERAS: Int = 4_096
        const val MAX_MATCH_EDGES: Int = 250_000
        const val MAX_TRACKS: Int = 1_000_000
        const val MAX_TOTAL_OBSERVATIONS: Long = 8_000_000
    }
}
