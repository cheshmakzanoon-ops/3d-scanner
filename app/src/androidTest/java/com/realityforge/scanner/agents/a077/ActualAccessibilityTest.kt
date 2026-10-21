package com.realityforge.scanner.agents.a077

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.UUID

/** Real application accessibility tree/action checks. Does not claim a human TalkBack review. */
@RunWith(AndroidJUnit4::class)
class ActualAccessibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val runId = UUID.randomUUID().toString()

    @Before fun fixtureIsInteractiveAndUnlocked() {
        val context = instrumentation.targetContext
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!interactive || keyguard == null || keyguard.isKeyguardLocked || keyguard.isDeviceLocked) {
            failWithDiagnostics(
                "Device fixture must be interactive and unlocked before running accessibility assertions. " +
                    "Prepare a nonsecure fixture with adb shell input keyevent KEYCODE_WAKEUP and " +
                    "adb shell wm dismiss-keyguard. A secure lock requires the fixture operator. " +
                    "The test does not dismiss locks or system dialogs."
            )
        }
    }

    /** Structural diagnostics exclude node text, field values, descriptions and hints. */
    private fun boundedTree(root: AccessibilityNodeInfo?): String {
        if (root == null) return "<no active accessibility root>"
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to 0)
        val tree = StringBuilder()
        var visited = 0
        var truncated = false
        fun safe(value: CharSequence?): String = value?.toString()
            ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(160) ?: "<none>"
        while (pending.isNotEmpty() && visited < 256 && tree.length < 6000) {
            val (node, depth) = pending.removeFirst()
            visited++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val line = "${" ".repeat(depth)}package=${safe(node.packageName)} " +
                "class=${safe(node.className)} id=${safe(node.viewIdResourceName)} " +
                "bounds=$bounds visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                "clickable=${node.isClickable} editable=${node.isEditable} " +
                "focused=${node.isFocused} accessibilityFocused=${node.isAccessibilityFocused}\n"
            tree.append(line.take(6000 - tree.length))
            if (depth < 32) {
                if (node.childCount > 32) truncated = true
                for (index in 0 until minOf(node.childCount, 32)) {
                    if (pending.size + visited >= 256) {
                        truncated = true
                        break
                    }
                    node.getChild(index)?.let { pending.add(it to depth + 1) }
                }
            } else if (node.childCount > 0) {
                truncated = true
            }
        }
        if (truncated || pending.isNotEmpty() || tree.length >= 6000) {
            return tree.toString().take(5980) + "\n<tree truncated>"
        }
        return tree.toString()
    }

    /** Capture the real active window before ActivityScenario closes it on assertion failure. */
    private fun failWithDiagnostics(reason: String): Nothing {
        val automation = instrumentation.uiAutomation
        val root = runCatching { automation.rootInActiveWindow }.getOrNull()
        val activePackage = root?.packageName?.toString()
            ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(160) ?: "<none>"
        val tree = runCatching { boundedTree(root) }
            .getOrElse { "<tree unavailable: ${it.javaClass.simpleName}>" }
        val screenshot = runCatching {
            val cache = instrumentation.targetContext.cacheDir.canonicalFile
            val directory = File(cache, "a077")
            check(directory.isDirectory || directory.mkdir()) { "Cannot create diagnostic cache directory" }
            check(directory.canonicalFile.parentFile == cache) { "Diagnostic cache escaped test scope" }
            val file = File(directory, "$runId.png")
            check(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid diagnostic filename" }
            val bitmap = checkNotNull(automation.takeScreenshot()) { "Screenshot unavailable" }
            try {
                try {
                    FileOutputStream(file).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG compression failed" }
                    }
                } catch (failure: Exception) {
                    file.delete()
                    throw failure
                }
                file.absolutePath
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { "<unavailable: ${it.javaClass.simpleName}>" }
        val header = "$reason\nrun=$runId activePackage=$activePackage screenshot=$screenshot"
        Log.e("A077Accessibility", header)
        tree.chunked(2000).forEachIndexed { index, part ->
            Log.e("A077Accessibility", "run=$runId tree[$index]=$part")
        }
        throw AssertionError("$header\nBounded active accessibility tree (no text/field values):\n$tree")
    }

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
        failWithDiagnostics("Expected actual accessibility control did not become available within ${timeout}ms")
    }

    /** Feature resources are optional in a standalone 077 checkout, present in the joined app. */
    private fun labels(resourceName: String, vararg standaloneLabels: String): List<String> {
        val context = instrumentation.targetContext
        val id = context.resources.getIdentifier(resourceName, "string", context.packageName)
        return if (id != 0) listOf(context.getString(id)) else standaloneLabels.toList()
    }

    private fun named(node: AccessibilityNodeInfo, labels: List<String>): Boolean =
        node.isVisibleToUser && labels.any {
            node.text?.toString() == it || node.contentDescription?.toString() == it
        }

    private fun guideSkip(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        if (!node.isVisibleToUser) return false
        val id = node.viewIdResourceName
        return id == "a075-skip" || id?.endsWith("/a075-skip") == true || named(node, labels)
    }

    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        return target
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        val target = clickTarget(node)
        assertTrue("Control lacks accessibility click action", target.isClickable && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickLabel(vararg labels: String) {
        val expected = labels.toList()
        var node: AccessibilityNodeInfo? = null
        await {
            node = find { named(it, expected) && clickTarget(it).let { target -> target.isClickable && target.isEnabled } }
            node != null
        }
        clickNode(requireNotNull(node))
    }

    /** Never scroll an unknown route or the library to make its primary-action assertion pass. */
    private fun guideScroller(current: List<AccessibilityNodeInfo>, titles: List<String>): AccessibilityNodeInfo? {
        val appPackage = instrumentation.targetContext.packageName
        fun forward(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser &&
            node.packageName?.toString() == appPackage &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        val route = current.firstOrNull {
            val id = it.viewIdResourceName
            it.isVisibleToUser && it.packageName?.toString() == appPackage &&
                (id == "a071-route-onboarding" || id?.endsWith("/a071-route-onboarding") == true)
        }
        if (route != null) return nodes(route).firstOrNull(::forward)
        var ancestor = current.firstOrNull {
            it.packageName?.toString() == appPackage && named(it, titles)
        }
        repeat(12) {
            val candidate = ancestor ?: return null
            if (forward(candidate)) return candidate
            ancestor = candidate.parent
        }
        return null
    }

    private fun reachLibrary() {
        val skipLabels = labels("a075_skip", "Skip")
        val createLabels = labels("a072_create", "Create local project")
        val guideTitles = labels("a075_title")
        var scrolls = 0
        var lastScroll = 0L
        var skip: AccessibilityNodeInfo? = null
        await {
            val current = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            skip = current.firstOrNull { guideSkip(it, skipLabels) }
            val ready = skip != null || current.any { named(it, createLabels) }
            if (!ready && scrolls < 8 && SystemClock.elapsedRealtime() - lastScroll >= 750) {
                guideScroller(current, guideTitles)?.let { guide ->
                    scrolls++
                    val accepted = guide.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    lastScroll = SystemClock.elapsedRealtime()
                    Log.i("A077Accessibility", "run=$runId guideScrollAttempt=$scrolls accepted=$accepted")
                }
            }
            ready
        }
        skip?.let(::clickNode)
        await { find { named(it, createLabels) } != null }
    }
    @Test fun realCreateDialogHasNamedEditableFieldAndReturnsToCorrectProject() {
        val name = "Accessibility ${UUID.randomUUID()}"
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                reachLibrary()
                clickLabel(*labels("a072_create", "Create local project").toTypedArray())
                val nameLabels = labels("a072_name")
                var field: AccessibilityNodeInfo? = null
                await {
                    field = find { node -> node.isVisibleToUser && node.isEditable &&
                        (nameLabels.isEmpty() || nameLabels.any {
                            node.contentDescription?.toString() == it || node.hintText?.toString() == it
                        }) }
                    field != null
                }
                val input = requireNotNull(field)
                val labels = (input.text?.toString().orEmpty() + input.contentDescription?.toString().orEmpty() + input.hintText?.toString().orEmpty())
                assertTrue("Project-name field must expose a label or hint", labels.isNotBlank())
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                clickLabel(*labels("a072_save", "Create", "Save").toTypedArray())
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
            reachLibrary()
            val createLabels = labels("a072_create", "Create local project")
            var control: AccessibilityNodeInfo? = null
            await { control = find { named(it, createLabels) }; control != null }
            var node = requireNotNull(control)
            while (!node.isClickable && node.parent != null) node = node.parent
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("Primary action must be enabled and clickable", node.isClickable && node.isEnabled)
            assertTrue("Primary action touch target under48dp: $bounds", bounds.width() >= minimum && bounds.height() >= minimum)
        }
    }
}
