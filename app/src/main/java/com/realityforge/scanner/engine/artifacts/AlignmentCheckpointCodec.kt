package com.realityforge.scanner.engine.artifacts

object AlignmentCheckpointCodec {
    private const val MAGIC = "RFAC"
    private const val WIRE_VERSION = 1
    private const val SCHEMA_VERSION = 1
    const val MAX_CHECKPOINT_BYTES: Int = 128 * 1024 * 1024

    fun encode(checkpoint: AlignmentCheckpoint): ByteArray {
        val payload = CanonicalWriter(MAX_CHECKPOINT_BYTES).apply {
            int(SCHEMA_VERSION)
            binding(checkpoint.binding)
            hash(checkpoint.inputSnapshotRecordHash)
            stageIdentity(checkpoint.identity)
            acceptance(checkpoint.acceptance)
            count(checkpoint.cameras.size, AlignmentCheckpoint.MAX_CAMERAS, "alignment camera")
            checkpoint.cameras.forEach { camera ->
                string(camera.photoId, 160)
                int(camera.width)
                int(camera.height)
                double(camera.fx)
                double(camera.fy)
                double(camera.cx)
                double(camera.cy)
                camera.rotationWorldToCamera.forEach(::double)
                camera.translationWorldToCamera.forEach(::double)
            }
            count(checkpoint.verifiedMatchGraph.size, AlignmentCheckpoint.MAX_MATCH_EDGES, "verified match edge")
            checkpoint.verifiedMatchGraph.forEach { edge ->
                int(edge.firstCamera)
                int(edge.secondCamera)
                int(edge.verifiedMatches)
                int(edge.inliers)
                double(edge.medianErrorNormalized)
            }
            count(checkpoint.tracks.size, AlignmentCheckpoint.MAX_TRACKS, "sparse track")
            checkpoint.tracks.forEach { track ->
                string(track.trackId, 160)
                double(track.x)
                double(track.y)
                double(track.z)
                int(track.red)
                int(track.green)
                int(track.blue)
                double(track.reprojectionRmsNormalized)
                count(track.observations.size, SparseTrack.MAX_OBSERVATIONS_PER_TRACK, "track observation")
                track.observations.forEach { observation ->
                    int(observation.cameraIndex)
                    int(observation.featureIndex)
                    double(observation.xNormalized)
                    double(observation.yNormalized)
                }
            }
        }.toByteArray()
        return WireEnvelope.encode(MAGIC, WIRE_VERSION, payload, MAX_CHECKPOINT_BYTES)
    }

    fun decode(encoded: ByteArray): AlignmentCheckpoint = decodeArtifactContract("alignment checkpoint") {
        val payload = WireEnvelope.decode(encoded, MAGIC, WIRE_VERSION, MAX_CHECKPOINT_BYTES)
        val reader = CanonicalReader(payload, MAX_CHECKPOINT_BYTES)
        val schema = reader.int("alignment checkpoint schema")
        if (schema != SCHEMA_VERSION) {
            throw ArtifactFormatException("Unsupported alignment checkpoint schema: $schema")
        }
        val binding = reader.binding()
        val snapshotHash = reader.hash("input snapshot record SHA-256")
        val identity = reader.stageIdentity()
        val acceptance = reader.acceptance()
        val cameraCount = reader.count(AlignmentCheckpoint.MAX_CAMERAS, 156, "alignment camera")
        val cameras = ArrayList<AlignmentCamera>(cameraCount)
        repeat(cameraCount) {
            val photoId = reader.string(160, "alignment camera photo ID")
            val width = reader.int("alignment camera width")
            val height = reader.int("alignment camera height")
            val fx = reader.double("alignment camera fx")
            val fy = reader.double("alignment camera fy")
            val cx = reader.double("alignment camera cx")
            val cy = reader.double("alignment camera cy")
            val rotation = List(9) { reader.double("alignment camera rotation") }
            val translation = List(3) { reader.double("alignment camera translation") }
            cameras += AlignmentCamera(photoId, width, height, fx, fy, cx, cy, rotation, translation)
        }
        val edgeCount = reader.count(AlignmentCheckpoint.MAX_MATCH_EDGES, 24, "verified match edge")
        val edges = ArrayList<VerifiedMatchEdge>(edgeCount)
        repeat(edgeCount) {
            edges += VerifiedMatchEdge(
                firstCamera = reader.int("match edge first camera"),
                secondCamera = reader.int("match edge second camera"),
                verifiedMatches = reader.int("verified match count"),
                inliers = reader.int("match edge inlier count"),
                medianErrorNormalized = reader.double("match edge median error"),
            )
        }
        val trackCount = reader.count(AlignmentCheckpoint.MAX_TRACKS, 52, "sparse track")
        val tracks = ArrayList<SparseTrack>(trackCount)
        var totalObservations = 0L
        repeat(trackCount) {
            val trackId = reader.string(160, "sparse track ID")
            val x = reader.double("sparse track x")
            val y = reader.double("sparse track y")
            val z = reader.double("sparse track z")
            val red = reader.int("sparse track red")
            val green = reader.int("sparse track green")
            val blue = reader.int("sparse track blue")
            val rms = reader.double("sparse track reprojection RMS")
            val observationCount = reader.count(
                SparseTrack.MAX_OBSERVATIONS_PER_TRACK,
                24,
                "track observation",
            )
            totalObservations += observationCount
            if (totalObservations > AlignmentCheckpoint.MAX_TOTAL_OBSERVATIONS) {
                throw ArtifactFormatException("Sparse track observation set is too large")
            }
            val observations = ArrayList<TrackObservation>(observationCount)
            repeat(observationCount) {
                observations += TrackObservation(
                    cameraIndex = reader.int("track observation camera"),
                    featureIndex = reader.int("track observation feature"),
                    xNormalized = reader.double("track observation x"),
                    yNormalized = reader.double("track observation y"),
                )
            }
            tracks += SparseTrack(trackId, x, y, z, red, green, blue, rms, observations)
        }
        reader.finish("alignment checkpoint")
        AlignmentCheckpoint(binding, snapshotHash, identity, acceptance, cameras, edges, tracks)
    }

    fun hash(checkpoint: AlignmentCheckpoint): Sha256 = Sha256.digest(encode(checkpoint))
}
