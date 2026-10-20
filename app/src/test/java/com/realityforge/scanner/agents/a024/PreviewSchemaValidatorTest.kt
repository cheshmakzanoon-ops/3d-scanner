package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.reopening.*
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
}
