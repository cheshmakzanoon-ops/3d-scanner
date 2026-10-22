package com.realityforge.scanner.agents.a072

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
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
import com.realityforge.scanner.ui.library.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID

/** Android accessibility-tree interactions with the real composable; fixture states are synthetic. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun activeNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
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

    private fun tagged(node: AccessibilityNodeInfo, tag: String): Boolean =
        node.viewIdResourceName == tag || node.viewIdResourceName?.endsWith("/$tag") == true

    /** No node text, field values or arbitrary descriptions are written to the failure log. */
    private fun failWithTree(message: String): Nothing {
        val context = instrumentation.targetContext
        val knownLabels = setOf(context.getString(R.string.a072_search), context.getString(R.string.a072_name))
        fun describe(node: AccessibilityNodeInfo): String {
            fun safe(value: CharSequence?): String = value?.toString()
                ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(120) ?: "<none>"
            val description = node.contentDescription?.toString()
            val label = when {
                description == null -> "<none>"
                description in knownLabels -> safe(description)
                else -> "<redacted>"
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return "package=${safe(node.packageName)} class=${safe(node.className)} id=${safe(node.viewIdResourceName)} " +
                "bounds=$bounds visible=${node.isVisibleToUser} editable=${node.isEditable} " +
                "enabled=${node.isEnabled} clickable=${node.isClickable} description=$label"
        }
        val tree = runCatching {
            val nodes = activeNodes()
            buildString {
                // Parent chains distinguish an editable child from a separately labelled wrapper.
                nodes.filter { it.isEditable }.take(4).forEachIndexed { index, node ->
                    append("editable[$index] parent chain:\n")
                    var current: AccessibilityNodeInfo? = node
                    repeat(6) {
                        val actual = current ?: return@repeat
                        append(describe(actual)).append('\n')
                        current = actual.parent
                    }
                }
                append("active window structure:\n")
                nodes.forEach { append(describe(it)).append('\n') }
            }.let { if (it.length > 6000) it.take(5980) + "\n<tree truncated>" else it }
        }.getOrElse { "<tree unavailable: ${it.javaClass.simpleName}>" }
        Log.e("A072Interaction", message)
        tree.chunked(2000).forEachIndexed { index, part -> Log.e("A072Interaction", "tree[$index]=$part") }
        throw AssertionError("$message\nBounded actual accessibility structure (field values omitted):\n$tree")
    }

    private fun awaitNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            find()?.let { return it }
            SystemClock.sleep(100)
        }
        failWithTree(message)
    }

    private fun awaitFixture(tag: String) {
        awaitNode("Replacement synthetic fixture did not become visible: $tag") {
            activeNodes().firstOrNull { it.isVisibleToUser && tagged(it, tag) }
        }
    }

    // Compose exports a merging text field's description as a virtual child.
    // Only inspect that editable owner's subtree, never an unrelated screen label.
    private fun exposesLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.contentDescription?.toString() == label || node.hintText?.toString() == label) return true
        return (0 until minOf(node.childCount, 32)).any { index ->
            node.getChild(index)?.let { child ->
                !child.isEditable && (child.contentDescription?.toString() == label || child.text?.toString() == label)
            } == true
        }
    }

    private fun editableNode(label: String): AccessibilityNodeInfo =
        awaitNode("No visible editable node exposes the required localized description: $label") {
            activeNodes().firstOrNull {
                it.isVisibleToUser && it.isEditable && exposesLabel(it, label)
            }
        }

    private fun node(text: String, scrollFixture: String? = null): AccessibilityNodeInfo {
        var scrolls = 0
        var lastScroll = 0L
        return awaitNode("Visible semantic node missing in synthetic fixture=$scrollFixture (bounded real scrolling when requested)") {
            val nodes = activeNodes()
            val visible = nodes.filter { it.isVisibleToUser }
            val found = visible.firstOrNull { it.text?.toString() == text }
                ?: visible.firstOrNull { it.text?.contains(text) == true }
            if (found == null && scrollFixture != null && scrolls < 8 &&
                SystemClock.elapsedRealtime() - lastScroll >= 750) {
                val list = visible.firstOrNull { tagged(it, scrollFixture) }
                if (list != null && list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                    if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolls = 8 else scrolls++
                    lastScroll = SystemClock.elapsedRealtime()
                }
            }
            found
        }
    }

    private fun click(text: String, scrollFixture: String? = null) {
        var target: AccessibilityNodeInfo? = node(text, scrollFixture)
        while (target != null && !target.isClickable) target = target.parent
        if (target?.isEnabled != true || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            failWithTree("Real synthetic-fixture control lacks an enabled accessibility click action")
        }
    }

    @Test fun editableFieldsExposeLocalizedNamesAndKeepEditActions() {
        val context = instrumentation.targetContext
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fixture = "a072-fixture-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    LibraryScreen(LibraryUiState(emptyList()), {},
                        modifier = Modifier.testTag(fixture).semantics { testTagsAsResourceId = true },
                        onQueryChange = { query, _, _ -> queries.add(query) })
                }
            } }
            // Android queue idleness alone does not prove the old Activity composition was replaced.
            awaitFixture(fixture)
            val searchLabel = context.getString(R.string.a072_search)
            val search = editableNode(searchLabel)
            assertTrue(exposesLabel(search, searchLabel))
            val searchInput = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic search fixture")
            }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, searchInput))
            val end = SystemClock.elapsedRealtime() + 10000
            while (!queries.contains("Synthetic search fixture") && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            if (!queries.contains("Synthetic search fixture")) failWithTree("Actual search text action did not reach the fixture callback")
            click(context.getString(R.string.a072_create))
            val nameLabel = context.getString(R.string.a072_name)
            val name = editableNode(nameLabel)
            assertTrue(exposesLabel(name, nameLabel))
            val nameInput = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic project fixture")
            }
            assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, nameInput))
        }
    }

    @Test fun realControlsDispatchBoundActions() {
        val context = instrumentation.targetContext
        val actions = java.util.concurrent.CopyOnWriteArrayList<LibraryAction>()
        val project = LibraryProject("immutable-project", "Synthetic library fixture", 1, 7, "READY", "Add photos", false)
        val fixture = "a072-fixture-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    LibraryScreen(LibraryUiState(listOf(project)), actions::add,
                        modifier = Modifier.testTag(fixture).semantics { testTagsAsResourceId = true })
                }
            } }
            awaitFixture(fixture)
            node(project.name, fixture)
            val delete = context.getString(R.string.a072_delete)
            click(delete, fixture)
            assertTrue(actions.isEmpty())
            node(context.getString(R.string.a072_delete_title, project.name))
            click(delete)
            val end = SystemClock.elapsedRealtime() + 10000
            while (actions.isEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            assertEquals(LibraryAction.Delete("immutable-project", 7), actions.single())
        }
    }
}
