package com.realityforge.scanner.engine

import com.realityforge.scanner.data.reopening.*
import com.realityforge.scanner.export.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar/schema fixtures: not photographic or physical-quality qualification. */
class SurfaceTopologyQualificationTest {
    private val key = "sparse-m19-r3-0123456789abcdef"
    private val identity = ArtifactIdentity("11111111-1111-1111-1111-111111111111", 3, key, "a".repeat(64))
    private fun mesh(edges: Any = 509, pairs: Any = 17, incomplete: Any = false) =
        JSONObject().put("schema", 8).put("representation", "observed_open_triangle_mesh")
            .put("scale", "arbitrary").put("textured", false).put("watertight", false)
            .put("vertexCount", 3).put("triangleCount", 1).put("boundaryEdges", 3)
            .put("nonmanifoldEdges", edges).put("intersections", pairs).put("topologyIncomplete", incomplete)
            .put("vertices", JSONArray("[[0,0,1,1,2,3],[1,0,1,1,2,3],[0,1,1,1,2,3]]"))
            .put("triangles", JSONArray("[[0,1,2]]"))
    private fun preview(mesh: JSONObject = mesh(), report: Boolean = true): JSONObject {
        val result = JSONObject().put("schema", 1).put("representation", "sparse_points").put("scale", "arbitrary")
            .put("revision", 3).put("artifactKey", key).put("registeredCameras", 2).put("pointCount", 1)
            .put("denseStatus", "ready").put("meshStatus", "ready")
            .put("points", JSONArray("[[0,0,1,1,2,3]]"))
            .put("densePreview", JSONObject().put("schema", 7).put("representation", "dense_points")
                .put("scale", "arbitrary").put("textured", false).put("triangleCount", 0)
                .put("pointCount", 1).put("points", JSONArray("[[0,0,1,1,2,3]]")))
            .put("meshPreview", mesh)
        if (report) result.put("scanDiagnostics", ScanQualityReport.build(result, 2))
        return result
    }
    private fun ready(value: JSONObject = preview(), previous: Boolean = false): ScanDisplayModel {
        val result = PreviewSchemaValidator.validate(value, 3, key, previous)
        assertTrue(result.toString(), result is ReopenResult.Ready)
        return (result as ReopenResult.Ready).model
    }
    private fun codes(report: JSONObject): List<String> = report.getJSONArray("recommendations").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("code") }
    }

    @Test fun exactReopenedEvidenceReachesPreparedGeometryWithoutMutatingSource() = runBlocking {
        val source = preview(); val original = source.toString()
        val model = ready(source); val prepared = preparePreviewGeometry(model)
        assertEquals(SurfaceTopologySummary(509, 17, false), model.surfaceTopology)
        assertEquals(model.surfaceTopology, prepared.surfaceTopology)
        assertEquals(key, prepared.artifactKey); assertEquals(3L, prepared.revision)
        assertEquals(listOf(DisplayTriangle(0, 1, 2)), model.triangles)
        assertEquals(1, prepared.mesh!!.sourceTriangleCount)
        assertEquals(original, source.toString())
    }

    @Test fun legacyReportAbsenceRemainsReadableAndUnknownEvenIfUnvalidatedRawMetricsExist() = runBlocking {
        val model = ready(preview(report = false))
        assertEquals(SurfaceTopologySummary(), model.surfaceTopology)
        assertEquals(model.surfaceTopology, preparePreviewGeometry(model).surfaceTopology)
        val copy = surfaceTopologyQualification(model.surfaceTopology).joinToString(" ")
        assertTrue(copy.contains("unknown")); assertTrue(copy.contains("unavailable"))
        assertFalse(copy.contains("edges: 0")); assertFalse(copy.contains("pairs: 0"))
    }

    @Test fun canonicalNativeAndLegacyAliasesMapToTheSameSummary() {
        val legacy = mesh().apply {
            put("nonManifoldEdges", remove("nonmanifoldEdges")); put("selfIntersections", remove("intersections"))
        }
        assertEquals(ready().surfaceTopology, ready(preview(legacy)).surfaceTopology)
        for (field in listOf("nonmanifoldEdges", "intersections")) {
            assertThrows(IllegalArgumentException::class.java) { preview(mesh().put(field, "0")) }
        }
        assertThrows(IllegalArgumentException::class.java) { preview(mesh().put("nonManifoldEdges", 0)) }
    }

    @Test fun explicitNullAndPartialEvidenceNeverBecomeZeroOrComplete() {
        val summary = ready(preview(mesh(509, JSONObject.NULL, JSONObject.NULL))).surfaceTopology
        assertEquals(509, summary.nonManifoldEdges); assertNull(summary.intersectingFacePairs); assertNull(summary.checksIncomplete)
        val lines = surfaceTopologyQualification(summary).joinToString(" ")
        assertTrue(lines.contains("edges: 509")); assertTrue(lines.contains("pairs: unknown"))
        assertTrue(lines.contains("issues were detected")); assertTrue(lines.contains("completeness is unknown"))
        assertFalse(lines.contains("No non-manifold edges or intersecting face pairs were detected"))
    }

    @Test fun incompleteIntersectionAuditShowsLowerBoundAndNeverACleanCertificate() {
        for (pairs in listOf(0, 17)) {
            val summary = ready(preview(mesh(0, pairs, true))).surfaceTopology
            val lines = surfaceTopologyQualification(summary).joinToString(" ")
            assertTrue(lines.contains("pairs: at least $pairs"))
            assertTrue(lines.contains("checks were incomplete"))
            assertFalse(lines.contains("No non-manifold edges or intersecting face pairs were detected"))
        }
    }

    @Test fun completeMeasuredZerosStillDoNotCertifyWatertightnessOrPhysicalQuality() {
        val summary = ready(preview(mesh(0, 0, false))).surfaceTopology
        assertFalse(summary.hasMeasuredDefects)
        val lines = surfaceTopologyQualification(summary).joinToString(" ")
        assertTrue(lines.contains("edges: 0")); assertTrue(lines.contains("pairs: 0"))
        assertTrue(lines.contains("not a manifoldness or watertightness certificate"))
        assertTrue(lines.contains("do not establish printability"))
        assertTrue(lines.contains("physical accuracy"))
    }

    @Test fun negativeProgrammaticCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SurfaceTopologySummary(-1, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceTopologySummary(0, -1, false) }
    }

    @Test fun measuredFindingIsDerivedOnBuildAndImportWithoutChangingStatusOrCounts() {
        val original = preview().getJSONObject("scanDiagnostics")
        assertTrue(codes(original).contains("SURFACE_TOPOLOGY_DEFECTS"))
        val historical = JSONObject(original.toString()).put("recommendations", JSONArray())
        val normalized = ScanQualityReport.validateAndRedact(historical)
        assertEquals("SURFACE_TOPOLOGY_DEFECTS", codes(normalized).first())
        assertEquals(original.getString("status"), normalized.getString("status"))
        assertEquals(original.getJSONObject("evidence").getJSONObject("topology").toString(),
            normalized.getJSONObject("evidence").getJSONObject("topology").toString())
        assertEquals(normalized.toString(), ScanQualityReport.importValidated(ScanQualityReport.exportValidated(normalized)).toString())
    }

    @Test fun staleSuppliedFindingIsRemovedForZeroOrUnavailableMetrics() {
        val warning = preview().getJSONObject("scanDiagnostics").getJSONArray("recommendations")
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("code") == "SURFACE_TOPOLOGY_DEFECTS" } }
        for (value in listOf<Any>(0, JSONObject.NULL)) {
            val report = preview(mesh(value, value, false)).getJSONObject("scanDiagnostics")
            report.getJSONArray("recommendations").put(warning)
            assertFalse(codes(ScanQualityReport.validateAndRedact(report)).contains("SURFACE_TOPOLOGY_DEFECTS"))
        }
    }

    @Test fun recommendationCapDoesNotRemoveReopenedQualification() {
        val source = preview()
        source.getJSONObject("scanDiagnostics").put("recommendations", JSONArray(List(6) {
            JSONObject().put("code", "LOW_PARALLAX").put("action", "Move sideways")
        }))
        val projected = PreviewUiSummary.fromValidated(source)
        assertEquals(4, projected.getJSONObject("scanDiagnostics").getJSONArray("recommendations").length())
        // Reopening derives scalar qualification independently of copied recommendation actions.
        val summary = ready(source).surfaceTopology
        assertEquals(509, summary.nonManifoldEdges)
        assertTrue(surfaceTopologyQualification(summary).any { it.contains("issues were detected") })
    }

    @Test fun exportQualificationBindsAllIdentityFieldsAndRejectsPreviousOrMismatchedGeometry() {
        val model = ready()
        val bound = requireNotNull(SurfaceTopologyExportEvidence.fromReopened(identity, model))
        assertEquals(model.surfaceTopology, bound.forArtifact(identity))
        for (other in listOf(identity.copy(revision = 4), identity.copy(artifactKey = "other"),
            identity.copy(projectId = "22222222-2222-2222-2222-222222222222"), identity.copy(sha256 = "b".repeat(64))))
            assertNull(bound.forArtifact(other))
        assertNull(SurfaceTopologyExportEvidence.fromReopened(identity, model.copy(revision = 4)))
        assertNull(SurfaceTopologyExportEvidence.fromReopened(identity, model.copy(artifactKey = "other")))
        assertNull(SurfaceTopologyExportEvidence.fromReopened(identity, ready(previous = true)))
        assertNull(SurfaceTopologyExportEvidence.fromReopened(identity, model.copy(meshVertices = emptyList(), triangles = emptyList())))
    }


    @Test fun wrongDiagnosticIdentityCannotBeDetachedFromReopenedGeometry() {
        for ((field, value) in listOf("artifactId" to "other-artifact", "sourceRevision" to 4)) {
            val source = preview()
            source.getJSONObject("scanDiagnostics").getJSONObject("identity").put(field, value)
            assertTrue(PreviewSchemaValidator.validate(source, 3, key) is ReopenResult.Invalid)
        }
    }

    @Test fun pointOnlyReportsDoNotAcquireASurfaceWarningFromStrayMetrics() {
        val input = ScanQualityInputs(2, 2, 1, null, null, 0, 0, 0, 0, null,
            topologyNonManifoldEdges = 509, topologySelfIntersections = 17, topologyIncomplete = false)
        assertFalse(ScanQualityEvidence.assess(input).findings.any { it.code == "SURFACE_TOPOLOGY_DEFECTS" })
        val report = preview().getJSONObject("scanDiagnostics").put("observedMeshTriangles", 0).put("observedMeshVertices", 0)
        assertFalse(codes(ScanQualityReport.validateAndRedact(report)).contains("SURFACE_TOPOLOGY_DEFECTS"))
        val source = preview(report = false).apply { remove("meshPreview"); remove("meshStatus") }
        source.put("scanDiagnostics", ScanQualityReport.build(source, 2))
        val model = ready(source)
        assertNull(SurfaceTopologyExportEvidence.fromReopened(identity, model))
    }


    @Test fun exportSelectionImmediatelyRejectsPreviousProjectAndResultCatalogs() {
        val selection = SurfaceExportSelection(identity.projectId, identity.revision, identity.artifactKey)
        assertTrue(selection.matches(identity))
        for (other in listOf(selection.copy(projectId = "22222222-2222-2222-2222-222222222222"),
            selection.copy(revision = 4), selection.copy(artifactKey = "other"),
            selection.copy(revision = null), selection.copy(artifactKey = null))) assertFalse(other.matches(identity))
    }

    @Test fun schemaOneMigrationCannotRetainMeasuredWarningWithoutTopologyEvidence() {
        val legacy = preview().getJSONObject("scanDiagnostics").put("schema", 1)
        val migrated = ScanQualityReport.validateAndRedact(legacy)
        assertFalse(codes(migrated).contains("SURFACE_TOPOLOGY_DEFECTS"))
        assertEquals(SurfaceTopologySummary(), SurfaceTopologySummary.fromValidatedReport(migrated))
        val roundTrip = ScanQualityReport.importValidated(ScanQualityReport.exportValidated(migrated))
        assertEquals(codes(migrated), codes(roundTrip))
        assertEquals(SurfaceTopologySummary(), SurfaceTopologySummary.fromValidatedReport(roundTrip))
    }

    @Test fun qualificationDoesNotChangeOptionsAndNeverLabelsPointAtlasOrJsonExportsAsSurfaces() {
        for (representation in AssetRepresentation.entries) {
            val format = if (representation == AssetRepresentation.PHOTOGRAPHIC_ATLAS) ExportFormat.PNG else ExportFormat.GLB
            val option = ExportOption("test", format, representation, 123)
            val before = option.copy()
            assertEquals(representation in setOf(AssetRepresentation.OBSERVED_SURFACE, AssetRepresentation.PHOTOGRAPHIC_SURFACE), option.isSurfaceMesh())
            assertEquals(before, option)
        }
    }
}
