package com.realityforge.scanner.agents.a073

import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.UUID
import java.util.ArrayDeque
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.realityforge.scanner.ui.photoreview.*

/** Android accessibility-tree interactions with the real composable; fixture states are synthetic. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private val fixtureId = "a073-review-${UUID.randomUUID()}"
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        root?.let { pending.add(it to 0) }
        while (pending.isNotEmpty() && result.size < 512) {
            val (node, depth) = pending.removeFirst()
            result.add(node)
            if (depth < 32) for (index in 0 until minOf(node.childCount, 32)) {
                if (pending.size + result.size >= 512) break
                node.getChild(index)?.let { pending.add(it to depth + 1) }
            }
        }
        return result
    }
    private fun node(text:String):AccessibilityNodeInfo {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val end=SystemClock.elapsedRealtime()+20000
        var scrolls=0
        var lastScroll=0L
        while(SystemClock.elapsedRealtime()<end) {
            val fixture=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.viewIdResourceName == fixtureId
            }
            val visible=nodes(fixture).filter { it.isVisibleToUser }
            visible.firstOrNull { it.text?.toString()==text }?.let { return it }
            // At 320dp/large font the reconstruction action is below the viewport.
            // Only scroll the positively identified replacement review fixture.
            if (fixture != null && scrolls < 12 && SystemClock.elapsedRealtime()-lastScroll >= 500) {
                visible.firstOrNull { it.isScrollable }?.let {
                    if (it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolls++ else scrolls=12
                    lastScroll=SystemClock.elapsedRealtime()
                }
            }
            SystemClock.sleep(100)
        }
        error("Visible semantic node missing in the review fixture after bounded scrolling: $text")
    }
    private fun click(text:String) {
        var target:AccessibilityNodeInfo?=node(text)
        while(target!=null && !target.isClickable) target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @Test fun realControlsDispatchBoundActions() {
        val actions=java.util.concurrent.CopyOnWriteArrayList<ReviewAction>()
        val photos=listOf(ReviewPhoto("a","synthetic-a.jpg",800,600,10,null,null,null,null),ReviewPhoto("b","synthetic-b.jpg",800,600,10,null,null,null,null))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { ProjectReviewScreen(ProjectReviewUiState("p",3,photos,"Synthetic review"),actions::add, Modifier.testTag(fixtureId).semantics { testTagsAsResourceId = true }) } } }
            click("Reconstruct selected photographs")
            assertEquals(ReviewAction.Command("p",3,ReviewCommand.RECONSTRUCT,26.0),actions.single())
        }
    }
}
