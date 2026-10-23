package com.realityforge.scanner.agents.a037

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** New JNI contract coverage. Device execution is required; host tests are not a substitute. */
@RunWith(AndroidJUnit4::class)
class CalibratedInitializerNativeTest {
    private data class Input(val rows: DoubleArray, val pixels: DoubleArray, val domains: DoubleArray)
    private fun input(): Input {
        val random=Random(640293);val rows=DoubleArray(240*7);val pixels=DoubleArray(240*4)
        for(i in 0 until 240) {
            val x=random.nextDouble()*1.8-.9;val y=random.nextDouble()*1.4-.7;val z=4+.45*x+.2*y
            val qx=cos(.1)*x+sin(.1)*z-.65;val qy=y+.02;val qz=-sin(.1)*x+cos(.1)*z+.02
            doubleArrayOf(x/z,y/z,qx/qz,qy/qz,120.0,130.0,140.0).copyInto(rows,i*7)
            doubleArrayOf(480*x/z+191.5,480*y/z+143.5,480*qx/qz+191.5,480*qy/qz+143.5).copyInto(pixels,i*4)
        }
        val domain=doubleArrayOf(480.0,480.0,191.5,143.5,0.0,0.0,0.0,0.0,0.0,384.0,288.0)
        return Input(rows,pixels,domain+domain)
    }
    @Test fun calibratedPlaneReportsModelAndSupportWithoutChangingLegacyContract() {
        val input=input();val result=NativeResultJson.decode(NativeCore.reconstructCalibrated(input.rows,input.pixels,input.domains,.003125))
        assertFalse(result.toString(),result.has("error"));assertEquals("HOMOGRAPHY",result.getString("initializerModel"))
        assertEquals(240,result.getInt("homographyInliers"));assertEquals(result.getInt("inliers"),result.getInt("modelInliers"))
        assertTrue(result.getInt("pointCount")>=230)
        val legacy=NativeResultJson.decode(NativeCore.reconstruct(input.rows,.003125))
        assertFalse(legacy.has("initializerModel"))
    }
    @Test fun calibratedJniRejectsMalformedDomainShapeAndMisalignedPixels() {
        val input=input()
        try {NativeCore.reconstructCalibrated(input.rows,input.pixels,input.domains.copyOf(21),.003125);fail("expected JNI shape rejection")}catch(_:IllegalArgumentException){}
        input.pixels[0]+=1
        assertEquals("INVALID_INPUT",NativeResultJson.decode(NativeCore.reconstructCalibrated(input.rows,input.pixels,input.domains,.003125)).getString("errorCode"))
    }
    @Test fun cancelledCalibratedCallHonorsScopedOperationLease() {
        val input=input();val token=NativeCore.beginReconstruction()
        try {
            assertTrue(NativeCore.cancelReconstruction(token))
            try {NativeResultJson.decode(NativeCore.reconstructCalibratedCancellable(input.rows,input.pixels,input.domains,.003125,token));fail("expected cancellation")}catch(_:CancellationException){}
        } finally {assertTrue(NativeCore.finishReconstruction(token))}
    }
}
