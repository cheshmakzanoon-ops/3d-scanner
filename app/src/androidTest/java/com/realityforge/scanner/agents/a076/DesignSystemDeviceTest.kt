package com.realityforge.scanner.agents.a076

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.ui.designsystem.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class DesignSystemDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val label = "Toggle verification"

    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        root?.let(pending::add)
        while (pending.isNotEmpty() && result.size < 128) {
            val current = pending.removeFirst()
            result.add(current)
            for (index in 0 until minOf(current.childCount, 32)) {
                if (pending.size + result.size >= 128) break
                current.getChild(index)?.let(pending::add)
            }
        }
        return result
    }

    private fun tagged(node: AccessibilityNodeInfo, tag: String): Boolean =
        node.viewIdResourceName == tag || node.viewIdResourceName?.endsWith("/$tag") == true
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun fixtureRoot(tag: String): AccessibilityNodeInfo? =
        descendants(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
            tagged(it, tag) && it.isVisibleToUser && !bounds(it).isEmpty
        }
    private fun namedNodes(tag: String): List<AccessibilityNodeInfo> =
        descendants(fixtureRoot(tag)).filter {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }.mapNotNull { labelNode ->
            // Android Compose exposes the text child and toggle action separately.
            // Resolve only its nearest checkable owner inside this exact fixture.
            var node: AccessibilityNodeInfo? = labelNode
            var owner: AccessibilityNodeInfo? = null
            repeat(16) {
                val current = node ?: return@repeat
                if (current.isCheckable && current.isClickable) { owner = current; node = null }
                else node = if (tagged(current, tag)) null else current.parent
            }
            owner
        }.distinct()

    /** The only text included is this test's fixed synthetic label; field values are omitted. */
    private fun failWithTree(message: String): Nothing {
        fun safe(value: CharSequence?): String = value?.toString()
            ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(120) ?: "<none>"
        val tree = runCatching {
            descendants(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n") { node ->
                val named = node.text?.toString() == label || node.contentDescription?.toString() == label
                "package=${safe(node.packageName)} class=${safe(node.className)} id=${safe(node.viewIdResourceName)} " +
                    "bounds=${bounds(node)} visible=${node.isVisibleToUser} clickable=${node.isClickable} " +
                    "checkable=${node.isCheckable} checked=${node.isChecked} syntheticLabel=$named"
            }.let { if (it.length > 6000) it.take(5980) + "\n<tree truncated>" else it }
        }.getOrElse { "<tree unavailable: ${it.javaClass.simpleName}>" }
        Log.e("A076DesignTest", message)
        tree.chunked(2000).forEachIndexed { index, part -> Log.e("A076DesignTest", "tree[$index]=$part") }
        throw AssertionError("$message\nBounded actual accessibility structure (field values omitted):\n$tree")
    }

    private fun awaitActual(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        failWithTree(message)
    }

    @Test fun largeTextLabelAndToggleShareOneOperableAction() {
        val checked = mutableStateOf(false)
        val callbacks = AtomicInteger()
        val fixture = "a076-fixture-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    AppTheme {
                        ScreenFrame {
                            key(fixture) {
                                LabelledToggle(label, checked.value, { checked.value = it; callbacks.incrementAndGet() },
                                    Modifier.testTag(fixture).semantics { testTagsAsResourceId = true })
                            }
                        }
                    }
                }
            } }
            // A real fixture ID and label, rather than Android queue idleness, prove readiness.
            awaitActual("The 200% synthetic toggle fixture did not render: $fixture") {
                fixtureRoot(fixture) != null && namedNodes(fixture).isNotEmpty()
            }
            val nodes = namedNodes(fixture)
            assertEquals("The label and switch must expose exactly one named node", 1, nodes.size)
            val toggle = nodes.single()
            assertTrue("The named toggle must be enabled, clickable and checkable", toggle.isEnabled && toggle.isClickable && toggle.isCheckable)
            assertFalse("The actual toggle must initially be unchecked", toggle.isChecked)
            val viewport = Rect()
            scenario.onActivity { it.window.decorView.getWindowVisibleDisplayFrame(viewport) }
            val target = bounds(toggle)
            val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("The 200% toggle must remain inside the actual window viewport", viewport.contains(target))
            assertTrue("The named toggle must retain its 48dp target", target.width() >= minimum && target.height() >= minimum)
            assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitActual("The real toggle click did not update callback and accessibility checked state") {
                checked.value && callbacks.get() == 1 && namedNodes(fixture).let { it.size == 1 && it.single().isChecked }
            }
            assertTrue(checked.value)
            assertEquals(1, callbacks.get())
            assertTrue(namedNodes(fixture).single().isChecked)
        }
    }
}
