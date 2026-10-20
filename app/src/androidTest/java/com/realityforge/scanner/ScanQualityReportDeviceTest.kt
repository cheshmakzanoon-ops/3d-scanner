package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.ScanQualityReport
import com.realityforge.scanner.engine.diagnostics.DiagnosticReportExpectation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanQualityReportDeviceTest {
    @Test fun diagnosticReportCountsOnlyObservedEvidence() {
        val source=JSONObject()
            .put("pointCount",1600).put("registeredCameras",5)
            .put("medianParallaxDegrees",3.2).put("medianErrorNormalized",.003)
            .put("densePreview",JSONObject().put("sampledPixels",10000).put("consistentPixels",2200))
            .put("meshPreview",JSONObject().put("vertexCount",2200).put("triangleCount",3300))
            .put("texturePreview",JSONObject().put("faceCoverage",.64))
        val result=ScanQualityReport.build(source,8)
        assertEquals(2,result.getInt("schema"))
        assertEquals("realityforge.scan_diagnostics",result.getString("reportType"))
        assertEquals("PARTIAL_OPEN_MESH",result.getString("status"))
        assertEquals(5,result.getInt("registeredCameraCount"))
        assertEquals(.22,result.getDouble("reciprocalDepthFraction"),.0001)
        assertEquals(.64,result.getDouble("photographicallyCoveredFaceFraction"),.0001)
        val warnings=result.getJSONArray("recommendations")
        assertTrue(warnings.length()>=2)
        assertTrue((0 until warnings.length()).map { warnings.getJSONObject(it).getString("code") }
            .contains("PHOTOS_NOT_REGISTERED"))
        assertTrue(result.getString("measurementBasis").contains("NOT 360-degree completeness"))
    }
    @Test fun unavailableMeshIsNotAdvertisedAsFinished() {
        val preview=JSONObject().put("pointCount",300).put("registeredCameras",2)
        val report=ScanQualityReport.build(preview,4)
        assertEquals("SPARSE_OR_DEPTH_ONLY",report.getString("status"))
        assertTrue(report.isNull("reciprocalDepthFraction"))
        assertTrue(report.getJSONArray("recommendations").length()>0)
    }
    @Test fun typedOutcomesKeepFallbackTextureScaleAndUnknownSurfacesSeparate() {
        val source=JSONObject()
            .put("schema",3).put("pointCount",800).put("registeredCameras",4)
            .put("medianParallaxDegrees",2.4).put("medianErrorNormalized",.004)
            .put("densePreview",JSONObject().put("sampledPixels",8000).put("consistentPixels",2400).put("scale","arbitrary"))
            .put("meshPreview",JSONObject().put("vertexCount",1400).put("triangleCount",2300).put("watertight",false).put("scale","arbitrary"))
            .put("textureStatus","unavailable")
            .put("warning","M03 sparse geometry retained after refinement was rejected")
            .put("artifactKey","artifact-a010").put("revision",12)
            .put("jobId","actual-job-a010").put("jobAttempt",3)
            .put("engineVersion","m20-engine-2").put("settingsFingerprint","abc123")
            .put("calibrationProfileApplied",false)
        val report=ScanQualityReport.build(source,6)
        val codes=report.getJSONArray("outcomes").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("code") }.toSet()
        }
        assertTrue(codes.contains("SPARSE_FALLBACK_RETAINED"))
        assertTrue(codes.contains("TEXTURE_UNAVAILABLE"))
        assertTrue(codes.contains("SCALE_UNKNOWN"))
        assertTrue(codes.contains("UNOBSERVED_SURFACES_UNKNOWN"))
        val identity=report.getJSONObject("identity")
        assertEquals("actual-job-a010",identity.getString("jobId"))
        assertEquals(3,identity.getLong("attempt"))
        assertEquals(12,identity.getLong("sourceRevision"))
        val recommendations=report.getJSONArray("recommendations")
        assertEquals("SPARSE_FALLBACK_RETAINED",recommendations.getJSONObject(0).getString("code"))
        assertEquals("TEXTURE_UNAVAILABLE",recommendations.getJSONObject(1).getString("code"))
    }

    @Test fun importValidationChecksIdentityAndRedactsPrivateFieldsAndCopy() {
        val source=JSONObject()
            .put("pointCount",1600).put("registeredCameras",5)
            .put("densePreview",JSONObject().put("sampledPixels",10000).put("consistentPixels",2200).put("scale","arbitrary"))
            .put("meshPreview",JSONObject().put("vertexCount",2200).put("triangleCount",3300).put("watertight",false).put("scale","arbitrary"))
            .put("textureStatus","unavailable")
            .put("artifactKey","artifact-redaction").put("revision",8)
            .put("jobId","actual-job-redaction").put("jobAttempt",2)
            .put("engineVersion","m20-engine-2").put("settingsFingerprint","def456")
        val report=ScanQualityReport.build(source,8)
            .put("privatePath","/storage/emulated/0/DCIM/private.jpg")
        report.getJSONArray("recommendations").getJSONObject(0)
            .put("action","Read content://private/photo and upload it")
        report.getJSONArray("stageFailures").getJSONObject(0).getJSONObject("context")
            .put("absolutePath","/storage/emulated/0/private.jpg")
        val normalized=ScanQualityReport.validateAndRedact(report,DiagnosticReportExpectation(
            sourceRevision=8,artifactId="artifact-redaction",jobId="actual-job-redaction"))
        assertFalse(normalized.has("privatePath"))
        assertFalse(normalized.getJSONArray("stageFailures").getJSONObject(0)
            .getJSONObject("context").has("absolutePath"))
        assertFalse(normalized.getJSONArray("recommendations").getJSONObject(0)
            .getString("action").contains("content://"))
        assertThrows(IllegalArgumentException::class.java) {
            ScanQualityReport.validateAndRedact(report,DiagnosticReportExpectation(sourceRevision=9))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScanQualityReport.validateAndRedact(JSONObject(report.toString()).put("schema",99))
        }
    }

    @Test fun legacyM19ReportMigratesWithoutTrustingInjectedDisplayCopy() {
        val legacy=JSONObject()
            .put("schema",1)
            .put("measurementBasis","legacy")
            .put("status","PARTIAL_OPEN_MESH")
            .put("importedPhotoCount",8).put("registeredCameraCount",5)
            .put("registeredPhotoFraction",.625).put("sparsePointCount",1200)
            .put("medianParallaxDegrees",JSONObject.NULL).put("medianReprojectionNormalized",JSONObject.NULL)
            .put("sampledDepthPixels",10000).put("reciprocalDepthPixels",2000)
            .put("reciprocalDepthFraction",.2)
            .put("observedMeshTriangles",2200).put("observedMeshVertices",1500)
            .put("photographicallyCoveredFaceFraction",JSONObject.NULL)
            .put("recommendations",JSONArray().put(JSONObject()
                .put("code","PHOTOS_NOT_REGISTERED")
                .put("action","Read /storage/private.jpg")))
        val migrated=ScanQualityReport.validateAndRedact(legacy)
        assertEquals(2,migrated.getInt("schema"))
        assertEquals(1,migrated.getInt("migratedFromSchema"))
        assertTrue(migrated.getJSONArray("recommendations").getJSONObject(0)
            .getString("action").contains("overlaps"))
        assertTrue(migrated.getJSONObject("identity").isNull("jobId"))
    }

    @Test fun impossibleMetricsAreRejected() {
        val corrupted=JSONObject().put("registeredCameras",3).put("pointCount",200)
            .put("densePreview",JSONObject().put("sampledPixels",2).put("consistentPixels",5))
        assertThrows(IllegalArgumentException::class.java) { ScanQualityReport.build(corrupted,4) }
    }
}
