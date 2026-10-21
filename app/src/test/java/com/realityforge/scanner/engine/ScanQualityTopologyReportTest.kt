package com.realityforge.scanner.engine

import com.realityforge.scanner.data.reopening.PreviewUiSummary
import com.realityforge.scanner.engine.diagnostics.TopologyEvidence
import com.realityforge.scanner.engine.diagnostics.TopologyKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScanQualityTopologyReportTest {
    // Scalar-only projection of actual Android native schema-8 capture:
    // /tmp/android-relief-trace-v2-evidence/quality-capture/result.json
    // SHA-256: 43cb5ccbdcb9be63abd67c2d1d73a57f7c3eb89506919bd3f00485140fc95574
    private fun capturedMesh() = JSONObject("""{"schema":8,"vertexCount":5753,"triangleCount":6741,
        "watertight":false,"boundaryEdges":4873,"nonmanifoldEdges":166,
        "intersections":6190,"topologyIncomplete":false}""")
    private fun source(mesh: JSONObject) = JSONObject().put("artifactKey", "topology-fixture")
        .put("revision", 1).put("meshPreview", mesh)
    private fun report(mesh: JSONObject) = ScanQualityReport.build(source(mesh), 1)
    private fun topology(report: JSONObject) = report.getJSONObject("evidence").getJSONObject("topology")

    @Test fun capturedNativeCountsSurviveReportAndReopenProjection() {
        val mesh = capturedMesh()
        val evidence = topology(report(mesh))
        assertEquals(166, evidence.getInt("nonManifoldEdges"))
        assertEquals(6190, evidence.getInt("selfIntersections"))
        assertEquals(4873, evidence.getInt("boundaryEdges"))
        assertFalse(evidence.getBoolean("topologyIncomplete"))
        assertTrue(evidence.isNull("connectedComponents"))
        val projected = PreviewUiSummary.fromValidated(source(mesh))
        assertEquals(166, projected.getJSONObject("meshPreview").getInt("nonmanifoldEdges"))
        assertEquals(6190, projected.getJSONObject("meshPreview").getInt("intersections"))
        assertFalse(projected.getJSONObject("meshPreview").getBoolean("topologyIncomplete"))
        assertEquals(evidence.toString(), topology(report(projected.getJSONObject("meshPreview"))).toString())
        assertEquals(evidence.toString(), topology(ScanQualityReport.importValidated(
            ScanQualityReport.exportValidated(report(mesh)))).toString())
    }

    @Test fun legacyAndEqualAliasesAreSupportedButConflictsFailClosed() {
        val legacy = capturedMesh()
        legacy.put("nonManifoldEdges", legacy.remove("nonmanifoldEdges"))
        legacy.put("selfIntersections", legacy.remove("intersections"))
        assertEquals(166, topology(report(legacy)).getInt("nonManifoldEdges"))
        assertEquals(6190, topology(report(legacy)).getInt("selfIntersections"))
        val equal = capturedMesh().put("nonManifoldEdges", 166).put("selfIntersections", 6190)
        assertEquals(topology(report(legacy)).toString(), topology(report(equal)).toString())
        for ((key, value) in listOf("nonManifoldEdges" to 0, "selfIntersections" to 0)) {
            assertThrows(IllegalArgumentException::class.java) { report(capturedMesh().put(key, value)) }
        }
    }

    @Test fun topologyCountsRejectCoercionOverflowNegativeAndMalformedAliases() {
        for (key in listOf("boundaryEdges", "nonmanifoldEdges", "nonManifoldEdges",
            "intersections", "selfIntersections", "connectedComponents")) {
            for (bad in listOf<Any>("0", 0.5, 0.0, -1, 2147483648L, true, JSONObject())) {
                assertThrows("$key=$bad", IllegalArgumentException::class.java) {
                    report(capturedMesh().put(key, bad))
                }
            }
        }
        for (bad in listOf<Any>("false", 0)) {
            assertThrows(IllegalArgumentException::class.java) {
                report(capturedMesh().put("topologyIncomplete", bad))
            }
        }
    }

    private fun cleanMesh() = capturedMesh().put("boundaryEdges", 0).put("nonmanifoldEdges", 0)
        .put("intersections", 0).put("watertight", true)

    @Test fun completenessIsTriStateAndOnlyCompleteZeroChecksPromoteWatertight() {
        for (flag in listOf(false, true, null)) {
            val mesh = cleanMesh()
            if (flag == null) mesh.remove("topologyIncomplete") else mesh.put("topologyIncomplete", flag)
            val original = report(mesh)
            val reopened = ScanQualityReport.importValidated(ScanQualityReport.exportValidated(original))
            for (value in listOf(original, reopened, report(PreviewUiSummary.fromValidated(source(mesh))
                .getJSONObject("meshPreview")))) {
                val evidence = topology(value)
                assertEquals(if (flag == false) "VERIFIED_WATERTIGHT" else "OBSERVED_OPEN_SURFACE",
                    evidence.getString("kind"))
                if (flag == null) assertTrue(evidence.isNull("topologyIncomplete"))
                else assertEquals(flag, evidence.getBoolean("topologyIncomplete"))
                val conditions = value.getJSONObject("evidence").getJSONArray("conditions")
                assertEquals(flag != false, (0 until conditions.length())
                    .any { conditions.getString(it) == "TOPOLOGY_EVIDENCE_UNKNOWN" })
            }
        }
        for (key in listOf("boundaryEdges", "nonmanifoldEdges", "intersections")) {
            assertNotEquals("VERIFIED_WATERTIGHT", topology(report(cleanMesh().put(key, 1))).getString("kind"))
            val mesh = cleanMesh()
            mesh.remove(key)
            assertNotEquals("VERIFIED_WATERTIGHT", topology(report(mesh)).getString("kind"))
        }
    }

    @Test fun oldSchemaTwoRemainsReadableWithoutInventingCompleteEvidence() {
        val old = report(cleanMesh())
        topology(old).remove("topologyIncomplete")
        val normalized = ScanQualityReport.validateAndRedact(old)
        assertTrue(topology(normalized).isNull("topologyIncomplete"))
        assertEquals("OBSERVED_OPEN_SURFACE", topology(normalized).getString("kind"))
        assertEquals(0, topology(normalized).getInt("nonManifoldEdges"))
        assertEquals(normalized.toString(), ScanQualityReport.validateAndRedact(normalized).toString())
    }

    @Test fun importsRejectMalformedCountsAndContradictoryWatertightEvidence() {
        for ((key, bad) in listOf("nonManifoldEdges" to "0", "selfIntersections" to 0.5,
            "boundaryEdges" to 2147483648L, "topologyIncomplete" to "false",
            "topologyIncomplete" to true, "selfIntersections" to 1)) {
            val malformed = report(cleanMesh())
            topology(malformed).put(key, bad)
            assertThrows(IllegalArgumentException::class.java) { ScanQualityReport.validateAndRedact(malformed) }
        }
    }

    @Test fun schemaOneMigrationKeepsTopologyCompletenessUnknown() {
        val old = report(capturedMesh()).put("schema", 1)
        val migrated = ScanQualityReport.validateAndRedact(old)
        assertTrue(topology(migrated).isNull("topologyIncomplete"))
        assertTrue(topology(migrated).isNull("nonManifoldEdges"))
        assertEquals(1, migrated.getInt("migratedFromSchema"))
    }

    @Test fun explicitNullIsUnknownAndDoesNotMaskValidLegacyAliases() {
        val mesh = capturedMesh().put("nonmanifoldEdges", JSONObject.NULL)
            .put("nonManifoldEdges", 166).put("intersections", JSONObject.NULL)
            .put("selfIntersections", 6190).put("topologyIncomplete", JSONObject.NULL)
        val result = topology(ScanQualityReport.validateAndRedact(report(mesh)))
        assertEquals(166, result.getInt("nonManifoldEdges"))
        assertEquals(6190, result.getInt("selfIntersections"))
        assertTrue(result.isNull("topologyIncomplete"))
        val projected = PreviewUiSummary.fromValidated(source(mesh)).getJSONObject("meshPreview")
        assertEquals(result.toString(), topology(report(projected)).toString())
    }

    @Test fun typedWatertightEvidenceAlsoRequiresExplicitCompleteChecks() {
        for (flag in listOf<Boolean?>(null, true)) {
            assertThrows(IllegalArgumentException::class.java) {
                TopologyEvidence(TopologyKind.VERIFIED_WATERTIGHT, 0, 0,
                    selfIntersections = 0, topologyIncomplete = flag)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TopologyEvidence(TopologyKind.VERIFIED_WATERTIGHT, 0, 0, topologyIncomplete = false)
        }
        assertEquals(TopologyKind.VERIFIED_WATERTIGHT,
            TopologyEvidence(TopologyKind.VERIFIED_WATERTIGHT, 0, 0,
                selfIntersections = 0, topologyIncomplete = false).kind)
    }

    @Test fun legacyWatertightDowngradeDoesNotSanitizeContradictoryCounts() {
        for (key in listOf("boundaryEdges", "nonManifoldEdges", "selfIntersections")) {
            val old = report(cleanMesh())
            topology(old).remove("topologyIncomplete")
            topology(old).put(key, 1)
            assertThrows(IllegalArgumentException::class.java) { ScanQualityReport.validateAndRedact(old) }
        }
    }
}
