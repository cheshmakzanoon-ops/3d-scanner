package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.abs

/**
 * Synthetic calibrated feature observations with intentionally missing visibility.
 * New camera ground-truth poses are NEVER passed to the registration solver.
 * This exercises the Android helper AND production native triangulation/PnP,
 * but is not photographic feature extraction or a full physical-object benchmark.
 */
@RunWith(AndroidJUnit4::class)
class IncrementalNativeRegistrationTest {
    @Test fun growsAcrossThreeVisibilityGroupsAndRetriesEarlierCandidates() = runBlocking {
        val rng=Random(160013)
        val points=List(210) { i ->
            doubleArrayOf(i/70*.8+rng.nextDouble()*1.6-.8,rng.nextDouble()*1.4-.7,4+rng.nextDouble()*3)
        }
        // Deliberately present later capture positions first to require retries.
        val physicalOrder=listOf(0,1,4,3,2,5)
        val ids=physicalOrder.map { physical ->
            points.indices.filter { point ->
                when(point/70) { 0 -> physical in 0..2;1 -> physical in 1..3;else -> physical in 2..4 }
            }
        }
        val f=700.0;val center=799.5
        val templates=physicalOrder.mapIndexed { photo,physical ->
            val xy=FloatArray(ids[photo].size*2)
            for((key,id) in ids[photo].withIndex()) {
                val p=points[id]
                xy[key*2]=(center+f*(p[0]-physical*.45)/p[2]).toFloat()
                xy[key*2+1]=(center+f*p[1]/p[2]).toFloat()
            }
            SparseView(1600,1600,f,xy,IntArray(ids[photo].size) { 0xffb2cab1.toInt() },
                doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(0.0,0.0,0.0))
        }
        val anchor0=RegisteredFrame(0,templates[0])
        val anchor1=RegisteredFrame(1,templates[1].copy(translation=doubleArrayOf(-.45,0.0,0.0)))
        // Canonicalize the trusted app-owned root, not an arbitrary checkpoint input.
        val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val directory=java.nio.file.Files.createTempDirectory(cache.toPath(),"registration-frontier-jni").toFile()
        var primaryFailure:Throwable?=null
        try {
        assertEquals("Fixture checkpoint root must already be canonical",directory.canonicalFile,directory)
        val stage="a".repeat(64)
        val photoIds=physicalOrder.map {"synthetic-physical-$it"}
        val matchCalls=mutableSetOf<Pair<Int,Int>>()
        val matcher:suspend (Int,Int)->FeaturePairs = { a,b ->
                matchCalls.add(a to b)
                val destinations=ids[b].withIndex().associate { it.value to it.index }
                val shared=ids[a].withIndex().mapNotNull { entry ->
                    destinations[entry.value]?.let { entry.index to it }
                }
                FeaturePairs(shared.map { it.first }.toIntArray(),shared.map { it.second }.toIntArray())
            }
        // First operation is cancelled only AFTER an actual unknown pose is solved
        // and its checkpoint has been atomically persisted to the real private store.
        val firstToken=NativeCore.beginReconstruction()
        try {
            val geometry=NativeFrontierGeometry.forOperation(firstToken)
            try {
                AdmittedRegistrationSession.grow(directory,stage,photoIds,templates,listOf(anchor0,anchor1),matcher,
                    geometry.triangulate,geometry.register,checkpointEvent={frontier,durable ->
                        assertTrue("Private checkpoint must be durable in this fixture",durable)
                        if(frontier.cameras.size==3) {
                            assertTrue(NativeCore.cancelReconstruction(firstToken))
                            throw java.util.concurrent.CancellationException("cancel after accepted persisted camera")
                        }
                    })
                fail("Mid-growth cancellation was swallowed")
            } catch(expected:java.util.concurrent.CancellationException) {
                assertEquals("cancel after accepted persisted camera",expected.message)
            }
        } finally { assertTrue(NativeCore.finishReconstruction(firstToken)) }
        val disk=com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore(directory)
        val persisted=com.realityforge.scanner.engine.registration.RegistrationFrontier.decode(requireNotNull(disk.load(stage){false}))
        assertEquals(3,persisted.cameras.size)
        assertEquals(photoIds.take(2),persisted.cameras.take(2).map {it.photoId})
        assertFalse(persisted.photoIds.any {it in listOf("0","1","2")})
        assertNull(disk.load("b".repeat(64)){false})
        // Cold reload with a NEW native lease and shuffled caller ordering. Only
        // the two original anchor poses are supplied, never other ground truth.
        val reorder=listOf(5,3,1,4,0,2)
        val reorderedIds=reorder.map {photoIds[it]};val reorderedTemplates=reorder.map {templates[it]}
        val reorderedInitial=listOf(anchor0,anchor1).map { RegisteredFrame(reorder.indexOf(it.photo),it.view) }
        val secondToken=NativeCore.beginReconstruction()
        try {
        val geometry=NativeFrontierGeometry.forOperation(secondToken)
        val session=AdmittedRegistrationSession.grow(directory,stage,reorderedIds,reorderedTemplates,reorderedInitial,
            {a,b -> matcher(reorder[a],reorder[b])},geometry.triangulate,geometry.register)
        assertEquals("restored",session.resumeStatus);assertEquals(3,session.restoredCameraCount)
        assertTrue(session.persistedCheckpoints>0);assertEquals(0,session.failedCheckpointWrites)
        for(saved in persisted.cameras) {
            val recovered=session.growth.cameras.single { reorderedIds[it.photo]==saved.photoId }
            assertArrayEquals(saved.rotation.toDoubleArray(),recovered.view.rotation,0.0)
            assertArrayEquals(saved.translation.toDoubleArray(),recovered.view.translation,0.0)
        }
        val result=RegistrationGrowth(session.growth.cameras.map { RegisteredFrame(reorder[it.photo],it.view) },
            session.growth.attempts.map { it.copy(photo=reorder[it.photo]) },session.growth.unregistered.map {reorder[it]})
        assertEquals("Only the deliberately disconnected camera should remain",listOf(5),result.unregistered)
        assertEquals(listOf(0,1,4,3,2),result.cameras.map { it.photo })
        assertTrue(result.attempts.any { it.photo==2 && !it.accepted })
        assertTrue(result.attempts.any { it.photo==2 && it.accepted && it.registeredBasis==4 })
        assertTrue(result.attempts.filter { it.accepted }.all { it.tracks>=60 })
        assertArrayEquals(anchor0.view.translation,result.cameras[0].view.translation,0.0)
        assertArrayEquals(anchor1.view.translation,result.cameras[1].view.translation,0.0)
        var maxTranslationError=0.0
        for(camera in result.cameras) {
            val expected=-physicalOrder[camera.photo]*.45
            maxTranslationError=maxOf(maxTranslationError,abs(expected-camera.view.translation[0]))
            assertEquals(expected,camera.view.translation[0],.01)
            assertEquals(0.0,camera.view.translation[1],.01)
            assertEquals(0.0,camera.view.translation[2],.01)
            for(k in 0..8) assertEquals(if(k%4==0)1.0 else 0.0,camera.view.rotation[k],.003)
        }
        assertTrue(matchCalls.isNotEmpty())
        assertTrue(matchCalls.map { minOf(it.first,it.second) to maxOf(it.first,it.second) }.distinct().size<=15)
        // Build the COMPLETE graph afresh from accepted poses, never the last frontier's window snapshot.
        val accepted=result.cameras;val full=MultiViewTrackGraph(accepted.map {it.view})
        for(a in accepted.indices)for(b in a+1 until accepted.size) {
            val pairs=matcher(accepted[a].photo,accepted[b].photo)
            for(k in pairs.first.indices)full.add(a,pairs.first[k],b,pairs.second[k])
        }
        val acceptedIds=accepted.map {photoIds[it.photo]};val snapshot=full.stableSnapshot(acceptedIds)
        val final=FinalSparseExecution.execute(acceptedIds,accepted.map {it.view},snapshot,null,3.0/f,100,
            triangulate={window -> geometry.triangulate(window.cameraRows,window.normalizedRows,3.0/f).map { point ->
                BoundedSparseCoordinator.Candidate(window.globalTrackIds[point.track],point.xyz[0],point.xyz[1],point.xyz[2]) } },
            bundle={input -> org.json.JSONObject(NativeCore.bundleAdjustProblemCancellable(input.cameraRows,input.seedRows,
                input.pixelRows,input.intrinsicRows,input.groupIds,input.photoIds,input.sourceTrackIds,false,false,3.0/f,15,secondToken))})
        assertEquals(5,final.geometry.getInt("registeredCameras"));assertEquals(210,final.receipt.seeds.size)
        assertEquals(630,final.receipt.inputObservations);assertEquals(630,final.receipt.acceptedObservations)
        assertEquals(210,final.geometry.getInt("pointCount"))
        println("M16 analytical visibility-chain: cameras=${result.cameras.size}, disconnected=${result.unregistered.size}, maxTranslationError=$maxTranslationError; not a physical scan")
        } finally { assertTrue(NativeCore.finishReconstruction(secondToken)) }
        } catch (failure:Throwable) { primaryFailure=failure;throw failure }
        finally {
            if (!directory.deleteRecursively()) {
                val cleanup=AssertionError("Could not remove owned synthetic registration checkpoint directory")
                if (primaryFailure==null) throw cleanup else primaryFailure.addSuppressed(cleanup)
            }
        }
    }
}
