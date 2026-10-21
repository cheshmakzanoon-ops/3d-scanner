package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore
import com.realityforge.scanner.engine.registration.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AdmittedRegistrationSessionTest {
    private fun views()=List(3) { camera -> SparseView(100,100,100.0,FloatArray(0),IntArray(0),
        doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(-camera.toDouble(),0.0,0.0)) }
    @Test fun staleIdentityAndPoseConflictsAreMissesNotSilentOverwrites() = runBlocking {
        val dir=Files.createTempDirectory("registration-session").toFile();val stage="a".repeat(64)
        val ids=listOf("real-c","real-a","real-b");val v=views();val accepted=v.indices.map { RegisteredFrame(it,v[it]) }
        try {
            val store=SparseFrontierCheckpointStore(dir)
            fun run()=kotlinx.coroutines.runBlocking { AdmittedRegistrationSession.grow(dir,stage,ids,v,accepted,
                {_,_->error("All poses supplied")},{_,_,_->error("No triangulation")},{_,_->error("No PnP")}) }
            val first=run();assertEquals("absent",first.resumeStatus);assertEquals(0,first.failedCheckpointWrites)
            assertEquals(2,first.persistedCheckpoints);assertEquals(3,first.growth.cameras.size)
            val complete=run();assertEquals("restored",complete.resumeStatus);assertEquals(3,complete.restoredCameraCount)
            assertEquals(2,complete.persistedCheckpoints);assertTrue(complete.growth.unregistered.isEmpty())
            val saved=RegistrationFrontier.decode(requireNotNull(store.load(stage){false}))
            val changed=RegistrationFrontier(saved.inputIdentity,ids,saved.cameras.mapIndexed { i,c -> if(i==2)
                FrontierCamera(c.photoId,c.rotation,listOf(-9.0,0.0,0.0)) else c },emptyMap())
            assertTrue(store.publish(stage,changed.encode()){false})
            val conflict=run();assertTrue(conflict.resumeStatus.startsWith("rejected:accepted_pose_conflict"))
            assertArrayEquals(v[2].translation,conflict.growth.cameras[2].view.translation,0.0)
            val wrong=RegistrationFrontier("b".repeat(64),ids,saved.cameras,emptyMap())
            assertTrue(store.publish(stage,wrong.encode()){false})
            assertTrue(run().resumeStatus.startsWith("rejected:stale_features"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun corruptPayloadAndUnavailablePrivateStoreDoNotClaimDurability() = runBlocking {
        val dir=Files.createTempDirectory("registration-session").toFile();val stage="a".repeat(64)
        val ids=listOf("real-c","real-a","real-b");val v=views();val accepted=v.indices.map { RegisteredFrame(it,v[it]) }
        try {
            val store=SparseFrontierCheckpointStore(dir)
            assertTrue(store.publish(stage,ByteArray(80){1}){false})
            val result=AdmittedRegistrationSession.grow(dir,stage,ids,v,accepted,
                {_,_->error("All poses supplied")},{_,_,_->error("No triangulation")},{_,_->error("No PnP")})
            assertTrue(result.resumeStatus.startsWith("rejected:"))
            val notDirectory=dir.resolve("ordinary-file").apply {writeText("preserve")}
            val unavailable=AdmittedRegistrationSession.grow(notDirectory,stage,ids,v,accepted,
                {_,_->error("All poses supplied")},{_,_,_->error("No triangulation")},{_,_->error("No PnP")})
            assertEquals(0,unavailable.persistedCheckpoints);assertEquals(2,unavailable.failedCheckpointWrites)
            assertEquals("preserve",notDirectory.readText())
        } finally {dir.deleteRecursively()}
    }
}
