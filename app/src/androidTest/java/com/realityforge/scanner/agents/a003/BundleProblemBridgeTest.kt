package com.realityforge.scanner.agents.a003

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the same typed JNI entry point used by002, including actual043 resection and041 solve. */
@RunWith(AndroidJUnit4::class)
class BundleProblemBridgeTest {
    private class Fixture {
        val cameras=ArrayList<Double>();val seeds=ArrayList<Double>();val pixels=ArrayList<Double>();val models=ArrayList<Double>()
        val groups=Array(5){"measured-camera-crop"};val photos=Array(5){"photo-$it"}
        val tracks=Array(101){it.toString(16).padStart(64,'0')}
        init {
            repeat(5) { camera ->
                cameras.addAll(listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0,-camera*.12,0.0,0.0))
                models.addAll(listOf(640.0,480.0,500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002))
            }
            repeat(100) { track ->
                val x=(track%10-4.5)*.17;val y=(track/10-4.5)*.13;val z=3.0+(track%7)*.22
                seeds.addAll(listOf(track.toDouble(),x,y,z,130.0,90.0,60.0))
                repeat(5) { camera ->
                    val u=(x-camera*.12)/z;val v=y/z;val r2=u*u+v*v
                    val radial=1-.04*r2+.006*r2*r2+.0002*r2*r2*r2
                    val xd=u*radial+2*.001*u*v-.0005*(r2+2*u*u)
                    val yd=v*radial+.001*(r2+2*v*v)+2*(-.0005)*u*v
                    pixels.addAll(listOf(track.toDouble(),camera.toDouble(),500*xd+319.5,510*yd+239.5,130.0,90.0,60.0))
                }
            }
            pixels.addAll(listOf(100.0,0.0,320.0,240.0,130.0,90.0,60.0))
        }
        fun run(token:Long)=NativeCore.bundleAdjustProblemCancellable(cameras.toDoubleArray(),seeds.toDoubleArray(),pixels.toDoubleArray(),models.toDoubleArray(),groups,photos,tracks,true,true,.003,3,token)
    }

    @Test fun typedPixelModelsAndActualLoopResectionCrossJni() {
        val fixture=Fixture();val token=NativeCore.beginReconstruction()
        try {
            val result=JSONObject(fixture.run(token))
            assertEquals(1,result.getInt("bundleProblemSchema"))
            assertEquals("pose_and_baseline",result.getString("gaugeMode"))
            assertTrue(result.getInt("pointCount")>=90)
            assertTrue(result.getInt("loopProposalCount")>0)
            assertTrue(result.getInt("verifiedLoopCount")>0)
            val rows=result.getJSONArray("cameraIntrinsics")
            assertEquals(5,rows.length())
            for(i in 0 until rows.length()) {
                assertEquals(11,rows.getJSONArray(i).length())
                assertEquals(500.0,rows.getJSONArray(i).getDouble(2),0.0)
                assertEquals(-.04,rows.getJSONArray(i).getDouble(6),0.0)
            }
            assertEquals(0,result.getInt("acceptedIntrinsicGroups"))
            assertEquals(100,result.getJSONArray("stableTrackMapping").length())
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }

    @Test fun typedLeaseHonorsPrecancelAndRejectsRemappedSourceIds() {
        val fixture=Fixture();var token=NativeCore.beginReconstruction()
        try {
            assertTrue(NativeCore.cancelReconstruction(token))
            try { fixture.run(token);fail("Cancelled typed native call returned geometry") }
            catch(expected:CancellationException) { assertNotNull(expected.message) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
        token=NativeCore.beginReconstruction()
        try {
            fixture.tracks[1]=fixture.tracks[0]
            try { fixture.run(token);fail("Duplicate full source mapping accepted") }
            catch(expected:IllegalArgumentException) { assertNotNull(expected.message) }
        } finally { assertTrue(NativeCore.finishReconstruction(token)) }
    }
}
