package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import com.realityforge.scanner.engine.pipeline.*
import com.realityforge.scanner.engine.pipeline.PipelineStage as AdapterStage
import com.realityforge.scanner.work.execution.PipelineJobCheckpointValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic rays, actual production bytes/manifests/readers; never a source-text assertion. */
class ProductionCheckpointFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualFilesReopenAcrossAttemptsAndCorruptedDependencyPreventsReuse() = runBlocking {
        val root = temporary.newFolder("project-9")
        val input = input()
        val acknowledgements = mutableListOf<JobStageCursor>()
        val session = PipelineArtifactSession(root, input, ArtifactProvenanceFixtures.binding(),
            PipelineCheckpointSink { acknowledgements += it })
        session.prepared()
        val features = File(root, "synthetic-features.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        session.record(ArtifactStage.FEATURES, listOf(PipelineStageFile(features, RepresentationKind.FEATURE_SET)), "synthetic-feature-layout")
        val cache = FeatureMatchCache(root, input.identity(AdapterStage.MATCHING), 2)
        val full = ArtifactProvenanceFixtures.checkpoint(session.plan)
        val matchRecord = FeatureMatchRecord(DoubleArray(full.tracks.size * 7) { index ->
            val track = full.tracks[index / 7]
            when (index % 7) {
                0 -> track.observations[0].xNormalized; 1 -> track.observations[0].yNormalized
                2 -> track.observations[1].xNormalized; 3 -> track.observations[1].yNormalized
                4 -> track.red.toDouble(); 5 -> track.green.toDouble(); else -> track.blue.toDouble()
            }
        }, IntArray(full.tracks.size) { it }, IntArray(full.tracks.size) { it })
        session.record(ArtifactStage.MATCHES, cache.save(mapOf((0 to 1) to matchRecord)), "synthetic-reciprocal-matches")
        val geometry = geometry(full)
        val measured = MeasuredAlignmentCheckpoint.build(session.plan, geometry, full.cameras,
            full.tracks.associate { it.trackId.removePrefix("track-").toInt() to it.observations })
        val alignment = SparseAlignmentCache(root)
        val references = alignment.save(session.plan, geometry, measured)
        session.record(ArtifactStage.ALIGNMENT, references, "positive-depth-reprojection-and-connected-tracks",
            evidenceHash = measured.acceptance.evidenceHash)
        assertEquals(listOf(ReconstructionStage.PREPARED, ReconstructionStage.FEATURES,
            ReconstructionStage.MATCHES, ReconstructionStage.SPARSE), acknowledgements.map { it.acceptedStage })
        PipelineJobCheckpointValidator.validateArtifacts(root, job(acknowledgements.last()), input)

        val next = PipelineArtifactSession(root, input, ArtifactProvenanceFixtures.binding(attempt = 3))
        val restored = requireNotNull(SparseAlignmentCache(root).load(next.plan))
        assertEquals(measured.tracks, restored.checkpoint.tracks)
        assertEquals(measured.cameras, restored.checkpoint.cameras)
        assertEquals(3L, restored.checkpoint.binding.attempt)
        assertNotEquals(measured.inputSnapshotRecordHash, restored.checkpoint.inputSnapshotRecordHash)
        MeasuredAlignmentCheckpoint.requireSameGeometry(restored.geometry, restored.checkpoint)

        features.writeBytes(ByteArray(64) { 99 })
        assertNull(SparseAlignmentCache(root).load(next.plan))
        assertThrows(IllegalArgumentException::class.java) {
            PipelineJobCheckpointValidator.validateArtifacts(root, job(acknowledgements.last()), input)
        }
    }

    @Test fun matchingCacheRejectsTruncationAndChangedOpticsWithoutDecodingUnboundedRows() {
        val root = temporary.newFolder("match-project")
        val input = input()
        val cache = FeatureMatchCache(root, input.identity(AdapterStage.MATCHING), 2)
        val rows = FeatureMatchRecord(doubleArrayOf(.1, .2, .3, .4, 12.0, 34.0, 56.0), intArrayOf(4), intArrayOf(8))
        cache.save(mapOf((0 to 1) to rows))
        val reopened = requireNotNull(FeatureMatchCache(root, input.identity(AdapterStage.MATCHING), 2).load())
        assertArrayEquals(rows.rows, reopened.getValue(0 to 1).rows, 0.0)
        assertArrayEquals(rows.firstKeys, reopened.getValue(0 to 1).firstKeys)
        val different = input.copy(calibrationIdentity = sha256Utf8("different optics"))
        assertNull(FeatureMatchCache(root, different.identity(AdapterStage.MATCHING), 2).load())
        cache.file.writeBytes(cache.file.readBytes().copyOf(30))
        assertNull(cache.load())
    }

    @Test fun geometrySummaryCannotReplaceCoordinatesWithStringsRepeatTracksOrRoundColours() {
        val full = ArtifactProvenanceFixtures.checkpoint()
        val good = geometry(full)
        MeasuredAlignmentCheckpoint.requireSameGeometry(good, full)
        val numericString = JSONObject(good.toString())
        numericString.getJSONArray("points").getJSONArray(0).put(0, "0.0")
        assertThrows(IllegalArgumentException::class.java) { MeasuredAlignmentCheckpoint.requireSameGeometry(numericString, full) }
        val roundedColour = JSONObject(good.toString())
        roundedColour.getJSONArray("points").getJSONArray(0).put(3, 90.1)
        assertThrows(IllegalArgumentException::class.java) { MeasuredAlignmentCheckpoint.requireSameGeometry(roundedColour, full) }
        val repeated = JSONObject(good.toString())
        repeated.getJSONArray("points").put(1, repeated.getJSONArray("points").getJSONArray(0))
        assertThrows(IllegalArgumentException::class.java) { MeasuredAlignmentCheckpoint.requireSameGeometry(repeated, full) }
    }

    private fun input() = PipelineInputSnapshot("project-9", 7, listOf(
        PipelinePhotoInput("a", sha256Utf8("a"), 4032, 3024, 1000, 26.0, sha256Utf8("meta-a")),
        PipelinePhotoInput("b", sha256Utf8("b"), 4032, 3024, 1000, 26.0, sha256Utf8("meta-b"))),
        sha256Utf8("calibration"), "synthetic-checkpoint-fixture-v1",
        PipelineStageSettings("f1", "m1", "s1", "d1", "surface1", "texture1", "export1"))

    private fun geometry(checkpoint: AlignmentCheckpoint) = JSONObject().put("schema", 3)
        .put("representation", "sparse_points").put("scale", "arbitrary")
        .put("pointCount", checkpoint.tracks.size).put("registeredCameras", checkpoint.cameras.size)
        .put("points", JSONArray(checkpoint.tracks.map { track -> JSONArray(listOf(track.x, track.y, track.z,
            track.red, track.green, track.blue, 0, 0, track.trackId.removePrefix("track-").toInt())) }))
        .put("cameras", JSONArray(checkpoint.cameras.map { camera -> JSONObject().put("photoId", camera.photoId)
            .put("rotation", JSONArray(camera.rotationWorldToCamera)).put("translation", JSONArray(camera.translationWorldToCamera)) }))

    private fun job(cursor: JobStageCursor): JobSnapshot {
        val hash = "a".repeat(64)
        val input = JobInputSnapshot("project-9", 7, "request-9", hash, hash, hash, hash, "synthetic")
        return JobSnapshot(JobId("job-9"), input.identityKey(), input, ReconstructionJobState.PAUSED,
            cursor, 3, null, null, null, null, null, null, 1, 2, 0)
    }
}
