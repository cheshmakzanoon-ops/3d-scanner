package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.reopening.*
import com.realityforge.scanner.engine.artifacts.ArtifactBinding
import com.realityforge.scanner.engine.pipeline.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewSchemaValidatorTest {
    private val key = "sparse-m19-r3-0123456789abcdef"
    private fun fixture() = JSONObject().put("schema", 1).put("representation", "sparse_points")
        .put("scale", "arbitrary").put("revision", 3).put("artifactKey", key)
        .put("registeredCameras", 2).put("pointCount", 1)
        .put("points", JSONArray().put(JSONArray(listOf(1.25, -2.5, 3.75, 12, 128, 255))))

    @Test fun legacyGeometryIsPreservedAndNeverInventsMissingSurface() {
        for (schema in listOf(1, 3, 6)) {
            val result = PreviewSchemaValidator.validate(fixture().put("schema", schema), 3, key) as ReopenResult.Ready
            assertEquals(DisplayPoint(1.25, -2.5, 3.75, 12, 128, 255), result.model.sparsePoints.single())
            assertEquals(setOf(ScanRepresentation.SPARSE_POINTS), result.model.capabilities)
            assertTrue(result.model.unavailableReasons.keys.containsAll(listOf("dense", "mesh", "texture")))
        }
    }

    @Test fun revisionValidMutationCorpusReturnsTypedFailure() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.remove("points") }, { it.put("points", "[]") }, { it.put("pointCount", 2) },
            { it.put("pointCount", "1") }, { it.put("schema", 99) }, { it.put("registeredCameras", -1) },
            { it.getJSONArray("points").getJSONArray(0).put(0, "1.0") },
            { it.getJSONArray("points").getJSONArray(0).put(0, 1e200) },
            { it.getJSONArray("points").getJSONArray(0).put(3, 256) },
            { it.put("denseStatus", "ready") }, { it.put("revision", 4) },
            { it.put("pointCount", PreviewSchemaValidator.MAX_POINTS + 1) },
        )
        for (mutate in mutations) {
            val json = fixture(); mutate(json)
            assertTrue(json.toString(), PreviewSchemaValidator.validate(json, 3, key) is ReopenResult.Invalid)
        }
    }

    @Test fun boundedReaderRejectsOverflowFramingAndInvalidUtf8() {
        assertTrue(runCatching { BoundedJson.read("{\"x\":1}garbage".byteInputStream(), 100) }.isFailure)
        assertTrue(runCatching { BoundedJson.read("{\"x\":1}".byteInputStream(), 5) }.isFailure)
        assertTrue(runCatching { BoundedJson.read(byteArrayOf(0x7b, 0xc0.toByte(), 0xaf.toByte(), 0x7d).inputStream(), 20) }.isFailure)
        val nesting = "{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"
        assertTrue(runCatching { BoundedJson.read(nesting.byteInputStream(), 200) }.isFailure)
    }

    @Test fun cancellationIsNotMisreportedAsCorruption() {
        assertTrue(runCatching { PreviewSchemaValidator.validate(fixture(), 3, key) {
            throw java.util.concurrent.CancellationException("cancel")
        } }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    // Explicitly synthetic schema fixture; this tests the two native serialization contracts,
    // not reconstruction quality or physical capture.
    private fun texturedFixture(compact: Boolean = true, triangleCount: Int = 1, skipped: Int = 0): JSONObject {
        val vertices = JSONArray("[[0,0,1,12,128,255],[1,0,1,12,128,255],[0,1,1,12,128,255]]")
        val root = fixture().put("denseStatus", "ready").put("meshStatus", "ready").put("textureStatus", "ready")
            .put("densePreview", JSONObject().put("schema", 7).put("representation", "dense_points")
                .put("scale", "arbitrary").put("textured", false).put("triangleCount", 0)
                .put("pointCount", 3).put("points", vertices).put("surfaceCheckpointAvailable", false))
            .put("meshPreview", JSONObject().put("schema", 8).put("representation", "observed_open_triangle_mesh")
                .put("scale", "arbitrary").put("textured", false).put("watertight", false)
                .put("vertexCount", 3).put("vertices", vertices).put("triangleCount", triangleCount)
                .put("triangles", JSONArray(List(triangleCount) { JSONArray("[0,1,2]") })))
        return root.put("texturePreview", JSONObject().put("schema", 10)
            .put("representation", if (compact) "compact_visible_photographic_atlas" else "camera_ranked_photographic_atlas")
            .put("textured", true).put("watertight", false).put("scale", "arbitrary")
            .put("atlasWidth", 4).put("atlasHeight", 4).put("atlasFormat", "image/png")
            .put("triangleCount", if (compact) triangleCount else triangleCount - skipped)
            .put("inputTriangles", triangleCount).put("skippedTriangles", skipped)
            .put("faceCoverage", (triangleCount - skipped).toDouble() / triangleCount)
            .put("compactCharts", compact).put("supportedTexels", 8).put("unsupportedTexels", 2)
            .put("blendedTexels", 4).put("chartCount", 1).put("atlasUtilization", .5)
            .put("maximumUvStretch", .01).put("texelsPerArbitraryUnit", 128.0).put("meanBlendResidual", .02)
            .put("viewSwitchEdgesBefore", if (compact) JSONObject.NULL else 2)
            .put("viewSwitchEdges", if (compact) JSONObject.NULL else 1)
            .put("reassignedFaces", if (compact) JSONObject.NULL else 1).put("exposureSamples", 8))
    }

    @Test fun optionalObservationLedgerPreservesLegacyAndRejectsMixedEvidence() {
        assertTrue(PreviewSchemaValidator.validate(texturedFixture(), 3, key) is ReopenResult.Ready)
        val current = texturedFixture().apply { getJSONObject("meshPreview")
            .put("observationLedgerVersion", 1).put("inputSamples", 8).put("acceptedSamples", 7)
            .put("attachedObservationCount", 6).put("unattachedObservationCount", 1)
            .put("exactDuplicateSupportCount", 3) }
        assertTrue(PreviewSchemaValidator.validate(current, 3, key) is ReopenResult.Ready)
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.remove("observationLedgerVersion") }, { it.put("observationLedgerVersion", 2) },
            { it.remove("unattachedObservationCount") }, { it.put("attachedObservationCount", 5) },
            { it.put("exactDuplicateSupportCount", 7) }, { it.put("acceptedSamples", 9) },
            { it.put("inputSamples", 100_001) }, { it.put("attachedObservationCount", "6") },
            { it.put("unattachedObservationCount", -1) }, { it.put("schema", 9) })
        for (mutate in mutations) {
            val changed = JSONObject(current.toString());mutate(changed.getJSONObject("meshPreview"))
            assertTrue(changed.toString(), PreviewSchemaValidator.validate(changed, 3, key) is ReopenResult.Invalid)
        }
    }

    @Test fun compactNativeSchemaPreservesGeometryAndInapplicableCountersWithoutCheckpoint() {
        val json = texturedFixture()
        val encoded = json.toString()
        for (candidate in listOf(json, JSONObject(encoded))) {
            val result = PreviewSchemaValidator.validate(candidate, 3, key)
            assertTrue(result.toString(), result is ReopenResult.Ready)
            val model = (result as ReopenResult.Ready).model
            assertEquals(ScanRepresentation.entries.toSet(), model.capabilities)
            assertEquals(3, model.meshVertices.size)
            assertEquals(listOf(DisplayTriangle(0, 1, 2)), model.triangles)
            assertTrue(candidate.getJSONObject("texturePreview").isNull("viewSwitchEdges"))
            assertFalse(candidate.getJSONObject("densePreview").getBoolean("surfaceCheckpointAvailable"))
        }
        assertEquals("Validation must not rewrite evidence", encoded, json.toString())
    }

    @Test fun compactAdapterRejectsUnknownMixedAndMalformedEvidence() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("representation", "invented_atlas") },
            { it.put("representation", "camera_ranked_photographic_atlas") },
            { it.put("compactCharts", false) }, { it.put("compactCharts", "true") },
            { it.put("watertight", true) }, { it.put("scale", "metric") },
            { it.put("atlasFormat", "image/jpeg") },
            { it.remove("viewSwitchEdges") }, { it.put("viewSwitchEdges", 0) },
            { it.put("viewSwitchEdgesBefore", "null") }, { it.put("reassignedFaces", 0) },
            { it.put("inputTriangles", 2) }, { it.put("triangleCount", 0) },
            { it.remove("skippedTriangles") }, { it.put("skippedTriangles", JSONObject.NULL) },
            { it.put("skippedTriangles", "0") }, { it.put("skippedTriangles", 0.0) },
            { it.put("skippedTriangles", -1) }, { it.put("skippedTriangles", 1) },
            { it.put("skippedTriangles", 2) }, { it.put("skippedTriangles", 2147483648L) },
            { it.put("faceCoverage", .9) }, { it.put("faceCoverage", "1.0") },
            { it.put("atlasUtilization", .6) },
            { it.put("supportedTexels", 0) }, { it.put("supportedTexels", "8") },
            { it.put("unsupportedTexels", 9) }, { it.put("blendedTexels", 9) },
            { it.put("chartCount", 2) }, { it.put("atlasUtilization", 1.1) },
            { it.put("maximumUvStretch", -1) }, { it.put("meanBlendResidual", "0.0") },
            { it.put("texelsPerArbitraryUnit", 1e200) }, { it.put("exposureSamples", JSONObject.NULL) },
            { it.put("atlasWidth", 4096).put("atlasHeight", 4096) },
        )
        for (mutate in mutations) {
            val json = texturedFixture(); mutate(json.getJSONObject("texturePreview"))
            assertTrue(json.getJSONObject("texturePreview").toString(),
                PreviewSchemaValidator.validate(json, 3, key) is ReopenResult.Invalid)
        }
        val invalidMesh = texturedFixture()
        invalidMesh.getJSONObject("meshPreview").put("triangles", JSONArray("[[0,1,9]]"))
        assertTrue(PreviewSchemaValidator.validate(invalidMesh, 3, key) is ReopenResult.Invalid)
        val missingDepth = texturedFixture().apply { remove("densePreview"); put("denseStatus", "unavailable") }
        assertTrue(PreviewSchemaValidator.validate(missingDepth, 3, key) is ReopenResult.Invalid)
    }

    @Test fun compactPartialCoverageAcceptsNativePrecisionAndRejectsContradictions() {
        val json = texturedFixture(triangleCount = 3, skipped = 2)
        val texture = json.getJSONObject("texturePreview")
        // Native std::setprecision(10) roundings of 1/3 and 8/24, not invented coverage.
        texture.put("faceCoverage", .3333333333).put("atlasWidth", 6)
            .put("atlasUtilization", .3333333333)
        val encoded = json.toString()
        for (candidate in listOf(json, JSONObject(encoded))) {
            val result = PreviewSchemaValidator.validate(candidate, 3, key)
            assertTrue(result.toString(), result is ReopenResult.Ready)
            assertEquals(3, (result as ReopenResult.Ready).model.triangles.size)
        }
        assertEquals(encoded, json.toString())
        for ((key, value) in listOf("faceCoverage" to .3333333334,
            "atlasUtilization" to .3333333334, "faceCoverage" to 1.0,
            "skippedTriangles" to 1, "triangleCount" to 1)) {
            val corrupted = JSONObject(encoded)
            corrupted.getJSONObject("texturePreview").put(key, value)
            assertTrue("$key=$value", PreviewSchemaValidator.validate(corrupted, 3, this.key) is ReopenResult.Invalid)
        }
        val impossibleSupport = texturedFixture(triangleCount = 9)
        assertTrue(PreviewSchemaValidator.validate(impossibleSupport, 3, key) is ReopenResult.Invalid)
    }

    @Test fun cameraRankedPartialAtlasStillRetainsOnlySupportedFaces() {
        val json = texturedFixture(compact = false, triangleCount = 3, skipped = 2)
        assertEquals(1, json.getJSONObject("texturePreview").getInt("triangleCount"))
        val result = PreviewSchemaValidator.validate(json, 3, key)
        assertTrue(result.toString(), result is ReopenResult.Ready)
        assertEquals(3, (result as ReopenResult.Ready).model.triangles.size)
    }

    @Test fun legacyAtlasKeepsExactIntegerSwitchCountersAndRejectsCompactFlags() {
        val legacy = texturedFixture(false)
        assertTrue(PreviewSchemaValidator.validate(legacy, 3, key) is ReopenResult.Ready)
        for (bad in listOf<Any>(JSONObject.NULL, "0", 0.0, -1)) {
            val json = texturedFixture(false)
            json.getJSONObject("texturePreview").put("viewSwitchEdges", bad)
            assertTrue(PreviewSchemaValidator.validate(json, 3, key) is ReopenResult.Invalid)
        }
        val mixed = texturedFixture(false)
        mixed.getJSONObject("texturePreview").put("compactCharts", true)
        assertTrue(PreviewSchemaValidator.validate(mixed, 3, key) is ReopenResult.Invalid)
    }


    @Test fun downstreamRejectionKeepsTheTypedReasonAndDoesNotAcceptCheckpoint() = runBlocking {
        val root = Files.createTempDirectory("synthetic-invalid-preview").toFile()
        var accepted = 0
        try {
            val input = PipelineInputSnapshot("synthetic-project", 3, listOf(
                PipelinePhotoInput("a", sha256Utf8("a"), 800, 600, 1000, 26.0),
                PipelinePhotoInput("b", sha256Utf8("b"), 800, 600, 1000, 26.0)),
                sha256Utf8("calibration"), "synthetic-preview-contract-v1",
                PipelineStageSettings("f1", "m1", "s1", "d1", "surface1", "texture1", "export1"))
            val session = PipelineArtifactSession(root, input,
                ArtifactBinding(input.projectId, input.revision, "synthetic-job", 1),
                PipelineCheckpointSink { accepted++ })
            val invalid = texturedFixture().apply { getJSONObject("texturePreview").put("compactCharts", false) }
            val reason = PreviewSchemaValidator.validate(invalid, 3, key) as ReopenResult.Invalid
            try {
                PipelineDownstreamCheckpoints.record(session, invalid, key)
                fail("Rejected preview reached checkpoint acceptance")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(reason.code))
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(reason.detail))
            }
            assertEquals(0, accepted)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { assertTrue(root.deleteRecursively()) }
    }

}
