package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore
import com.realityforge.scanner.engine.registration.FrontierCamera
import com.realityforge.scanner.engine.registration.RegistrationFrontier
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Optional durable registration progress for the existing <=12 profile, not a
 * completed alignment artifact. Only the two original anchors define resume gauge. */
internal object AdmittedRegistrationSession {
    data class Result(val growth:RegistrationGrowth,val resumeStatus:String,val restoredCameraCount:Int,
        val persistedCheckpoints:Int,val failedCheckpointWrites:Int)
    suspend fun grow(projectDirectory:File,stageSha256:String,photoIds:List<String>,
        templates:List<SparseView>,accepted:List<RegisteredFrame>,
        matches:suspend (Int,Int)->FeaturePairs,
        triangulate:(DoubleArray,DoubleArray,Double)->List<FrontierPoint>,
        register:(DoubleArray,Double)->FrontierPose,
        checkpointEvent:(RegistrationFrontier,Boolean)->Unit={_,_->},
        progress:suspend (String,Int,Int)->Unit={_,_,_->}):Result {
        require(templates.size in 2..12 && photoIds.size==templates.size)
        require(stageSha256.matches(Regex("[a-f0-9]{64}")))
        require(photoIds.distinct().size==photoIds.size && accepted.size in 2..templates.size)
        require(accepted.map { it.photo }.distinct().size==accepted.size && accepted.all { it.photo in templates.indices })
        val context=coroutineContext;context.ensureActive()
        for(frame in accepted) {
            context.ensureActive();val template=templates[frame.photo];val view=frame.view
            require(template.width==view.width && template.height==view.height && template.focalPixels==view.focalPixels &&
                template.projection==view.projection && template.xy.contentEquals(view.xy) && template.colors.contentEquals(view.colors)) { "Accepted pose uses different source features or lens model" }
            FrontierCamera(photoIds[frame.photo],view.rotation.toList(),view.translation.toList())
        }
        val anchors=accepted.take(2)
        fun center(view:SparseView)=DoubleArray(3) { axis -> -(0..2).sumOf { view.rotation[it*3+axis]*view.translation[it] } }
        val a=center(anchors[0].view);val b=center(anchors[1].view)
        require(sqrt((0..2).sumOf { (a[it]-b[it]).let { d -> d*d } })>=1e-6) { "Registration anchors are coincident" }
        val identity=IncrementalRegistration.inputIdentity(templates,photoIds) { context.ensureActive() }
        val store=SparseFrontierCheckpointStore(projectDirectory)
        var resumeStatus="absent";var restored:RegistrationFrontier?=null
        val bytes=store.load(stageSha256) { !context.isActive }
        if(bytes!=null) {
            try {
                val candidate=RegistrationFrontier.decode(bytes)
                require(candidate.inputIdentity==identity && candidate.photoIds.toSet()==photoIds.toSet()) { "stale_features" }
                require(candidate.cameras.take(2).map { it.photoId }==anchors.map { photoIds[it.photo] }) { "anchor_order_mismatch" }
                for(frame in accepted) {
                    val saved=candidate.cameras.firstOrNull { it.photoId==photoIds[frame.photo] }
                    if(frame in anchors)require(saved!=null) { "missing_anchor" }
                    if(saved!=null)require(saved.rotation==frame.view.rotation.toList() && saved.translation==frame.view.translation.toList()) { "accepted_pose_conflict" }
                }
                restored=candidate;resumeStatus="restored"
            } catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled }
            catch(invalid:IllegalArgumentException) { resumeStatus="rejected:${invalid.message.orEmpty().take(100)}" }
            catch(invalid:java.io.IOException) { resumeStatus="rejected:malformed_frontier" }
        }
        val merged=linkedMapOf<String,FrontierCamera>()
        // Anchor order is invariant; other accepted poses are merged by validated photo ID.
        for(frame in anchors)merged[photoIds[frame.photo]]=FrontierCamera(photoIds[frame.photo],frame.view.rotation.toList(),frame.view.translation.toList())
        for(camera in restored?.cameras.orEmpty())merged[camera.photoId]=camera
        for(frame in accepted)if(photoIds[frame.photo] !in merged)merged[photoIds[frame.photo]]=FrontierCamera(photoIds[frame.photo],frame.view.rotation.toList(),frame.view.translation.toList())
        val seed=RegistrationFrontier(identity,photoIds,merged.values.toList(),
            restored?.failureReasons.orEmpty().filterKeys { it !in merged },restored?.trackSnapshot ?: ByteArray(0))
        var saved=0;var failed=0
        fun persist(frontier:RegistrationFrontier) {
            context.ensureActive()
            val durable=store.publish(stageSha256,frontier.encode()) { !context.isActive }
            if(durable)saved++ else failed++
            checkpointEvent(frontier,durable)
        }
        persist(seed)
        val neighbors=photoIds.indices.associateWith { camera -> photoIds.indices.filter { it!=camera }.toSet() }
        val growth=IncrementalRegistration.grow(templates,anchors,matches,triangulate,register,
            photoIds=photoIds,resume=seed,checkpoint=::persist,neighbors={neighbors.getValue(it)},
            pairBudget=photoIds.size*(photoIds.size-1)/2,progress=progress)
        context.ensureActive()
        return Result(growth,resumeStatus,restored?.cameras?.size ?: 0,saved,failed)
    }
}
