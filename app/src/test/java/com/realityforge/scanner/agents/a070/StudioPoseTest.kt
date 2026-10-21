package com.realityforge.scanner.agents.a070

import com.realityforge.scanner.ui.studio.StudioPose
import org.junit.Assert.*
import org.junit.Test

class StudioPoseTest {
    @Test fun commandsAreBoundedReversibleAndDoNotChangeSourcePose() {
        val source=StudioPose()
        val changed=source.orbit(30f,10f).pan(.5f,-.25f).magnify(2f)
        assertEquals(StudioPose(),source)
        assertEquals(source,changed.orbit(-30f,-10f).pan(-.5f,.25f).magnify(.5f))
        assertEquals(89f,source.orbit(0f,1000f).pitch,0f)
        assertEquals(5f,source.magnify(100f).zoom,0f)
        assertEquals(-2f,source.pan(-100f,0f).panX,0f)
    }
    @Test fun nonfiniteAndInvalidCommandsFailClosed() {
        for (command in listOf<() -> Unit>(
            { StudioPose().orbit(Float.NaN,0f) },
            { StudioPose().magnify(0f) },
            { StudioPose().pan(0f,Float.POSITIVE_INFINITY) },
            { StudioPose(zoom=-1f) },
        )) {
            try { command(); fail("Invalid pose accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
