package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val matchCalls=mutableSetOf<Pair<Int,Int>>()
        val result=IncrementalRegistration.grow(templates,listOf(anchor0,anchor1),
            { a,b ->
                matchCalls.add(a to b)
                val destinations=ids[b].withIndex().associate { it.value to it.index }
                val shared=ids[a].withIndex().mapNotNull { entry ->
                    destinations[entry.value]?.let { entry.index to it }
                }
                FeaturePairs(shared.map { it.first }.toIntArray(),shared.map { it.second }.toIntArray())
            },NativeFrontierGeometry::triangulate,NativeFrontierGeometry::register)
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
        println("M16 analytical visibility-chain: cameras=${result.cameras.size}, disconnected=${result.unregistered.size}, maxTranslationError=$maxTranslationError; not a physical scan")
    }
}
