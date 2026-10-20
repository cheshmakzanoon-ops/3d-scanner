package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.reopening.PreviewUiSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewUiSummaryTest {
    @Test fun exactViewerIdentityAndAllCurrentUiMeasurementsSurviveProjection() {
        val source = fixture()
        val summary = PreviewUiSummary.fromValidated(source)
        assertEquals("artifact-summary-v1", summary.getString("artifactKey"))
        assertEquals(9L, summary.getLong("revision"))
        assertEquals(125, summary.getInt("pointCount"))
        assertEquals(3, summary.getInt("registeredCameras"))
        assertEquals(2.25, summary.getDouble("medianParallaxDegrees"), 0.0)
        assertEquals(72, summary.getJSONObject("texturePreview").getInt("triangleCount"))
        for (key in listOf("densePreview", "meshPreview", "texturePreview")) {
            val original = source.getJSONObject(key)
            val copied = summary.getJSONObject(key)
            for (field in original.keySet()) {
                val value = original.get(field)
                if (value !is JSONObject && value !is JSONArray)
                    assertEquals("$key.$field", value, copied.get(field))
            }
        }
        assertTrue(summary.getJSONObject("scanDiagnostics").isNull("reciprocalDepthFraction"))
        assertEquals("Move sideways for more overlap.", summary.getJSONObject("scanDiagnostics")
            .getJSONArray("recommendations").getJSONObject(0).getString("action"))
    }

    @Test fun geometryAndHistoriesAreNeverSerializedOrRetained() {
        val unreadable = object : JSONObject() {
            override fun toString(): String = throw AssertionError("Source JSON must never be serialized")
        }
        val source = fixture(unreadable)
        val geometry = JSONArray().put(object {
            override fun toString(): String = throw AssertionError("Geometry must never be serialized")
        })
        source.put("points", geometry).put("cameras", geometry).put("tracks", geometry)
        source.getJSONObject("densePreview").put("points", geometry).put("selectedPhotoIds", geometry)
        source.getJSONObject("meshPreview").put("vertices", geometry).put("triangles", geometry)
        source.getJSONObject("texturePreview").put("triangles", geometry).put("atlasPixels", geometry)
        source.getJSONObject("scanDiagnostics").put("events", geometry).put("progressEvents", geometry)
            .put("stageFailures", geometry).put("evidence", unreadable)
        val summary = PreviewUiSummary.fromValidated(source)
        val serialized = summary.toString()
        assertTrue(serialized.length < 5000)
        for (key in listOf("points", "cameras", "tracks", "vertices", "triangles", "atlasPixels", "selectedPhotoIds",
            "events", "progressEvents", "stageFailures", "evidence")) {
            assertFalse("Retained $key", serialized.contains("\"$key\""))
        }
    }

    @Test fun nestedMutationCannotChangeTheSavedUiSnapshotInEitherDirection() {
        val source = fixture()
        val summary = PreviewUiSummary.fromValidated(source)
        source.put("pointCount", 1)
        source.getJSONObject("densePreview").put("pointCount", 1)
        source.getJSONObject("scanDiagnostics").getJSONArray("recommendations").getJSONObject(0)
            .put("action", "Changed source")
        assertEquals(125, summary.getInt("pointCount"))
        assertEquals(220, summary.getJSONObject("densePreview").getInt("pointCount"))
        assertEquals("Move sideways for more overlap.", summary.getJSONObject("scanDiagnostics")
            .getJSONArray("recommendations").getJSONObject(0).getString("action"))
        summary.getJSONObject("texturePreview").put("triangleCount", 2)
        assertEquals(72, source.getJSONObject("texturePreview").getInt("triangleCount"))
        assertNotSame(source.getJSONObject("meshPreview"), summary.getJSONObject("meshPreview"))
    }

    @Test fun onlyFirstFourActionsAreReadAndEachIsBoundedWithoutAnUnpairedSurrogate() {
        val source = fixture()
        val actions = JSONArray()
        repeat(4) { actions.put(JSONObject().put("action", "a".repeat(255) + "\uD83D\uDE00" + "z".repeat(2000))) }
        actions.put(object : JSONObject() {
            override fun opt(key: String): Any? = throw AssertionError("A fifth action must never be inspected")
        })
        source.getJSONObject("scanDiagnostics").put("recommendations", actions)
        source.put("denseFailure", "\n" + "q".repeat(3000))
        val summary = PreviewUiSummary.fromValidated(source)
        val retained = summary.getJSONObject("scanDiagnostics").getJSONArray("recommendations")
        assertEquals(4, retained.length())
        for (index in 0 until retained.length()) {
            val action = retained.getJSONObject(index).getString("action")
            assertEquals(255, action.length)
            assertFalse(Character.isHighSurrogate(action.last()))
            assertEquals(setOf("action"), retained.getJSONObject(index).keySet())
        }
        assertEquals(PreviewUiSummary.MAX_TEXT_CHARS, summary.getString("denseFailure").length)
        assertFalse(summary.getString("denseFailure").any(Char::isISOControl))
    }

    @Test fun absentResultsStayAbsentAndUnknownMeasurementsStayUnknown() {
        val source = JSONObject().put("artifactKey", "artifact-summary-v1").put("revision", 9)
            .put("pointCount", 2).put("registeredCameras", 2).put("textureFailure", "No usable photographs")
            .put("scanDiagnostics", JSONObject().put("reciprocalDepthFraction", JSONObject.NULL)
                .put("photographicallyCoveredFaceFraction", JSONObject.NULL))
        val summary = PreviewUiSummary.fromValidated(source)
        assertFalse(summary.has("densePreview"))
        assertFalse(summary.has("meshPreview"))
        assertFalse(summary.has("texturePreview"))
        assertFalse(summary.has("medianParallaxDegrees"))
        assertEquals("No usable photographs", summary.getString("textureFailure"))
        assertTrue(summary.getJSONObject("scanDiagnostics").isNull("photographicallyCoveredFaceFraction"))
    }

    @Test fun invalidIdentityIsRejectedWithoutTruncationAndOptionalScalarsAreNeverCoerced() {
        for (identity in listOf("", "a".repeat(PreviewUiSummary.MAX_IDENTIFIER_CHARS + 1), "key\nother"))
            assertThrows(IllegalArgumentException::class.java) {
                PreviewUiSummary.fromValidated(fixture().put("artifactKey", identity))
            }
        for (revision in listOf<Any>(-1, "9", 9.5, JSONObject.NULL))
            assertThrows(IllegalArgumentException::class.java) {
                PreviewUiSummary.fromValidated(fixture().put("revision", revision))
            }
        val source = fixture().put("medianParallaxDegrees", "2.25").put("pointCount", "125")
        source.getJSONObject("meshPreview").put("triangleCount", JSONArray()).put("watertight", "true")
        val summary = PreviewUiSummary.fromValidated(source)
        assertFalse(summary.has("medianParallaxDegrees"))
        assertFalse(summary.has("pointCount"))
        assertFalse(summary.getJSONObject("meshPreview").has("triangleCount"))
        assertFalse(summary.getJSONObject("meshPreview").has("watertight"))
    }

    private fun fixture(source: JSONObject = JSONObject()): JSONObject = source
        .put("artifactKey", "artifact-summary-v1").put("revision", 9L).put("pointCount", 125)
        .put("registeredCameras", 3).put("medianParallaxDegrees", 2.25)
        .put("densePreview", JSONObject().put("pointCount", 220).put("sourcePhotos", 3)
            .put("rectifiedWidth", 256).put("rectifiedHeight", 192).put("pixelStride", 2)
            .put("excludedRegisteredCameraCount", 1).put("writtenDepthMaps", 0).put("geometryReused", true))
        .put("meshPreview", JSONObject().put("triangleCount", 90).put("vertexCount", 55).put("watertight", false))
        .put("texturePreview", JSONObject().put("atlasWidth", 512).put("atlasHeight", 256)
            .put("triangleCount", 72).put("faceCoverage", 0.8).put("viewSwitchEdgesBefore", 16)
            .put("viewSwitchEdges", 7).put("exposureSamples", 20))
        .put("scanDiagnostics", JSONObject().put("status", "PARTIAL_OPEN_MESH").put("registeredCameraCount", 3)
            .put("importedPhotoCount", 4).put("observedMeshTriangles", 90)
            .put("reciprocalDepthFraction", JSONObject.NULL).put("photographicallyCoveredFaceFraction", 0.8)
            .put("recommendations", JSONArray().put(JSONObject().put("action", "Move sideways for more overlap.")
                .put("privateContext", "Must not enter the UI summary"))))
}
