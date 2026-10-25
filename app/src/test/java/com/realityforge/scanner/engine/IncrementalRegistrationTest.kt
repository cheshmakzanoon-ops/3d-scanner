package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.pipeline.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalRegistrationTest {
    private fun view(keys: Int = 4, tx: Double = 0.0): SparseView {
        val f=100.0
        val pixels=FloatArray(keys*2) { i ->
            if(i%2==0)(63.5+((i/2)*.1+tx)/5*f).toFloat() else 63.5f
        }
        return SparseView(128,128,f,pixels,IntArray(keys) { -1 },
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(tx,0.0,0.0))
    }
    @Test fun laterRegisteredViewsCanSupportANewCandidateWithoutOriginalAnchor() {
        val rows=IncrementalRegistration.candidateRows(listOf(doubleArrayOf(.2,0.0,5.0)),
            listOf(mapOf(3 to 0,4 to 1)),mapOf(3 to mapOf(0 to 2),4 to mapOf(1 to 2)),view())
        assertEquals(5,rows.size)
        assertEquals(.2,rows[0],0.0)
        assertEquals(.04,rows[3],1e-6)
    }
    @Test fun establishedLandmarkNeedsOneLinkButConflictingCandidateKeysAreRejected() {
        val xyz=listOf(doubleArrayOf(.2,0.0,5.0));val group=listOf(mapOf(0 to 0,1 to 0))
        assertEquals(5,IncrementalRegistration.candidateRows(xyz,group,mapOf(0 to mapOf(0 to 2)),view()).size)
        assertEquals(0,IncrementalRegistration.candidateRows(xyz,group,
            mapOf(0 to mapOf(0 to 2),1 to mapOf(0 to 3)),view()).size)
    }
    @Test fun singleSourceObservationCannotEstablishALandmarkAtCandidateBoundary() {
        val xyz=listOf(doubleArrayOf(.2,0.0,5.0))
        for(group in listOf(emptyMap(),mapOf(0 to 0))) {
            assertEquals(0,IncrementalRegistration.candidateRows(xyz,listOf(group),
                mapOf(0 to mapOf(0 to 2),1 to mapOf(0 to 2)),view()).size)
        }
    }
    @Test fun duplicateSingleLinkTargetRejectsEveryCompetingLandmark() {
        assertEquals(0,IncrementalRegistration.candidateRows(
            listOf(doubleArrayOf(.2,0.0,5.0),doubleArrayOf(.4,0.0,7.0)),
            listOf(mapOf(0 to 0,1 to 0),mapOf(0 to 1,1 to 1)),
            mapOf(0 to mapOf(0 to 2),1 to mapOf(1 to 2)),view()).size)
    }
    @Test fun duplicateCandidateFeatureRejectsBothCompetingLandmarks() {
        val rows=IncrementalRegistration.candidateRows(
            listOf(doubleArrayOf(.2,0.0,5.0),doubleArrayOf(.4,0.0,7.0)),
            listOf(mapOf(0 to 0,1 to 0),mapOf(0 to 1,1 to 1)),
            mapOf(0 to mapOf(0 to 2,1 to 2),1 to mapOf(0 to 2,1 to 2)),view())
        assertEquals(0,rows.size)
    }
    @Test fun graphSnapshotKeepsTheNativeTrackIdAndDoesNotAliasMutableGraph() {
        val graph=MultiViewTrackGraph(listOf(view(),view(tx=-.5),view(tx=-1.0)))
        assertTrue(graph.add(0,2,1,2))
        assertTrue(graph.add(0,0,2,0))
        val snapshot=graph.snapshot()
        val packed=graph.packedRows()
        assertEquals(2,snapshot.size)
        assertEquals(0,snapshot[0][0])
        assertEquals(2,snapshot[1][0])
        assertEquals(0.0,packed[0],0.0)
        assertEquals(1.0,packed[14],0.0)
        @Suppress("UNCHECKED_CAST")
        (snapshot[0] as MutableMap<Int,Int>)[0]=3
        assertEquals(0,graph.snapshot()[0][0])
    }
    @Test fun reciprocalMatchContractRejectsDuplicateIdsAndNegativeIndices() {
        for (pair in listOf(
            intArrayOf(0,0) to intArrayOf(1,2),intArrayOf(0,1) to intArrayOf(2,2),
            intArrayOf(-1) to intArrayOf(0),intArrayOf(0) to intArrayOf())) {
            try { FeaturePairs(pair.first,pair.second);fail("Invalid matches accepted") }
            catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun disconnectedCandidateIsNotCountedAsRegistered() = runBlocking {
        val views=listOf(view(),view(tx=-.5),view())
        val result=IncrementalRegistration.grow(views,
            listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            { _,_ -> FeaturePairs(intArrayOf(),intArrayOf()) },
            { _,_,_ -> error("No triangulation without tracks") },{ _,_ -> error("No fictitious camera") })
        assertEquals(listOf(2),result.unregistered)
        assertEquals(2,result.cameras.size)
        assertTrue(result.attempts.isEmpty())
    }
    @Test fun cancellationIsPropagatedWithoutReturningFakeSuccess() = runBlocking {
        val views=listOf(view(),view(tx=-.5),view())
        try {
            IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
                { _,_ -> FeaturePairs(intArrayOf(),intArrayOf()) },
                { _,_,_ -> emptyList() },{ _,_ -> error("Unexpected solver") },
                { _,_,_ -> throw CancellationException("test stop") })
            fail("Cancellation swallowed")
        } catch(_: CancellationException) { }
    }

    @Test fun inaccurateNativePoseCannotBePublishedEvenWithClaimedInliers() = runBlocking {
        val views=listOf(view(20),view(20,tx=-.5),view(20))
        val result=IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            { _,_ -> FeaturePairs(IntArray(20) { it },IntArray(20) { it }) },
            { _,_,_ -> List(20) { FrontierPoint(it,doubleArrayOf(it*.1,0.0,5.0)) } },
            { _,_ -> FrontierPose(views[2].rotation,doubleArrayOf(100.0,0.0,0.0),IntArray(20) { it }) })
        assertEquals(2,result.cameras.size)
        assertEquals(listOf(2),result.unregistered)
        assertTrue(result.attempts.single().reason.contains("reprojection"))
    }

    /** Actual Kotlin scheduler/geometry checks; analytic callbacks are not JNI qualification. */
    @Test fun twoAnchorsAndOnlyOneCandidateEdgeCanRegisterWithoutATriangle()=runBlocking {
        val views=listOf(view(20),view(20,tx=-.5),view(20,tx=-1.0))
        var calls=0
        val directory=java.nio.file.Files.createTempDirectory("single-link-admitted-session").toFile()
        try {
        val session=AdmittedRegistrationSession.grow(directory,"a".repeat(64),listOf("a","b","c"),views,
            listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            {a,b->if(a==0&&b==2) FeaturePairs(intArrayOf(),intArrayOf())
                  else FeaturePairs(IntArray(20){it},IntArray(20){it})},
            {poses,observations,_->
                assertEquals(24,poses.size) // exactly two registered anchors
                assertEquals(40,observations.size/7)
                List(20){FrontierPoint(it,doubleArrayOf(it*.1,0.0,5.0),listOf(0,1))}
            },
            {rows,threshold->
                calls++;assertEquals(20,rows.size/5);assertEquals(.02,threshold,0.0)
                FrontierPose(views[2].rotation,views[2].translation,IntArray(20){it})
            })
        val result=session.growth
        assertEquals(1,calls);assertEquals(3,result.cameras.size);assertTrue(result.unregistered.isEmpty())
        assertEquals(20,result.attempts.single().tracks)
        assertTrue(session.persistedCheckpoints>0);assertEquals(0,session.failedCheckpointWrites)
        } finally {directory.deleteRecursively()}
    }
    @Test fun oneLinkDoesNotBypassSourceGeometryPoseConsensusOrCancellation()=runBlocking {
        for(mode in listOf("source-depth","source-reprojection","source-support","pose", "inliers", "cancel")) {
            val views=listOf(view(20),view(20,tx=-.5),view(20,tx=-1.0))
            var called=false
            try {
                val result=IncrementalRegistration.grow(views,
                    listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
                    {a,b->if(a==0&&b==2) FeaturePairs(intArrayOf(),intArrayOf())
                          else FeaturePairs(IntArray(20){it},IntArray(20){it})},
                    {_,_,_->List(20){FrontierPoint(it,
                        doubleArrayOf(it*.1,if(mode=="source-reprojection")10.0 else 0.0,if(mode=="source-depth")-5.0 else 5.0),
                        if(mode=="source-support")listOf(0) else listOf(0,1))}},
                    {_,_->
                        called=true
                        if(mode=="cancel")throw CancellationException("cancel single-link PnP")
                        FrontierPose(views[2].rotation,
                            if(mode=="pose")doubleArrayOf(100.0,0.0,0.0) else views[2].translation,
                            IntArray(if(mode=="inliers")15 else 20){it})
                    })
                assertNotEquals("Cancellation must propagate", "cancel", mode)
                assertEquals(2,result.cameras.size);assertEquals(listOf(2),result.unregistered)
                assertEquals(mode in listOf("pose","inliers"),called)
            } catch(failure:CancellationException) {
                assertEquals("cancel",mode);assertTrue(called)
            }
        }
    }

    @Test fun frontierCheckpointRoundTripAndStaleInputRejection() = runBlocking {
        val views=listOf(view(),view(tx=-.5),view());var checkpoint:com.realityforge.scanner.engine.registration.RegistrationFrontier?=null
        val initial=listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1]))
        suspend fun run(input:List<SparseView>, resume:com.realityforge.scanner.engine.registration.RegistrationFrontier?)=
            IncrementalRegistration.grow(input,initial,{_,_->FeaturePairs(intArrayOf(),intArrayOf())},{_,_,_->emptyList()},{_,_->error("Unexpected pose")},
                photoIds=listOf("photo-a","photo-b","photo-c"),resume=resume,checkpoint={checkpoint=it})
        val first=run(views,null);val decoded=com.realityforge.scanner.engine.registration.RegistrationFrontier.decode(requireNotNull(checkpoint).encode())
        val resumed=run(views,decoded);assertEquals(first.unregistered,resumed.unregistered);assertEquals(2,resumed.cameras.size)
        val bytes=decoded.encode();bytes[20]=(bytes[20].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){com.realityforge.scanner.engine.registration.RegistrationFrontier.decode(bytes)}
        try { run(views.toMutableList().also{it[2]=view(tx=.1)},decoded);fail("Stale input accepted") } catch(_:IllegalArgumentException){}
    }
    @Test fun formerFrontierIdentityCannotResumeDespiteUnchangedInputs()=runBlocking {
        val views=listOf(view(),view(tx=-.5),view());val ids=listOf("a","b","c")
        // Independently reproduce the former exact input identity, not the new implementation.
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        digest.update("registration-frontier-v2".toByteArray())
        for(index in views.indices) {
            val v=views[index];digest.update(ids[index].toByteArray());digest.update(0.toByte())
            digest.update(java.nio.ByteBuffer.allocate(20).putInt(v.width).putInt(v.height).putDouble(v.focalPixels).putInt(v.colors.size).array())
            for(value in v.xy)digest.update(java.nio.ByteBuffer.allocate(4).putFloat(value).array())
            digest.update(v.projection.toString().toByteArray())
        }
        val old=digest.digest().joinToString(""){"%02x".format(it)}
        assertNotEquals(old,IncrementalRegistration.inputIdentity(views,ids))
        val initial=listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1]))
        val stale=com.realityforge.scanner.engine.registration.RegistrationFrontier(old,ids,
            initial.map {com.realityforge.scanner.engine.registration.FrontierCamera(ids[it.photo],it.view.rotation.toList(),it.view.translation.toList())},emptyMap())
        try {
            IncrementalRegistration.grow(views,initial,{_,_->error("Stale frontier matched")},
                {_,_,_->error("Stale frontier triangulated")},{_,_->error("Stale frontier registered")},
                photoIds=ids,resume=stale)
            fail("Former frontier policy accepted")
        } catch(failure:IllegalArgumentException) {assertEquals("Stale registration frontier",failure.message)}
    }
    @Test fun admissionPolicyInvalidatesSparseDescendantsAndRenamedFrontierButReusesFeaturesMatches() {
        val old=PipelineInputSnapshot("project",1,listOf(
            PipelinePhotoInput("a","a".repeat(64),128,128,100,26.0),
            PipelinePhotoInput("b","b".repeat(64),128,128,100,26.0)),"calibration","engine",
            PipelineStageSettings("features","matches","incremental-frontier-v1|unchanged-tail","dense","surface","texture","export"))
        val next=old.copy(settings=old.settings.copy(sparse=IncrementalRegistration.POLICY+"|unchanged-tail"))
        assertEquals("incremental-frontier-supported-landmark-single-link-v2",IncrementalRegistration.POLICY)
        for(stage in PipelineStage.entries) {
            if(stage in listOf(PipelineStage.FEATURES,PipelineStage.MATCHING))
                assertEquals(old.identity(stage),next.identity(stage))
            else assertNotEquals(old.identity(stage),next.identity(stage))
        }
        val directory=java.nio.file.Files.createTempDirectory("landmark-policy-frontier").toFile()
        try {
            val store=com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore(directory)
            val before=old.identity(PipelineStage.SPARSE).digest;val after=next.identity(PipelineStage.SPARSE).digest
            val bytes=byteArrayOf(1,2,3)
            assertTrue(store.publish(before,bytes){false});assertNull(store.load(after){false})
            val cache=java.io.File(directory,com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore.DIRECTORY_NAME)
            java.io.File(cache,"$before.frontier").copyTo(java.io.File(cache,"$after.frontier"))
            assertNull(store.load(after){false});assertArrayEquals(bytes,store.load(before){false})
        } finally {directory.deleteRecursively()}
    }

    @Test fun largerPhotoManifestsRemainExplicitlyDisconnected() = runBlocking {
        for(count in listOf(30,80,250)){
            val views=List(count){view(tx=if(it==1)-.5 else 0.0)};var pairCalls=0
            val result=IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
                {_,_->++pairCalls;FeaturePairs(intArrayOf(),intArrayOf())},{_,_,_->error("No geometry")},{_,_->error("No pose")})
            assertEquals(count-2,result.unregistered.size);assertEquals(1,pairCalls)
        }
    }
    @Test fun duplicatePhotoIdentityIsRejectedBeforeMatching()=runBlocking {
        val views=listOf(view(),view(tx=-.5));try{
            IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),{_,_->error("Duplicate matched")},{_,_,_->emptyList()},{_,_->error("pose")},photoIds=listOf("same","same"));fail("Duplicate accepted")
        }catch(_:IllegalArgumentException){}
    }

    /** Analytic scheduler fixture: test-only triangulation/PnP use known points;
     * production JNI accuracy is a distinct instrumentation gate. */
    @Test fun boundedVisibilityChainsRegisterAndResumeThirtyEightyAndTwoHundredFiftyViews()=runBlocking {
        for(count in listOf(30,80,250)){
            val views=List(count){view(20,tx=-it*.01)};val initial=listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1]))
            val ids=List(count){"photo-%03d".format(it)};var calls=0;var latest:com.realityforge.scanner.engine.registration.RegistrationFrontier?=null
            suspend fun run(resume:com.realityforge.scanner.engine.registration.RegistrationFrontier?,interrupt:Boolean)=IncrementalRegistration.grow(views,initial,
                {a,b->++calls;assertTrue(kotlin.math.abs(a-b)<=2);FeaturePairs(IntArray(20){it},IntArray(20){it})},
                {poses,observations,_->
                    assertTrue(poses.size<=144)
                    // A bounded anchor+frontier window can contain separate
                    // components for the same scene feature. Compact track IDs
                    // belong to these exact rows, never to global key ordinals.
                    observations.asList().chunked(7).groupBy {it[0].toInt()}.map { (track,rows)->
                        val sample=rows.first();val camera=sample[1].toInt()
                        val key=kotlin.math.round((sample[2]*5-poses[camera*12+9])/.1).toInt()
                        assertTrue(key in 0 until 20)
                        FrontierPoint(track,doubleArrayOf(key*.1,0.0,5.0),rows.map {it[1].toInt()})
                    }
                },
                {rows,_->val tx=rows[3]*5-rows[0];FrontierPose(views[0].rotation,doubleArrayOf(tx,0.0,0.0),IntArray(rows.size/5){it})},
                photoIds=ids,resume=resume,checkpoint={latest=it;if(interrupt&&it.cameras.size==10)throw CancellationException("simulated persisted interruption")},
                neighbors={a->setOf(a-2,a-1,a+1,a+2).filter {it in views.indices}.toSet()})
            try{run(null,true);fail("Did not interrupt") }catch(_:CancellationException){}
            val resumed=run(com.realityforge.scanner.engine.registration.RegistrationFrontier.decode(requireNotNull(latest).encode()),false)
            val clean=run(null,false)
            assertEquals(count,resumed.cameras.size);assertTrue(resumed.unregistered.isEmpty());assertTrue(calls<count*8)
            assertEquals(clean.cameras.map {it.photo},resumed.cameras.map {it.photo})
            for(camera in resumed.cameras){
                assertEquals(-camera.photo*.01,camera.view.translation[0],1e-5)
                assertArrayEquals(clean.cameras.first {it.photo==camera.photo}.view.translation,camera.view.translation,1e-5)
            }
        }
    }

    @Test fun frontierDefendsPoseIdentityAndObservationInputs(){
        val rotation=mutableListOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0);val translation=mutableListOf(0.0,0.0,0.0)
        val first=com.realityforge.scanner.engine.registration.FrontierCamera("a",rotation,translation)
        val second=com.realityforge.scanner.engine.registration.FrontierCamera("b",rotation,listOf(-1.0,0.0,0.0));val ids=mutableListOf("a","b");val cameras=mutableListOf(first,second);val failures=mutableMapOf("a" to "measured reason")
        val tracks=com.realityforge.scanner.engine.tracks.TrackSnapshot(emptyList()).encode()
        val frontier=com.realityforge.scanner.engine.registration.RegistrationFrontier("a".repeat(64),ids,cameras,failures,tracks);val encoded=frontier.encode()
        rotation[0]=0.0;translation[0]=500.0;ids.clear();cameras.clear();failures.clear();tracks[0]=0;frontier.trackSnapshot[0]=0
        assertArrayEquals(encoded,frontier.encode());assertEquals(1.0,first.rotation[0],0.0);assertEquals(0.0,first.translation[0],0.0)
        assertThrows(UnsupportedOperationException::class.java){(frontier.photoIds as MutableList).clear()}
    }
}
