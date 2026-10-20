package com.realityforge.scanner.agents.a074

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.ui.processing.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real composable/button actions, synthetic job states. Readiness never requires animation idleness. */
@RunWith(AndroidJUnit4::class)
class ScreenInteractionTest {
    private val fixtureId = "a074-processing-fixture-${UUID.randomUUID()}"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        androidx.compose.material3.MaterialTheme {
            Box(Modifier.fillMaxSize().semantics {
                testTagsAsResourceId = true
                contentDescription = fixtureId
            }.testTag(fixtureId)) { content() }
        }
    }

    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && result.size < 512) {
            val (node, depth) = queue.removeFirst()
            result.add(node)
            if (depth < 32) for (i in 0 until minOf(node.childCount, 64)) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return result
    }

    private fun fixtureNodes(): List<AccessibilityNodeInfo> {
        val marker = descendants(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
            it.viewIdResourceName == fixtureId || it.contentDescription?.toString() == fixtureId
        } ?: return emptyList()
        return descendants(marker)
    }

    private fun await(description: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val structure = descendants(instrumentation.uiAutomation.rootInActiveWindow).take(32).joinToString("\n") {
            "package=${it.packageName} class=${it.className} id=${it.viewIdResourceName} enabled=${it.isEnabled}"
        }
        error("Timed out waiting for $description in the unique processing fixture; structural tree:\n$structure")
    }

    private fun node(text: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("semantic label $text") {
            found = fixtureNodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            found != null
        }
        return requireNotNull(found)
    }

    private fun buttonOrNull(text: String): AccessibilityNodeInfo? {
        var value = fixtureNodes().firstOrNull { it.text?.toString() == text } ?: return null
        repeat(32) {
            if (value.className?.toString() == "android.widget.Button" ||
                value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return value
            if (value.viewIdResourceName == fixtureId || value.contentDescription?.toString() == fixtureId) return null
            value = value.parent ?: return null
        }
        return null
    }

    private fun click(text: String) {
        var target: AccessibilityNodeInfo? = null
        await("enabled $text button") { target = buttonOrNull(text); target?.isEnabled == true }
        assertTrue("Actual accessibility click was rejected", requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun remainsPending(calls: AtomicInteger, expectedCalls: Int) {
        val end = SystemClock.elapsedRealtime() + 1000
        do {
            assertEquals("Repeated accessibility action must not dispatch", expectedCalls, calls.get())
            assertFalse("Accepted/pending mutation must keep the actual button disabled", requireNotNull(buttonOrNull("Pause")).isEnabled)
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
    }

    @Test fun realControlsDispatchBoundActions() {
        val actions = java.util.concurrent.CopyOnWriteArrayList<JobAction>()
        val identity = JobIdentity("p", 4, "job-synthetic")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                Fixture { ProcessingScreen(JobUiState(identity, JobPhase.RUNNING), { actions.add(it) }, Modifier.fillMaxSize()) }
            } }
            node("The remaining amount of work is unknown.")
            click("Pause")
            await("one actual action callback") { actions.size == 1 }
            assertEquals(JobAction(identity, JobCommand.PAUSE), actions.single())
        }
    }

    @Test fun actualScreenBlocksRepeatedDispatchUntilAcceptedIdentityChanges() {
        val calls = AtomicInteger()
        val returned = AtomicInteger()
        val completion = CompletableDeferred<Boolean>()
        val identity = JobIdentity("p", 4, "job-synthetic", 1, 2)
        val visible = mutableStateOf(JobUiState(identity, JobPhase.RUNNING))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    Fixture { ProcessingScreen(visible.value, {
                        calls.incrementAndGet()
                        completion.await().also { returned.incrementAndGet() }
                    }, Modifier.fillMaxSize()) }
                } }
                click("Pause")
                await("one suspended actual command") { calls.get() == 1 }
                await("disabled pending button") { buttonOrNull("Pause")?.isEnabled == false }
                // Exercise the disabled semantic action once; callback count must remain unchanged.
                requireNotNull(buttonOrNull("Pause")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                remainsPending(calls, 1)
                completion.complete(true)
                await("actual admission callback return") { returned.get() == 1 }
                remainsPending(calls, 1)
                scenario.onActivity { visible.value = visible.value.copy(identity = identity.copy(stateVersion = 3)) }
                await("new persisted version enables the actual button") { buttonOrNull("Pause")?.isEnabled == true }
                click("Pause")
                await("exactly one action for the new version") { calls.get() == 2 && returned.get() == 2 }
                remainsPending(calls, 2)
            }
        } finally { completion.cancel() }
    }
}
