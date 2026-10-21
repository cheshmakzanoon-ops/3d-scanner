package com.realityforge.scanner.agents.a069

import com.realityforge.scanner.viewer.DisplayCamera
import org.junit.Assert.*
import org.junit.Test

/** Independent asymmetric reference coordinates catch opposite yaw and world-relative pan. */
class DisplayCameraTest {
    private fun camera(point: FloatArray,yaw: Float,pitch: Float,panX: Float=0f,panY: Float=0f): FloatArray {
        val m=DisplayCamera.rotation(yaw,pitch,FloatArray(16))
        val v=DisplayCamera.view(panX,panY,DisplayCamera.distance(128,128,1f),FloatArray(16))
        val world=FloatArray(3) { r -> m[r]*point[0]+m[4+r]*point[1]+m[8+r]*point[2]+m[12+r] }
        return FloatArray(3) { r -> v[r]*world[0]+v[4+r]*world[1]+v[8+r]*world[2]+v[12+r] }
    }
    @Test fun positiveYawMatchesTheExistingGlesModelRotation() {
        assertArrayEquals(floatArrayOf(.6830127f,0f,-2.8169873f),camera(floatArrayOf(.5f,0f,.5f),30f,0f),1e-6f)
    }
    @Test fun panRemainsInViewAxesAtRightAngleYaw() {
        assertArrayEquals(floatArrayOf(-.4f,0f,-3f),camera(floatArrayOf(0f,0f,0f),90f,0f,.4f),1e-6f)
        assertArrayEquals(floatArrayOf(3f,1.6f,-4f),camera(floatArrayOf(1f,2f,3f),90f,0f,0f,.4f),1e-6f)
    }
    @Test fun pitchFollowsYawAndDoesNotSwapTheTransformOrder() {
        assertArrayEquals(floatArrayOf(.3f,.1f,-2.8f),camera(floatArrayOf(.1f,.2f,.3f),90f,90f),1e-6f)
    }
}
