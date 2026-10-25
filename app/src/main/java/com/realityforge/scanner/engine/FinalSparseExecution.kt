package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.TrackSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Existing admitted <=12 production profile only. No frontier snapshot is accepted
 * as a substitute for the complete accepted graph. No geometry is published on failure. */
internal object FinalSparseExecution {
    data class Result(val input:TypedBundleAdapter.Input,val geometry:JSONObject,
        val receipt:BoundedSparseCoordinator.Receipt)
    fun execute(photoIds:List<String>,views:List<SparseView>,snapshot:TrackSnapshot,
        measuredCalibrationSha256:String?,threshold:Double,minimumAcceptedPoints:Int,
        checkpoint:()->Unit={},onSeedReceipt:(BoundedSparseCoordinator.Receipt)->Unit={},
        triangulate:(BoundedSparseCoordinator.Window)->List<BoundedSparseCoordinator.Candidate>,
        bundle:(TypedBundleAdapter.Input)->JSONObject):Result {
        require(minimumAcceptedPoints>=0)
        if (views.size == 2) throw SparseFinalizationDiagnostics.TwoCameraAdmission()
        require(views.size in 3..12)
        checkpoint()
        val plan=BoundedSparseCoordinator.prepare(photoIds,views,snapshot,
            BoundedSparseCoordinator.Budget(maxCameras=12),anchorPhotoIds=photoIds.take(2),checkpoint=checkpoint)
        check(plan.windowCount==1) { "Admitted production sparse geometry requires one complete window" }
        val receipt=plan.execute(threshold,checkpoint=checkpoint,solver=triangulate)
        onSeedReceipt(receipt)
        require(receipt.seeds.size>=maxOf(24,minimumAcceptedPoints)) { "Too few globally supported sparse seeds; anchor geometry retained" }
        val input=plan.bundleInput(receipt,measuredCalibrationSha256,threshold,checkpoint)
        checkpoint()
        val joint=bundle(input)
        checkpoint()
        require(!joint.has("error")) { joint.optString("error","Typed bundle adjustment failed") }
        TypedBundleAdapter.validateResult(input,joint)
        require(joint.getInt("iterations") in 0..25 && joint.getInt("acceptedSteps") in 0..joint.getInt("iterations"))
        val minimum=maxOf(minimumAcceptedPoints,ceil(receipt.seeds.size*.90).toInt())
        require(joint.getInt("pointCount")>=minimum &&
            joint.getDouble("robustCostAfter")<=joint.getDouble("robustCostBefore")+1e-12 &&
            joint.getDouble("medianErrorAfterNormalized")<=threshold*2 &&
            joint.getDouble("medianErrorAfterNormalized")<=joint.getDouble("medianErrorBeforeNormalized")*1.2+1e-8) {
            "Global typed sparse quality gate rejected result; anchor geometry retained"
        }
        return Result(input,joint,receipt)
    }
    /** Sparse source-observation accounting only; never object/surface completeness. */
    fun accounting(receipt:BoundedSparseCoordinator.Receipt):JSONObject = JSONObject()
        .put("policy","complete-source-observations-admitted12-v1")
        .put("windowsExecuted",receipt.windowsExecuted).put("windowIterations",0)
        .put("inputTracks",receipt.seeds.size+receipt.missingTrackIds.size+receipt.invalidTrackIds.size)
        .put("acceptedSeedTracks",receipt.seeds.size)
        .put("missingTrackIds",JSONArray(receipt.missingTrackIds)).put("invalidTrackIds",JSONArray(receipt.invalidTrackIds))
        .put("inputObservations",receipt.inputObservations).put("acceptedSeedObservations",receipt.acceptedObservations)
        .put("rejectedSeedObservations",receipt.rejectedObservations).put("packedObservations",receipt.packedObservations)
        .put("rejectedCandidates",receipt.rejectedCandidates).put("unsupportedPhotoIds",JSONArray(receipt.unsupportedPhotoIds))
        .put("published",false)
}
