package com.realityforge.scanner.agents.a074

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.realityforge.scanner.ui.processing.*
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

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
    @Test fun realControlsDispatchBoundActions() {
        val actions=java.util.concurrent.CopyOnWriteArrayList<JobAction>()
        val identity=JobIdentity("p",4,"job-synthetic")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { ProcessingScreen(JobUiState(identity,JobPhase.RUNNING),{ actions.add(it) }) } } }
            node("The remaining amount of work is unknown.")
            click("Pause")
            assertEquals(JobAction(identity,JobCommand.PAUSE),actions.single())
        }
    }
    @Test fun actualScreenBlocksRepeatedDispatchUntilAcceptedIdentityChanges() {
        val calls=AtomicInteger()
        val completion=CompletableDeferred<Boolean>()
        val identity=JobIdentity("p",4,"job-synthetic",1,2)
        val visible=mutableStateOf(JobUiState(identity,JobPhase.RUNNING))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    ProcessingScreen(visible.value,{ calls.incrementAndGet();completion.await() })
                }
            } }
            click("Pause")
            assertEquals(1,calls.get())
            fun target():AccessibilityNodeInfo {
                var value=node("Pause")
                while(value.className?.toString()!="android.widget.Button" &&
                    value.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK } && value.parent!=null) value=value.parent
                return value
            }
            assertFalse("Pending action must disable the actual button",target().isEnabled)
            target().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            assertEquals("Repeated accessibility action must not dispatch",1,calls.get())
            completion.complete(true)
            instrumentation.waitForIdleSync()
            assertFalse("Accepted mutation stays pending until persisted version changes",target().isEnabled)
            scenario.onActivity {
                visible.value=visible.value.copy(identity=identity.copy(stateVersion=3))
            }
            instrumentation.waitForIdleSync()
            assertTrue(target().isEnabled)
            click("Pause")
            assertEquals(2,calls.get())
        }
    }
}
