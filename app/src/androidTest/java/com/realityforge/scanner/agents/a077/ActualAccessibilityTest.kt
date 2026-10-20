package com.realityforge.scanner.agents.a077

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real application accessibility tree/action checks. Does not claim a human TalkBack review. */
@RunWith(AndroidJUnit4::class)
class ActualAccessibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val all = mutableListOf(root)
        for (i in 0 until root.childCount) all += nodes(root.getChild(i))
        return all
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull(predicate)
    private fun await(timeout: Long = 20000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; SystemClock.sleep(100) }
        fail("Expected actual accessibility control did not become available")
    }
    private fun clickLabel(vararg labels: String) {
        var node: AccessibilityNodeInfo? = null
        await { node = find { n -> labels.any { n.text?.toString() == it || n.contentDescription?.toString() == it } }; node != null }
        var target = requireNotNull(node)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Control lacks accessibility click action", target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun realCreateDialogHasNamedEditableFieldAndReturnsToCorrectProject() {
        val name = "Accessibility ${UUID.randomUUID()}"
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // An integrated first-run guide may be skipped through its real visible control.
                await { find { it.text?.toString() in setOf("Skip", "Create local project") } != null }
                if (find { it.text?.toString() == "Skip" } != null) clickLabel("Skip")
                clickLabel("Create local project")
                var field: AccessibilityNodeInfo? = null
                await { field = find { it.isEditable }; field != null }
                val input = requireNotNull(field)
                val labels = (input.text?.toString().orEmpty() + input.contentDescription?.toString().orEmpty() + input.hintText?.toString().orEmpty())
                assertTrue("Project-name field must expose a label or hint", labels.isNotBlank())
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                clickLabel("Create", "Save")
                await { find { it.text?.toString()?.contains(name) == true } != null }
                scenario.recreate()
                await { find { it.text?.toString()?.contains(name) == true } != null }
            }
        } finally {
            runBlocking { app.repository.dao.observeProjects().first().filter { it.name == name }.forEach { app.repository.delete(it.id) } }
        }
    }
    @Test fun primaryActionRemainsReachableAtTwoHundredPercentText() {
        fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previous=shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 2.0")
            visiblePrimaryLibraryActionHasTouchTargetAndClickSemantics()
        } finally {
            if(previous.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) shell("settings put system font_scale $previous")
            else shell("settings delete system font_scale")
        }
    }
    @Test fun visiblePrimaryLibraryActionHasTouchTargetAndClickSemantics() {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { find { it.text?.toString() in setOf("Skip", "Create local project") } != null }
                if (find { it.text?.toString() == "Skip" } != null) clickLabel("Skip")
            var control: AccessibilityNodeInfo? = null
            await { control = find { n -> n.text?.toString() == "Create local project" }; control != null }
            var node = requireNotNull(control)
            while (!node.isClickable && node.parent != null) node = node.parent
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("Primary action must be enabled and clickable", node.isClickable && node.isEnabled)
            assertTrue("Primary action touch target under48dp: $bounds", bounds.width() >= minimum && bounds.height() >= minimum)
        }
    }
}
