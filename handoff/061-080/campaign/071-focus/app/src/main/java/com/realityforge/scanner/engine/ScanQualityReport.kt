package com.realityforge.scanner.engine

import org.json.JSONArray
import org.json.JSONObject

/** Persisted objective evidence and explicitly bounded guidance for a scan.
 * These are NOT completeness percentages or an estimate of physical accuracy.
 */
internal object ScanQualityReport {
    private fun finiteNonnegative(obj: JSONObject, key: String): Double? {
        val value=obj.optDouble(key,Double.NaN)
        return value.takeIf { it.isFinite() && it >= 0 }
    }
    fun build(result: JSONObject, importedPhotos: Int): JSONObject {
        val registered=result.optInt("registeredCameras",2)
        val sparse=result.optInt("pointCount",0)
        val dense=result.optJSONObject("densePreview")
        val mesh=result.optJSONObject("meshPreview")
        val texture=result.optJSONObject("texturePreview")
        val fraction=texture?.let { finiteNonnegative(it,"faceCoverage")?.takeIf { c -> c<=1 } }
        val measurements=ScanQualityInputs(
            importedPhotos=importedPhotos,
            registeredCameras=registered,
            sparsePoints=sparse,
            medianParallaxDegrees=finiteNonnegative(result,"medianParallaxDegrees"),
            medianReprojectionNormalized=finiteNonnegative(result,"medianErrorNormalized"),
            sampledDepthPixels=dense?.optInt("sampledPixels") ?: 0,
            consistentDepthPixels=dense?.optInt("consistentPixels") ?: 0,
            meshVertices=mesh?.optInt("vertexCount") ?: 0,
            meshTriangles=mesh?.optInt("triangleCount") ?: 0,
            photographicFaceFraction=fraction,
        )
        val summary=ScanQualityEvidence.assess(measurements)
        return JSONObject()
            .put("schema",1)
            .put("measurementBasis","Observed reconstruction evidence only: NOT 360-degree completeness, physical millimeters or scan-to-reference accuracy")
            .put("status",summary.status)
            .put("importedPhotoCount",importedPhotos)
            .put("registeredCameraCount",registered)
            .put("registeredPhotoFraction",summary.registeredFraction)
            .put("sparsePointCount",sparse)
            .put("medianParallaxDegrees",measurements.medianParallaxDegrees ?: JSONObject.NULL)
            .put("medianReprojectionNormalized",measurements.medianReprojectionNormalized ?: JSONObject.NULL)
            .put("sampledDepthPixels",measurements.sampledDepthPixels)
            .put("reciprocalDepthPixels",measurements.consistentDepthPixels)
            .put("reciprocalDepthFraction",summary.reciprocalFraction ?: JSONObject.NULL)
            .put("observedMeshTriangles",measurements.meshTriangles)
            .put("observedMeshVertices",measurements.meshVertices)
            .put("photographicallyCoveredFaceFraction",summary.photographicFaceFraction ?: JSONObject.NULL)
            .put("recommendations",JSONArray().apply {
                for(finding in summary.findings)
                    put(JSONObject().put("code",finding.code).put("action",finding.action))
            })
    }
}
