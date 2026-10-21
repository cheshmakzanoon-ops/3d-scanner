package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.TrackSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Offside publication assembly: never mutate the verified anchor output or its poses.
 * Caller performs fallible progress/persistence work BEFORE swapping these three values. */
internal object FinalSparsePublication {
    data class Observation(val camera:Int,val key:Int,val nx:Double,val ny:Double)
    data class Prepared(val output:JSONObject,val cameras:JSONArray,
        val observations:Map<Int,List<Observation>>)
    fun prepare(original:JSONObject,originalCameras:JSONArray,sourcePhotoIds:List<String>,
        views:List<SparseView>,snapshot:TrackSnapshot,result:FinalSparseExecution.Result,
        checkpoint:()->Unit={},beforeCommit:()->Unit={}):Prepared {
        checkpoint()
        require(sourcePhotoIds.size==views.size && originalCameras.length()==views.size)
        require(result.input.photoIds.toSet()==sourcePhotoIds.toSet())
        val tracks=snapshot.tracks.sortedBy { it.id }
        require(result.input.sourceTrackIds.toList()==tracks.map { it.id })
        TypedBundleAdapter.validateResult(result.input,result.geometry)
        // JSON roundtrip ensures the staged object tree owns all mutable arrays/objects.
        val output=JSONObject(original.toString());val cameras=JSONArray(originalCameras.toString())
        val joint=JSONObject(result.geometry.toString());val points=joint.getJSONArray("points")
        val observations=linkedMapOf<Int,List<Observation>>()
        val published=hashSetOf<String>()
        for(index in 0 until points.length()) {
            checkpoint();val track=points.getJSONArray(index).getInt(8);published.add(tracks[track].id)
            observations[track]=tracks[track].observations.map { source ->
                val camera=sourcePhotoIds.indexOf(source.photo);require(camera>=0)
                val normalized=views[camera].normalized(source.keypoint)
                Observation(camera,source.keypoint,normalized.first,normalized.second)
            }
        }
        for(camera in sourcePhotoIds.indices) {
            checkpoint();val target=cameras.getJSONObject(camera)
            require(target.getString("photoId")==sourcePhotoIds[camera])
            val nativeIndex=result.input.photoIds.indexOf(sourcePhotoIds[camera]);require(nativeIndex>=0)
            val corrected=joint.getJSONArray("cameras").getJSONObject(nativeIndex)
            target.put("rotation",corrected.getJSONArray("rotation"))
                .put("translation",corrected.getJSONArray("translation"))
                .put("gaugeFixed",corrected.getBoolean("fixed"))
        }
        val parallax=(0 until points.length()).map { points.getJSONArray(it).getDouble(7) }.sorted()
        require(parallax.isNotEmpty())
        output.put("schema",6).put("points",points).put("pointCount",points.length())
            .put("sparseSourceTrackIds",JSONArray(result.input.sourceTrackIds.toList()))
            .put("medianParallaxDegrees",parallax[parallax.size/2])
            .put("medianErrorNormalized",joint.getDouble("medianErrorAfterNormalized"))
            .put("finalSparseOutcome","global_typed_ba_admitted12")
            .put("cameras",cameras).put("registeredCameras",cameras.length())
            .put("finalSparseAccounting",FinalSparseExecution.accounting(result.receipt).put("published",true)
                .put("publishedTracks",points.length())
                .put("bundleDroppedSeedTrackIds",JSONArray(result.receipt.seeds.map { it.candidate.sourceTrackId }.filter { it !in published }))
                .put("bundleInputObservations",joint.getInt("inputObservations"))
                .put("bundleUsableObservations",joint.getInt("usableObservations"))
                .put("bundleRejectedObservations",joint.getInt("rejectedObservations")))
        val stats=JSONObject().put("solver","coupled_huber_schur_lm")
        for(field in listOf("bundleProblemSchema","anchorCamerasFixed","gaugeMode","intrinsicPolicy",
            "acceptedIntrinsicGroups","loopProposalCount","verifiedLoopCount","acceptedLoopCorrections",
            "stableTrackMapping","inputObservations","usableObservations","rejectedObservations",
            "robustCostBefore","robustCostAfter","acceptedSteps"))stats.put(field,joint.get(field))
        stats.put("attemptedIterations",joint.getInt("iterations"))
            .put("medianBeforeNormalized",joint.getDouble("medianErrorBeforeNormalized"))
            .put("medianAfterNormalized",joint.getDouble("medianErrorAfterNormalized"))
        output.put("bundleAdjustment",stats).put("multiViewStats",JSONObject()
            .put("optimization","fixed_pose_window_then_typed_bundle").put("initialTracks",tracks.size)
            .put("rejectedTracks",result.receipt.missingTrackIds.size+result.receipt.invalidTrackIds.size).put("iterations",0))
        checkpoint();beforeCommit()
        return Prepared(output,cameras,observations)
    }
}
