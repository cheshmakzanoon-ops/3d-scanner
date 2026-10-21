package com.realityforge.scanner.agents.a079

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.R
import com.realityforge.scanner.ui.preferences.*
import com.realityforge.scanner.preferences.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID

/** Actual composable/semantic actions with synthetic preferences. No global-idle prerequisite. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        root?.let(queue::add)
        while (queue.isNotEmpty() && result.size < 256) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until minOf(node.childCount,32)) {
                if (queue.size + result.size >= 256) break
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun tagged(node: AccessibilityNodeInfo, fixture: String): Boolean =
        node.viewIdResourceName == fixture || node.viewIdResourceName?.endsWith("/$fixture") == true
    private fun fixtureRoot(fixture: String): AccessibilityNodeInfo? =
        descendants(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
            tagged(it,fixture) && it.isVisibleToUser && !bounds(it).isEmpty
        }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val tree = descendants(instrumentation.uiAutomation.rootInActiveWindow).take(32).joinToString("\n") {
            "package=${it.packageName} class=${it.className} id=${it.viewIdResourceName} bounds=${bounds(it)} visible=${it.isVisibleToUser} enabled=${it.isEnabled}"
        }
        error("Timed out waiting for $description in the unique preferences fixture; structural tree:\n$tree")
    }
    private fun click(fixture: String, resource: Int) {
        val label = instrumentation.targetContext.getString(resource)
        var target: AccessibilityNodeInfo? = null
        var scrolls = 0
        var lastScroll = 0L
        await("visible enabled preferences control $label") {
            val root = fixtureRoot(fixture)
            if (root == null) false else {
                val viewport = bounds(root)
                val nodes = descendants(root)
                val found = nodes.firstOrNull {
                    it.isVisibleToUser && Rect.intersects(viewport,bounds(it)) &&
                        (it.text?.toString() == label || it.contentDescription?.toString() == label)
                }
                if (found != null) {
                    var candidate: AccessibilityNodeInfo = requireNotNull(found)
                    while (!candidate.isClickable && !tagged(candidate,fixture)) {
                        val next = candidate.parent ?: break
                        candidate = next
                    }
                    target = candidate.takeIf { it.isClickable && it.isEnabled && it.isVisibleToUser && Rect.intersects(viewport,bounds(it)) }
                }
                if (target == null && scrolls < 8 && SystemClock.elapsedRealtime() - lastScroll >= 750) {
                    val scroll = nodes.firstOrNull {
                        it.isVisibleToUser && Rect.intersects(viewport,bounds(it)) &&
                            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                    }
                    if (scroll != null) {
                        if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolls++ else scrolls = 8
                        lastScroll = SystemClock.elapsedRealtime()
                    }
                }
                target != null
            }
        }
        assertTrue("Actual preferences accessibility click was rejected",requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun actualAndroidPreferencesSurviveActivityAndStoreRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=java.io.File(context.filesDir,"a079-synthetic-"+java.util.UUID.randomUUID())
        val expected=UserPreferences(35.5,DisplayTheme.DARK,1)
        try {
            PreferencesStore(directory).save(expected)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                val restored=PreferencesStore(directory).read()
                assertNull(restored.issue)
                assertEquals(expected,restored.preferences)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun realControlsDispatchBoundActions() {
        val actions=java.util.concurrent.CopyOnWriteArrayList<PreferencesAction>()
        val initial=UserPreferences()
        val fixture="a079-preferences-fixture-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    key(fixture) {
                        PreferencesScreen(PreferencesUiState(initial,"synthetic-test-version"),{ actions.add(it) },
                            Modifier.testTag(fixture).semantics { testTagsAsResourceId=true })
                    }
                }
            } }
            await("unique preferences fixture") { fixtureRoot(fixture) != null }
            click(fixture,R.string.a079_dark)
            assertTrue("Theme selection alone must not persist settings",actions.isEmpty())
            click(fixture,R.string.a079_save)
            await("exactly one saved-defaults callback") { actions.size == 1 }
            val saved=actions.single() as PreferencesAction.Save
            assertEquals(initial.copy(theme=DisplayTheme.DARK),saved.preferences)
        }
    }
}
