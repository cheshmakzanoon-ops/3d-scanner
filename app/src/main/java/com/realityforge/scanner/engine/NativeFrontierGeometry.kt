package com.realityforge.scanner.engine

import org.json.JSONObject

internal object NativeFrontierGeometry {
    fun triangulate(poses: DoubleArray, observations: DoubleArray, threshold: Double): List<FrontierPoint> {
        val result=JSONObject(NativeCore.reconstructMultiview(poses,observations,threshold,0))
        require(!result.has("error")) { result.optString("error") }
        val points=result.getJSONArray("points")
        return List(points.length()) { index ->
            val point=points.getJSONArray(index)
            FrontierPoint(point.getInt(8),DoubleArray(3) { point.getDouble(it) })
        }
    }
    fun register(observations: DoubleArray, threshold: Double): FrontierPose {
        val result=JSONObject(NativeCore.registerCamera(observations,threshold))
        require(!result.has("error")) { result.optString("error") }
        val rotation=result.getJSONArray("rotation")
        val translation=result.getJSONArray("translation")
        val inliers=result.getJSONArray("inlierIndices")
        val conditioning=if(result.has("initializationModel"))FrontierConditioning(result.getString("initializationModel"),
            result.getDouble("worldPlanarityRatio"),result.getInt("hypothesesTested")) else null
        return FrontierPose(DoubleArray(9) { rotation.getDouble(it) },
            DoubleArray(3) { translation.getDouble(it) },IntArray(inliers.length()) { inliers.getInt(it) },conditioning)
    }
}
