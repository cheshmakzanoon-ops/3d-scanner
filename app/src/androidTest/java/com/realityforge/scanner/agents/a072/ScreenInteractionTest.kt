package com.realityforge.scanner.agents.a072

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.realityforge.scanner.ui.library.*

/** Android accessibility-tree interactions with the real composable; fixture states are synthetic. */
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private fun editableNode(): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 256) {
                val current = pending.removeFirst()
                if (current.isEditable && current.isVisibleToUser) return current
                for (index in 0 until minOf(current.childCount, 32)) {
                    if (pending.size >= 256) break
                    current.getChild(index)?.let(pending::add)
                }
            }
            SystemClock.sleep(100)
        }
        error("Visible editable accessibility node missing")
    }

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
    @Test fun editableFieldsExposeLocalizedNamesAndKeepEditActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    LibraryScreen(LibraryUiState(emptyList()), {}, onQueryChange = { query, _, _ -> queries.add(query) })
                }
            } }
            val search = editableNode()
            assertEquals(context.getString(R.string.a072_search), search.contentDescription?.toString())
            val searchInput = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic search fixture")
            }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, searchInput))
            instrumentation.waitForIdleSync()
            assertTrue(queries.contains("Synthetic search fixture"))
            click(context.getString(R.string.a072_create))
            val name = editableNode()
            assertEquals(context.getString(R.string.a072_name), name.contentDescription?.toString())
            val nameInput = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic project fixture")
            }
            assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, nameInput))
        }
    }

    @Test fun realControlsDispatchBoundActions() {
        val actions=java.util.concurrent.CopyOnWriteArrayList<LibraryAction>()
        val project=LibraryProject("immutable-project","Synthetic library fixture",1,7,"READY","Add photos",false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { LibraryScreen(LibraryUiState(listOf(project)),actions::add) } } }
            node("Synthetic library fixture")
            click("Delete")
            assertTrue(actions.isEmpty())
            node("Delete Synthetic library fixture?")
            click("Delete")
            assertEquals(LibraryAction.Delete("immutable-project",7),actions.single())
        }
    }
}
