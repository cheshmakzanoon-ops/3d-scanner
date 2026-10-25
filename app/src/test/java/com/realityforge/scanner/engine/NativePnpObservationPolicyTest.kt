package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.pipeline.*
import com.realityforge.scanner.engine.registration.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativePnpObservationPolicyTest {
    private fun view(count:Int=20,tx:Double=0.0,distinct:Int=20)=SparseView(128,128,100.0,
        FloatArray(count*2){i->if(i%2==0)(63.5+((i/2%distinct)*.1+tx)/5*100).toFloat() else 63.5f},
        IntArray(count){-1},doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(tx,0.0,0.0))

    @Test fun nativePolicyInvalidatesSparseDescendantsButNotFeatureMatching() {
        val old=PipelineInputSnapshot("p",1,listOf(PipelinePhotoInput("a","a".repeat(64),128,128,100,26.0),
            PipelinePhotoInput("b","b".repeat(64),128,128,100,26.0)),"calibration","engine",
            PipelineStageSettings("features","matches","sparse|native-pnp-exact-observation-support-v1","dense","surface","texture","export"))
        assertEquals("native-pnp-exact-observation-support-centered-translation-v2",IncrementalRegistration.PNP_POLICY)
        val next=old.copy(settings=old.settings.copy(sparse=old.settings.sparse.replace("native-pnp-exact-observation-support-v1",IncrementalRegistration.PNP_POLICY)))
        for(stage in PipelineStage.entries) {
            if(stage in listOf(PipelineStage.FEATURES,PipelineStage.MATCHING))assertEquals(old.identity(stage),next.identity(stage))
            else {
                assertNotEquals(old.identity(stage),next.identity(stage))
                val artifact=PipelineArtifactReference.forStage(old.identity(stage),"old-${stage.name}.bin",contentSha256="a".repeat(64))
                val checkpoint=PipelineCheckpoint(old.identity(stage),artifact)
                assertFalse(checkpoint.isReusable(next.identity(stage)))
                assertThrows(IllegalArgumentException::class.java) { checkpoint.rebound(next.identity(stage)) }
            }
        }
    }

    @Test fun priorPnpPolicyFrontierCannotResumeEvenWithUnchangedFeatures()=runBlocking {
        val views=listOf(view(),view(tx=-.5),view());val ids=listOf("c","a","b")
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        digest.update("registration-frontier-v3|${IncrementalRegistration.POLICY}|native-pnp-exact-observation-support-v1".toByteArray())
        for(index in views.indices.sortedBy { ids[it] }) {
            val v=views[index];digest.update(ids[index].toByteArray());digest.update(0.toByte())
            digest.update(java.nio.ByteBuffer.allocate(20).putInt(v.width).putInt(v.height).putDouble(v.focalPixels).putInt(v.colors.size).array())
            for(value in v.xy)digest.update(java.nio.ByteBuffer.allocate(4).putFloat(value).array())
            digest.update(v.projection.toString().toByteArray())
        }
        val previous=digest.digest().joinToString(""){"%02x".format(it)}
        val current=IncrementalRegistration.inputIdentity(views,ids)
        assertNotEquals(previous,current)
        val anchors=listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1]))
        val old=RegistrationFrontier(previous,ids,anchors.map {FrontierCamera(ids[it.photo],it.view.rotation.toList(),it.view.translation.toList())},emptyMap())
        try {
            IncrementalRegistration.grow(views,anchors,{_,_->error("Stale policy matched")},
                {_,_,_->error("Stale policy triangulated")},{_,_->error("Stale policy registered")},photoIds=ids,resume=old)
            fail("Pre-correction PnP frontier reused")
        } catch(failure:IllegalArgumentException) { assertEquals("Stale registration frontier",failure.message) }
        val directory=java.nio.file.Files.createTempDirectory("pnp-centered-policy").toFile()
        try {
            val store=com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore(directory)
            assertTrue(store.publish(previous,old.encode()){false})
            assertNull(store.load(current){false})
            val cache=java.io.File(directory,com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore.DIRECTORY_NAME)
            java.io.File(cache,"$previous.frontier").copyTo(java.io.File(cache,"$current.frontier"))
            assertNull(store.load(current){false})
            assertArrayEquals(old.encode(),store.load(previous){false})
        } finally { directory.deleteRecursively() }
    }

    @Test fun oldRawCountFrontierIdentityCannotResume()=runBlocking {
        val views=listOf(view(),view(tx=-.5),view());val ids=listOf("a","b","c")
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        digest.update("registration-frontier-v2".toByteArray())
        for(index in views.indices) {
            val v=views[index];digest.update(ids[index].toByteArray());digest.update(0.toByte())
            digest.update(java.nio.ByteBuffer.allocate(20).putInt(v.width).putInt(v.height).putDouble(v.focalPixels).putInt(v.colors.size).array())
            for(value in v.xy)digest.update(java.nio.ByteBuffer.allocate(4).putFloat(value).array())
            digest.update(v.projection.toString().toByteArray())
        }
        val previous=digest.digest().joinToString(""){"%02x".format(it)}
        assertNotEquals(previous,IncrementalRegistration.inputIdentity(views,ids))
        val anchors=listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1]))
        val old=RegistrationFrontier(previous,ids,anchors.map {FrontierCamera(ids[it.photo],it.view.rotation.toList(),it.view.translation.toList())},emptyMap())
        try {
            IncrementalRegistration.grow(views,anchors,{_,_->error("Stale policy matched")},
                {_,_,_->error("Stale policy triangulated")},{_,_->error("Stale policy registered")},photoIds=ids,resume=old)
            fail("Old frontier reused")
        } catch(failure:IllegalArgumentException) {assertEquals("Stale registration frontier",failure.message)}
    }

    /** Actual Kotlin policy with analytic pose callback, not JNI qualification. */
    @Test fun duplicatedGeometryUsesDistinctRowsForTheUnchangedPoseGate()=runBlocking {
        val views=listOf(view(100),view(100,-.5),view(100,-1.0));var called=false
        val result=IncrementalRegistration.grow(views,
            listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            {_,_->FeaturePairs(IntArray(100){it},IntArray(100){it})},
            {_,_,_->List(100){FrontierPoint(it,doubleArrayOf((it%20)*.1,0.0,5.0),listOf(0,1))}},
            {rows,_->called=true;assertEquals(20,rows.size/5)
                FrontierPose(views[2].rotation,views[2].translation,IntArray(20){it})})
        assertTrue(called);assertEquals(3,result.cameras.size);assertTrue(result.unregistered.isEmpty())
        assertEquals(20,result.attempts.single().tracks)
    }
    @Test fun duplicateGeometryCannotInflateMinimumSixteen()=runBlocking {
        val views=listOf(view(100,distinct=15),view(100,-.5,15),view(100,-1.0,15))
        val result=IncrementalRegistration.grow(views,
            listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            {_,_->FeaturePairs(IntArray(100){it},IntArray(100){it})},
            {_,_,_->List(100){FrontierPoint(it,doubleArrayOf((it%15)*.1,0.0,5.0),listOf(0,1))}},
            {_,_->error("Repeated geometry must not launch PnP")})
        assertEquals(2,result.cameras.size);assertEquals(15,result.attempts.single().tracks)
    }
    @Test fun geometryDedupPreservesOrderNumericZeroAndExactDistinctTuples() {
        val groups=List(4){mapOf(0 to it,1 to it)}
        val links=mapOf(0 to (0..3).associateWith {it},1 to (0..3).associateWith {it})
        val base=view(4).copy(xy=FloatArray(8){if(it%2==0)63.5f else 63.5f})
        val rows=IncrementalRegistration.candidateRows(listOf(
            doubleArrayOf(0.0,0.0,5.0),doubleArrayOf(-0.0,-0.0,5.0),
            doubleArrayOf(Math.nextUp(0.0),0.0,5.0),doubleArrayOf(0.0,0.0,6.0)),groups,links,base)
        assertEquals(15,rows.size)
        assertEquals(0.0,rows[0],0.0);assertEquals(Math.nextUp(0.0),rows[5],0.0);assertEquals(6.0,rows[12],0.0)
    }
    @Test fun duplicateFeatureKeysAreRejectedBeforeEqualGeometryCanHideCompetition() {
        val xyz=listOf(doubleArrayOf(0.0,0.0,5.0),doubleArrayOf(0.0,0.0,5.0),doubleArrayOf(.1,0.0,5.0))
        val groups=List(3){mapOf(0 to it,1 to it)}
        val links=mapOf(0 to mapOf(0 to 0,1 to 0,2 to 1),1 to mapOf(0 to 0,1 to 0,2 to 1))
        val rows=IncrementalRegistration.candidateRows(xyz,groups,links,view())
        assertEquals(5,rows.size);assertEquals(.1,rows[0],0.0)
    }

}
