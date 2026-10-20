package com.realityforge.scanner.agents.a075

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
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
import com.realityforge.scanner.ui.onboarding.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Android accessibility-tree interactions with the real composable; fixture states are synthetic. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        root?.let(pending::add)
        while (pending.isNotEmpty() && result.size < 256) {
            val current = pending.removeFirst()
            result.add(current)
            for (index in 0 until minOf(current.childCount, 32)) {
                if (pending.size + result.size >= 256) break
                current.getChild(index)?.let(pending::add)
            }
        }
        return result
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun tagged(node: AccessibilityNodeInfo, tag: String): Boolean =
        node.viewIdResourceName == tag || node.viewIdResourceName?.endsWith("/$tag") == true
    private fun fixtureRoot(tag: String): AccessibilityNodeInfo? =
        descendants(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
            it.isVisibleToUser && !bounds(it).isEmpty && tagged(it, tag)
        }

    /** Only known guide resource labels are included; arbitrary text/field values are omitted. */
    private fun failWithTree(message: String): Nothing {
        val context = instrumentation.targetContext
        val knownLabels = listOf(R.string.a075_title, R.string.a075_subject_title, R.string.a075_overlap_title,
            R.string.a075_optics_title, R.string.a075_review_title, R.string.a075_calibration_title,
            R.string.a075_limits_title, R.string.a075_privacy_title, R.string.a075_next,
            R.string.a075_back, R.string.a075_skip, R.string.a075_start).map(context::getString).toSet()
        fun safe(value: CharSequence?): String = value?.toString()
            ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(120) ?: "<none>"
        val tree = runCatching {
            descendants(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n") { node ->
                val actual = node.text?.toString()
                val label = if (actual in knownLabels) safe(actual) else "<omitted>"
                "package=${safe(node.packageName)} class=${safe(node.className)} id=${safe(node.viewIdResourceName)} " +
                    "bounds=${bounds(node)} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                    "clickable=${node.isClickable} editable=${node.isEditable} guideLabel=$label"
            }.let { if (it.length > 6000) it.take(5980) + "\n<tree truncated>" else it }
        }.getOrElse { "<tree unavailable: ${it.javaClass.simpleName}>" }
        Log.e("A075Interaction", message)
        tree.chunked(2000).forEachIndexed { index, part -> Log.e("A075Interaction", "tree[$index]=$part") }
        throw AssertionError("$message\nBounded actual accessibility structure:\n$tree")
    }

    private fun awaitFixture(tag: String) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            if (fixtureRoot(tag) != null) return
            SystemClock.sleep(100)
        }
        failWithTree("Replacement synthetic guide fixture did not become visible: $tag")
    }

    private fun node(tag: String, text: String, scrollAction: Int): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 20000
        var scrolls = 0
        var lastScroll = 0L
        while (SystemClock.elapsedRealtime() < end) {
            val root = fixtureRoot(tag)
            if (root != null) {
                val viewport = bounds(root)
                val nodes = descendants(root).filter { it.isVisibleToUser && Rect.intersects(viewport, bounds(it)) }
                val found = nodes.firstOrNull { it.text?.toString() == text }
                    ?: nodes.firstOrNull { it.contentDescription?.toString() == text }
                if (found != null) return found
                if (scrolls < 8 && SystemClock.elapsedRealtime() - lastScroll >= 750 &&
                    root.actionList.any { it.id == scrollAction }) {
                    if (!root.performAction(scrollAction)) scrolls = 8 else scrolls++
                    lastScroll = SystemClock.elapsedRealtime()
                }
            }
            SystemClock.sleep(100)
        }
        failWithTree("Required guide control/title missing from fixture=$tag; resource label=$text, scrolls=$scrolls")
    }

    private fun click(tag: String, text: String) {
        var target = node(tag, text, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        while (!target.isClickable && target.parent != null) target = target.parent
        val root = fixtureRoot(tag)
        if (root == null || !target.isVisibleToUser || !Rect.intersects(bounds(root), bounds(target)) ||
            !target.isEnabled || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            failWithTree("Real guide control lacks a visible enabled accessibility click action: $text")
        }
    }

    @Test fun checkerboardIsPackagedAndReadableEntirelyOffline() {
        val context = instrumentation.targetContext
        val bytes = context.assets.open(CaptureGuide.BOARD_ASSET).use { it.readBytes() }
        assertTrue(bytes.size in 1..100000)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("9×6 INTERIOR corners"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("href="))
    }

    @Test fun realControlsDispatchBoundActions() {
        val context = instrumentation.targetContext
        val skipped = AtomicInteger()
        val completed = AtomicInteger()
        val fixture = "a075-fixture-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    key(fixture) {
                        OnboardingScreen({ completed.incrementAndGet() }, { skipped.incrementAndGet() },
                            Modifier.testTag(fixture).semantics { testTagsAsResourceId = true })
                    }
                }
            } }
            awaitFixture(fixture)
            node(fixture, context.getString(R.string.a075_subject_title), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            click(fixture, context.getString(R.string.a075_next))
            node(fixture, context.getString(R.string.a075_overlap_title), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            assertEquals(0, skipped.get())
            assertEquals(0, completed.get())
            click(fixture, context.getString(R.string.a075_skip))
            val end = SystemClock.elapsedRealtime() + 10000
            while (skipped.get() == 0 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            assertEquals(1, skipped.get())
            assertEquals(0, completed.get())
        }
    }
}
