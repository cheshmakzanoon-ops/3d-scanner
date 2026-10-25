package com.realityforge.scanner.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Actual decoder/frontier persistence checks; not a JNI runtime qualification. */
class PnpFailureEvidenceTest {
    private fun reject(json: String): Nothing {
        val method = NativeFrontierGeometry::class.java.getDeclaredMethod("resected", String::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        try { method.invoke(NativeFrontierGeometry, json, 5000) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        error("Failure JSON was accepted as a pose")
    }
    @Test fun boundedEvidenceSurvivesActualDecoderAndRegistrationReasonLimit() = runBlocking {
        val message = "Third-camera registration failed: insufficient consistent 2D-3D tracks" +
            " [pnp1 raw=5000 distinct=5000 required=1400 best=1399 tested=450 valid=450 model=plane planarity=0.123456]"
        assertTrue(message.length <= 200)
        val encoded = JSONObject().put("error", message).toString()
        try { reject(encoded) } catch (failure: IllegalArgumentException) { assertEquals(message, failure.message) }
        fun view(tx: Double) = SparseView(128,128,100.0,
            FloatArray(40) { i -> if(i%2==0)(63.5+((i/2)*.1+tx)/5*100).toFloat() else 63.5f },
            IntArray(20) { -1 },doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(tx,0.0,0.0))
        val views=listOf(view(0.0),view(-.5),view(-1.0))
        val result=IncrementalRegistration.grow(views,listOf(RegisteredFrame(0,views[0]),RegisteredFrame(1,views[1])),
            { _,_ -> FeaturePairs(IntArray(20) { it },IntArray(20) { it }) },
            { _,_,_ -> List(20) { FrontierPoint(it,doubleArrayOf(it*.1,0.0,5.0)) } },
            { _,_ -> reject(encoded) })
        assertEquals(2,result.cameras.size)
        assertEquals(listOf(2),result.unregistered)
        assertEquals(message,result.attempts.single().reason)
        assertFalse(result.attempts.single().accepted)
    }
    @Test fun cancellationAndResourceFailuresNeverAcquireSolverEvidence() {
        for(code in listOf("CANCELLED","RESOURCE_EXHAUSTED")) {
            try { reject(JSONObject().put("errorCode",code).put("error","private solver text").toString()) }
            catch(failure: Exception) {
                if(code == "CANCELLED") assertTrue(failure is java.util.concurrent.CancellationException)
                else assertTrue(failure is com.realityforge.scanner.engine.nativebridge.NativeResourceExhaustedException)
                assertFalse(failure.message.orEmpty().contains("pnp1"))
                assertFalse(failure.message.orEmpty().contains("private solver text"))
            }
        }
    }
}
