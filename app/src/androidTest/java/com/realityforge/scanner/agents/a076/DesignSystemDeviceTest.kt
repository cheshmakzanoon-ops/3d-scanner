package com.realityforge.scanner.agents.a076

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.ui.designsystem.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignSystemDeviceTest {
    @Test fun largeTextLabelAndToggleShareOneOperableAction() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val checked=mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,2f)) {
                    AppTheme { ScreenFrame { LabelledToggle("Toggle verification",checked.value,{checked.value=it}) } }
                }
            } }
            instrumentation.waitForIdleSync()
            val root=instrumentation.uiAutomation.rootInActiveWindow
            val nodes=root.findAccessibilityNodeInfosByText("Toggle verification")
            assertEquals(1,nodes.size)
            assertTrue(nodes.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue(checked.value)
        }
    }
}
