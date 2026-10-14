package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.ScanQualityReport
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
    @Test fun impossibleMetricsAreRejected() {
        val corrupted=JSONObject().put("registeredCameras",3).put("pointCount",200)
            .put("densePreview",JSONObject().put("sampledPixels",2).put("consistentPixels",5))
        assertThrows(IllegalArgumentException::class.java) { ScanQualityReport.build(corrupted,4) }
    }
}
