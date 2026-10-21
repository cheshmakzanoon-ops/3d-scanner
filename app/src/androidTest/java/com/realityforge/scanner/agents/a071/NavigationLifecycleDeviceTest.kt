package com.realityforge.scanner.agents.a071

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Activity and Compose accessibility tree; no controller shortcuts or source-string checks. */
@RunWith(AndroidJUnit4::class)
class NavigationLifecycleDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun tag(id:String):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<deadline) {
            instrumentation.waitForIdleSync()
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root!=null) {
                fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                    if(n.viewIdResourceName==id || n.viewIdResourceName?.endsWith(":id/$id")==true) return n
                    for(i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let {found->return found} }
                    return null
                }
                find(root)?.let {return it}
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing actual route/control: $id")
    }
    @Test fun settingsRouteAndBackSurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            assertTrue(tag("a071-open-menu").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(tag("a071-open-settings").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            tag("a071-route-settings")
            scenario.recreate()
            tag("a071-route-settings")
            assertTrue(tag("a071-back").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            tag("a071-route-library")
        }
    }
}
