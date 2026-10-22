package com.realityforge.scanner.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScanQualityCoverageReportTest {
    // Scalar-only projection of native/texture.cpp's real Middlebury compact bake.
    // Captured native-result.json SHA-256:
    // 9ec2992767e38f6ef1df8edacf038612a6eec08df8d238ba15e3c0b4c89f235b
    // This is observed stereo-surface support, NOT complete-object quality evidence.
    private fun capturedCompactTexture() = JSONObject("""{"schema":10,
        "representation":"compact_visible_photographic_atlas","triangleCount":4040,
        "inputTriangles":4040,"skippedTriangles":8,"faceCoverage":0.998019802}""")
    private fun source(texture: JSONObject) = JSONObject().put("artifactKey", "coverage-fixture")
        .put("revision", 1).put("meshPreview", JSONObject().put("triangleCount", 4040))
        .put("texturePreview", texture)
    private fun report(texture: JSONObject) = ScanQualityReport.build(source(texture), 2)
    private fun visibility(report: JSONObject) = report.getJSONObject("evidence").getJSONObject("visibility")

    @Test fun capturedCompactCountsExcludeUnsupportedFacesWithoutRemovingGeometry() {
        val source = source(capturedCompactTexture())
        val unchanged = source.toString()
        val report = ScanQualityReport.build(source, 2)
        val exported = ScanQualityReport.importValidated(ScanQualityReport.exportValidated(report))
        for (candidate in listOf(report, exported)) {
            val visibility = visibility(candidate)
            assertEquals(4040, visibility.getInt("evaluatedFaces"))
            assertEquals(4032, visibility.getInt("supportedFaces"))
            assertEquals(4032.0 / 4040, visibility.getDouble("supportedFraction"), 0.0)
            assertEquals(.998019802, candidate.getDouble("photographicallyCoveredFaceFraction"), 0.0)
            assertEquals(4040, candidate.getInt("observedMeshTriangles"))
        }
        assertEquals("Diagnostic construction must not rewrite native evidence", unchanged, source.toString())
    }

    @Test fun cameraRankedAndLegacyCountsAreNotSubtractedTwice() {
        // Explicitly synthetic legacy schema fixtures: only supported faces are retained.
        for (representation in listOf("camera_ranked_photographic_atlas", null)) {
            val texture = JSONObject().put("inputTriangles", 100).put("triangleCount", 80)
                .put("skippedTriangles", 20).put("faceCoverage", .8)
            representation?.let { texture.put("representation", it) }
            val result = visibility(report(texture))
            assertEquals(100, result.getInt("evaluatedFaces"))
            assertEquals(80, result.getInt("supportedFaces"))
            assertEquals(.8, result.getDouble("supportedFraction"), 0.0)
        }
        val unknown = visibility(report(JSONObject().put("faceCoverage", .8)))
        assertTrue(unknown.isNull("evaluatedFaces"))
        assertTrue(unknown.isNull("supportedFaces"))
        assertTrue(unknown.isNull("supportedFraction"))
    }

    @Test fun compactCountsRequireExactCompleteCoherentEvidence() {
        for (key in listOf("inputTriangles", "triangleCount", "skippedTriangles")) {
            for (bad in listOf<Any>(JSONObject.NULL, "8", 8.0, -1, 2147483648L, true, JSONObject())) {
                assertThrows("$key=$bad", IllegalArgumentException::class.java) {
                    report(capturedCompactTexture().put(key, bad))
                }
            }
            assertThrows(key, IllegalArgumentException::class.java) {
                report(capturedCompactTexture().apply { remove(key) })
            }
        }
        for ((key, bad) in listOf("inputTriangles" to 0, "triangleCount" to 4032,
            "skippedTriangles" to 4040, "skippedTriangles" to 4041,
            "faceCoverage" to 1.0, "faceCoverage" to "0.998019802",
            "faceCoverage" to JSONObject.NULL)) {
            assertThrows("$key=$bad", IllegalArgumentException::class.java) {
                report(capturedCompactTexture().put(key, bad))
            }
        }
    }

    @Test fun fullySupportedCompactFacesAndNativeDecimalRoundingStayValid() {
        for ((input, skipped, serialized) in listOf(Triple(3, 2, .3333333333),
            Triple(6, 1, .8333333333), Triple(4040, 0, 1.0))) {
            val texture = capturedCompactTexture().put("triangleCount", input).put("inputTriangles", input)
                .put("skippedTriangles", skipped).put("faceCoverage", serialized)
            assertEquals(input - skipped, visibility(report(texture)).getInt("supportedFaces"))
        }
    }
}
