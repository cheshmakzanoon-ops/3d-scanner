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

    @Test fun actualFilesReopenAcrossAttemptsAndCorruptedDependencyPreventsReuse(): Unit = runBlocking {
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

    @Test fun surfaceSelectionReusesOnlyExactSparseGeometryAndSeparatesAppearanceFromGeometryChanges() = runBlocking {
        val fixture = surfaceFixture()
        val next = fixture.input.copy(revision = 8)
        fun select(input: PipelineInputSnapshot, sparse: JSONObject = fixture.sparse) =
            PipelineSurfaceCache(fixture.root).load(PipelineArtifactSession.identityPlan(input,
                ArtifactProvenanceFixtures.binding(revision = input.revision, attempt = 3)), sparse)
        val unchanged = requireNotNull(select(next))
        assertNotNull(unchanged.completeTexture)
        assertEquals("surface-v2.rfmesh", unchanged.surfaceFile.name)
        assertEquals(PipelineArtifactSession.hashFile(unchanged.meshGlb).hex, unchanged.meshGlbSha256)

        val appearance = requireNotNull(select(next.copy(settings = next.settings.copy(texture = "texture-only-change"))))
        assertNull(appearance.completeTexture)
        assertEquals(unchanged.surfaceFile, appearance.surfaceFile)
        assertNull(select(next.copy(settings = next.settings.copy(dense = "changed-depth"))))
        val displaced = JSONObject(fixture.sparse.toString())
        displaced.getJSONArray("points").getJSONArray(0).put(0, 0.5)
        assertNull(select(next, displaced))
    }

    @Test fun legacySurfaceFilenameIsPreservedButNeverReusedAsCurrentSchema() = runBlocking {
        val fixture = surfaceFixture()
        val cache = PipelineSurfaceCache(fixture.root)
        val plan = PipelineArtifactSession.identityPlan(fixture.input, ArtifactProvenanceFixtures.binding())
        val accepted = requireNotNull(cache.load(plan, fixture.sparse))
        val originalBytes = accepted.surfaceFile.readBytes()
        val legacy = File(accepted.surfaceFile.parentFile, "surface-v1.rfmesh")
        assertTrue(accepted.surfaceFile.renameTo(legacy))
        assertNull(cache.load(plan, fixture.sparse))
        assertArrayEquals(originalBytes, legacy.readBytes())
        assertFalse(accepted.surfaceFile.exists())
    }

    @Test fun surfaceSelectionRejectsCorruptDepthOrMeshAndIgnoresUnacceptedNativeFiles() = runBlocking {
        val fixture = surfaceFixture()
        val cache = PipelineSurfaceCache(fixture.root)
        val plan = PipelineArtifactSession.identityPlan(fixture.input, ArtifactProvenanceFixtures.binding())
        val accepted = requireNotNull(cache.load(plan, fixture.sparse))
        val depth = File(accepted.depthDirectory, "ref-0.bin")
        val bytes = depth.readBytes()
        depth.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        assertNull(cache.load(plan, fixture.sparse))
        depth.writeBytes(bytes)
        assertNotNull(cache.load(plan, fixture.sparse))
        accepted.surfaceFile.writeBytes(accepted.surfaceFile.readBytes().copyOf(4))
        assertNull(cache.load(plan, fixture.sparse))

        val unaccepted = surfaceFixture(available = false)
        val ignored = PipelineSurfaceCache(unaccepted.root).load(PipelineArtifactSession.identityPlan(unaccepted.input,
            ArtifactProvenanceFixtures.binding()), unaccepted.sparse)
        assertNull(ignored)
        assertTrue(File(unaccepted.root, ".depth-checkpoints-m18/${sha256Utf8("synthetic-depth")}/surface-v2.rfmesh").isFile)
    }

    @Test fun calibrationIdentityHashesExactBoundedBytesAndRejectsCorruptSizeBeforeAllocation() {
        val directory = temporary.newFolder("calibration")
        val file = File(directory, "lens-profile.json")
        assertEquals(sha256Utf8("pinhole-no-profile-v1"), ProductionPipelineSnapshot.calibrationSha256(file))
        // Hashing validates byte ownership/framing; CalibrationProfileCodec separately validates JSON semantics.
        val bytes = "synthetic-profile-byte-identity-\u00e9\u00e9\u00e9".toByteArray(Charsets.UTF_8)
        file.writeBytes(bytes)
        assertEquals(Sha256.digest(bytes).hex, ProductionPipelineSnapshot.calibrationSha256(file))
        file.writeBytes(ByteArray(com.realityforge.scanner.capture.calibration.CalibrationProfileCodec.MAX_PROFILE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { ProductionPipelineSnapshot.calibrationSha256(file) }
        assertThrows(IllegalArgumentException::class.java) { ProductionPipelineSnapshot.calibrationSha256(directory) }
    }

    private data class SurfaceFixture(val root: File, val input: PipelineInputSnapshot, val sparse: JSONObject)

    /** Opaque synthetic native payloads test the Kotlin reference gate only; native decoder tests use real RF mesh bytes. */
    private suspend fun surfaceFixture(available: Boolean = true): SurfaceFixture {
        // Each fixture gets isolation while retaining the production project-directory identity.
        val root = File(temporary.newFolder(), "project-9").apply { check(mkdirs()) }
        val input = input()
        val session = PipelineArtifactSession(root, input, ArtifactProvenanceFixtures.binding())
        session.prepared()
        fun file(name: String, bytes: ByteArray = "synthetic-opaque-stage-payload-$name".toByteArray()): File =
            File(root, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        session.record(ArtifactStage.FEATURES,
            listOf(PipelineStageFile(file("features.bin"), RepresentationKind.FEATURE_SET)), "synthetic-feature-boundary")
        val full = ArtifactProvenanceFixtures.checkpoint(session.plan)
        val matches = FeatureMatchRecord(DoubleArray(full.tracks.size * 7) { index ->
            val track = full.tracks[index / 7]
            when (index % 7) {
                0 -> track.observations[0].xNormalized; 1 -> track.observations[0].yNormalized
                2 -> track.observations[1].xNormalized; 3 -> track.observations[1].yNormalized
                4 -> track.red.toDouble(); 5 -> track.green.toDouble(); else -> track.blue.toDouble()
            }
        }, IntArray(full.tracks.size) { it }, IntArray(full.tracks.size) { it })
        session.record(ArtifactStage.MATCHES, FeatureMatchCache(root, input.identity(AdapterStage.MATCHING), 2)
            .save(mapOf((0 to 1) to matches)), "synthetic-measured-rays")
        val sparse = geometry(full)
        val measured = MeasuredAlignmentCheckpoint.build(session.plan, sparse, full.cameras,
            full.tracks.associate { it.trackId.removePrefix("track-").toInt() to it.observations })
        session.record(ArtifactStage.ALIGNMENT, SparseAlignmentCache(root).save(session.plan, sparse, measured),
            "positive-depth-reprojection-and-connected-tracks", evidenceHash = measured.acceptance.evidenceHash)

        val key = "sparse-m16-r7-${"a".repeat(16)}"
        val depthIdentity = sha256Utf8("synthetic-depth")
        fun grid(count: Int) = JSONArray(List(count) { index ->
            JSONArray(listOf(index % 8 * .01, index / 8 * .01, 3.0, 90, 120, 150)) })
        val triangles = JSONArray()
        for (y in 0 until 3) for (x in 0 until 7) {
            val a = y * 8 + x
            triangles.put(JSONArray(listOf(a, a + 1, a + 8)))
            triangles.put(JSONArray(listOf(a + 1, a + 9, a + 8)))
        }
        val dense = JSONObject().put("schema", 7).put("representation", "dense_points").put("scale", "arbitrary")
            .put("textured", false).put("pointCount", 64).put("triangleCount", 0).put("consistentPixels", 64)
            .put("points", grid(64)).put("depthCheckpointIdentity", depthIdentity)
            .put("surfaceCheckpointAvailable", available).put("surfaceCheckpointWritten", available)
        val mesh = JSONObject().put("schema", 8).put("representation", "observed_open_triangle_mesh").put("scale", "arbitrary")
            .put("textured", false).put("watertight", false).put("vertexCount", 32).put("vertices", grid(32))
            .put("triangleCount", triangles.length()).put("triangles", triangles)
        val texture = JSONObject().put("schema", 10).put("representation", "camera_ranked_photographic_atlas")
            .put("textured", true).put("atlasWidth", 64).put("atlasHeight", 64)
            .put("triangleCount", triangles.length()).put("faceCoverage", 1.0)
        val preview = JSONObject(sparse.toString()).put("revision", 7).put("artifactKey", key)
            .put("denseStatus", "ready").put("meshStatus", "ready").put("textureStatus", "ready")
            .put("densePreview", dense).put("meshPreview", mesh).put("texturePreview", texture)
        file("$key.json", preview.toString().toByteArray())
        for (extension in listOf("dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png")) file("$key.$extension")
        for (name in listOf("ref-0.bin", "ref-1.bin", "surface-v2.rfmesh")) file(".depth-checkpoints-m18/$depthIdentity/$name")
        PipelineDownstreamCheckpoints.record(session, preview, key)
        return SurfaceFixture(root, input, sparse)
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
