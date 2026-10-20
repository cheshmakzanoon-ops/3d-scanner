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
            val found=root?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString()==text }
            if(found!=null) return found
            Thread.sleep(100)
        }
        error("Visible semantic node missing: $text")
    }
    private fun click(text:String) {
        var target:AccessibilityNodeInfo?=node(text)
        while(target!=null && !target.isClickable) target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
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
