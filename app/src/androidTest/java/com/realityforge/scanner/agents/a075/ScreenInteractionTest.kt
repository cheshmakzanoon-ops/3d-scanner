package com.realityforge.scanner.agents.a075

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.realityforge.scanner.ui.onboarding.*

/** Android accessibility-tree interactions with the real composable; fixture states are synthetic. */
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private fun node(text:String):AccessibilityNodeInfo {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val end=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<end) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            val matches=root?.findAccessibilityNodeInfosByText(text).orEmpty()
            val found=matches.firstOrNull { it.text?.toString()==text } ?: matches.firstOrNull { it.text?.contains(text)==true }
            if(found!=null) return found
            Thread.sleep(100)
        }
        error("Visible semantic node missing: $text")
    }
    private fun click(text:String) {
        var target:AccessibilityNodeInfo?=node(text)
        while(target!=null && !target.isClickable) target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @Test fun checkerboardIsPackagedAndReadableEntirelyOffline() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bytes=context.assets.open(CaptureGuide.BOARD_ASSET).use { it.readBytes() }
        assertTrue(bytes.size in 1..100000)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("9×6 INTERIOR corners"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("href="))
    }
    @Test fun realControlsDispatchBoundActions() {
        val skipped=java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { OnboardingScreen({}, {skipped.incrementAndGet()}) } } }
            node("Choose a useful subject")
            click("Skip guide")
            assertEquals(1,skipped.get())
        }
    }
}
