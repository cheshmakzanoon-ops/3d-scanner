package com.realityforge.scanner.hostintegration

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.pipeline.SparseAlignmentCache
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Artificial adapter-only inputs; never used by the actual-photo native experiment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class VerifiedProfilePairAdapterTest {
    private data class Fixture(val restored:SparseAlignmentCache.Restored,val photos:List<ScanPhoto>)
    private fun fixture(sharpness:List<Double> = listOf(10.0,20.0,30.0)):Fixture {
        val ids=(1..3).map { "00000000-0000-4000-8000-%012d".format(it) }
        val rotation=listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val cameras=ids.mapIndexed { i,id -> AlignmentCamera(id,800,600,760.0,760.0,400.0,300.0,rotation,listOf(-i*.1,0.0,0.0)) }
        val tracks=(0..11).map { id -> SparseTrack("track-$id",id*.01,0.0,4.0,120,121,122,0.0,
            cameras.indices.map { c -> TrackObservation(c,id,(id*.01-c*.1)/4.0,0.0) }) }
        val hash=Sha256.digestUtf8("adapter-unit-only")
        val stage=StageIdentity(PipelineStage.ALIGNMENT,AlgorithmSpec("unit-adapter","1",hash),CanonicalParameters.EMPTY,
            mapOf(InputComponent.entries.first() to hash),emptyList())
        val checkpoint=AlignmentCheckpoint(ArtifactBinding("unit-project",3,"unit-job",1),hash,stage,
            StageAcceptance(AcceptanceKind.GEOMETRY,"unit-adapter","1",hash,true),cameras,
            listOf(VerifiedMatchEdge(0,1,12,12,0.0),VerifiedMatchEdge(1,2,12,12,0.0)),tracks)
        val geometry=JSONObject().put("pointCount",12).put("registeredCameras",3)
            .put("cameras",JSONArray(cameras.map { JSONObject().put("photoId",it.photoId)
                .put("rotation",JSONArray(it.rotationWorldToCamera)).put("translation",JSONArray(it.translationWorldToCamera)) }))
            .put("points",JSONArray(tracks.mapIndexed { i,t -> JSONArray(listOf(t.x,t.y,t.z,t.red,t.green,t.blue,0,3,i)) }))
        val photos=ids.mapIndexed { i,id -> ScanPhoto(id,"unit-project","$id.jpg","0".repeat(64),1,800,600,null,sharpness[i],0.0,0.0,0) }
        return Fixture(SparseAlignmentCache.Restored(geometry,checkpoint,emptyMap()),photos)
    }
    private fun tracks(f:Fixture, transform:(SparseTrack)->SparseTrack):Fixture {
        val c=f.restored.checkpoint
        val changed=AlignmentCheckpoint(c.binding,c.inputSnapshotRecordHash,c.identity,c.acceptance,c.cameras,c.verifiedMatchGraph,c.tracks.map(transform))
        return f.copy(restored=f.restored.copy(checkpoint=changed))
    }
    private fun observations(t:SparseTrack, values:List<TrackObservation>)=SparseTrack(t.trackId,t.x,t.y,t.z,t.red,t.green,t.blue,t.reprojectionRmsNormalized,values)
    private fun rejected(block:()->Unit) { try { block(); fail("Expected rejection") } catch(expected:IllegalArgumentException) {} }
    @Test fun mapsExactAcceptedTrackSupportAndMeasuredThreshold() {
        val f=fixture();val out=VerifiedProfilePair.inputs(f.restored,f.photos)
        assertEquals((0..11).toSet(),out.support.keys)
        assertTrue(out.support.values.all { it==f.photos.map{p->p.id}.toSet() })
        assertEquals(3.0/760.0,out.threshold,0.0)
        assertEquals(3,out.selection.referencePhotoIds.size)
        assertTrue(out.selection.sourcePhotoIds.values.all { it.size==2 })
        assertEquals(30.0,out.proof.getDouble("sharpnessNormalizationMaximum"),0.0)
    }
    @Test fun localLabelsAndSelectionAreDeterministicAcrossPhotoListOrder() {
        val f=fixture();val a=VerifiedProfilePair.inputs(f.restored,f.photos);val b=VerifiedProfilePair.inputs(f.restored,f.photos.reversed())
        assertEquals(a.selection,b.selection);assertEquals(a.proof.toString(),b.proof.toString())
    }
    @Test fun zeroSharpnessUsesExistingZeroNormalization() {
        val f=fixture(listOf(0.0,0.0,0.0));val out=VerifiedProfilePair.inputs(f.restored,f.photos)
        assertEquals(0.0,out.proof.getDouble("sharpnessNormalizationMaximum"),0.0);assertEquals(3,out.selection.referencePhotoIds.size)
    }
    @Test fun negativeSharpnessRejected() { val f=fixture(listOf(-1.0,0.0,1.0));rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun nonfiniteSharpnessRejected() { val f=fixture(listOf(Double.NaN,0.0,1.0));rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun foreignPhotoRejected() { val f=fixture();rejected { VerifiedProfilePair.inputs(f.restored,f.photos.drop(1)+f.photos[0].copy(id="foreign")) } }
    @Test fun duplicatePhotoRejected() { val f=fixture();rejected { VerifiedProfilePair.inputs(f.restored,listOf(f.photos[0],f.photos[0],f.photos[2])) } }
    @Test fun changedPointIdRejected() { val f=fixture();f.restored.geometry.getJSONArray("points").getJSONArray(0).put(8,99);rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun changedPointGeometryRejected() { val f=fixture();f.restored.geometry.getJSONArray("points").getJSONArray(0).put(0,50.0);rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun changedPoseRejected() { val f=fixture();f.restored.geometry.getJSONArray("cameras").getJSONObject(0).getJSONArray("translation").put(0,1.0);rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun refinedIntrinsicsRejectedRatherThanGuessingThreshold() { val f=fixture();f.restored.geometry.getJSONArray("cameras").getJSONObject(0).put("refinedIntrinsics",JSONObject());rejected { VerifiedProfilePair.inputs(f.restored,f.photos) } }
    @Test fun foreignObservationCameraIndexRejected() {
        val f=tracks(fixture()) { observations(it,listOf(it.observations[0],it.observations[1].copy(cameraIndex=3))) }
        rejected { VerifiedProfilePair.inputs(f.restored,f.photos) }
    }
    @Test fun duplicateCameraObservationRejectedByTypedCheckpoint() {
        rejected { tracks(fixture()) { observations(it,listOf(it.observations[0],it.observations[0])) } }
    }
    @Test fun disconnectedThirdCameraRejected() {
        val f=tracks(fixture()) { observations(it,it.observations.take(2)) }
        rejected { VerifiedProfilePair.inputs(f.restored,f.photos) }
    }

}
