package com.realityforge.scanner.engine

import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Token ownership remains with the pipeline; this binds every sparse call to that lease. */
internal class NativeFrontierCallbacks(
    val triangulate: (DoubleArray,DoubleArray,Double)->List<FrontierPoint>,
    val register: (DoubleArray,Double)->FrontierPose,
)
internal object NativeFrontierGeometry {
    private fun checked(json:String, schema:Int, representation:String):JSONObject {
        val result=JSONObject(json)
        if(result.optString("errorCode")=="CANCELLED" || result.optString("error")=="Reconstruction cancelled")
            throw CancellationException("Native sparse operation cancelled")
        require(!result.has("error")){result.optString("error")}
        require(result.getInt("schema")==schema && result.getString("representation")==representation && result.getString("scale")=="arbitrary") {"Unsupported sparse native result schema"}
        return result
    }
    private fun exactIndex(value:Double, maximum:Int):Int {
        require(value.isFinite() && value>=0 && value<maximum && value%1.0==0.0){"Invalid native source index"}
        return value.toInt()
    }
    private fun triangulated(json:String, cameraCount:Int):List<FrontierPoint> {
        val result=checked(json,3,"sparse_points");val points=result.getJSONArray("points")
        require(points.length() in 0..15000 && result.getInt("pointCount")==points.length() && result.getInt("registeredCameras")==cameraCount)
        val evidence=result.optJSONArray("trackEvidence")
        val support=mutableMapOf<Int,Pair<List<Int>,Double>>()
        if(evidence!=null){require(evidence.length()==points.length());for(i in 0 until evidence.length()){
            val item=evidence.getJSONObject(i);val id=exactIndex(item.getDouble("track"),10000001)
            val cameras=item.getJSONArray("supportingCameras");require(cameras.length() in 2..cameraCount)
            val ids=List(cameras.length()){exactIndex(cameras.getDouble(it),cameraCount)};require(ids.distinct().size==ids.size)
            val condition=item.getDouble("angularCondition");require(condition.isFinite()&&condition>=1)
            require(support.put(id,ids to condition)==null){"Duplicate native evidence identity"}
        }}
        val output=List(points.length()){index->val point=points.getJSONArray(index);require(point.length()==9)
            val id=exactIndex(point.getDouble(8),10000001);val xyz=DoubleArray(3){point.getDouble(it)};require(xyz.all {it.isFinite()})
            val provenance=support[id];require(evidence==null||provenance!=null){"Missing accepted track evidence"}
            FrontierPoint(id,xyz,provenance?.first,provenance?.second)
        }
        require(output.map {it.track}.distinct().size==output.size);return output
    }
    private fun resected(json:String, inputCount:Int):FrontierPose {
        val result=checked(json,2,"camera_pose");val rotation=result.getJSONArray("rotation");val translation=result.getJSONArray("translation");val inliers=result.getJSONArray("inlierIndices")
        require(rotation.length()==9 && translation.length()==3 && inliers.length() in 16..inputCount && result.getInt("inputTracks")==inputCount && result.getInt("inlierCount")==inliers.length())
        val indices=IntArray(inliers.length()){exactIndex(inliers.getDouble(it),inputCount)};require(indices.distinct().size==indices.size)
        val conditioning=if(result.has("initializationModel"))FrontierConditioning(result.getString("initializationModel"),result.getDouble("worldPlanarityRatio"),result.getInt("hypothesesTested")) else null
        return FrontierPose(DoubleArray(9){rotation.getDouble(it)},DoubleArray(3){translation.getDouble(it)},indices,conditioning)
    }
    fun triangulate(poses:DoubleArray, observations:DoubleArray, threshold:Double):List<FrontierPoint> =
        triangulated(NativeCore.reconstructMultiview(poses,observations,threshold,0),poses.size/12)
    fun register(observations:DoubleArray, threshold:Double):FrontierPose =
        resected(NativeCore.registerCamera(observations,threshold),observations.size/5)
    fun forOperation(token:Long):NativeFrontierCallbacks {
        require(token>0){"Missing sparse operation lease"}
        return NativeFrontierCallbacks(
            {poses,observations,threshold->triangulated(NativeCore.reconstructMultiviewCancellable(poses,observations,threshold,0,token),poses.size/12)},
            {observations,threshold->resected(NativeCore.registerCameraCancellable(observations,threshold,token),observations.size/5)})
    }
}
