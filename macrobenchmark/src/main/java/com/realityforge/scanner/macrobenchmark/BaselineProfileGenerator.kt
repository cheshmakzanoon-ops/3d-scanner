package com.realityforge.scanner.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generates a proposed profile only. report.compare must approve measured benefit before retention. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun collectRealStartupAndViewer() = profile.collect(
        packageName = "com.realityforge.scanner", includeInStartupProfile = true
    ) {
        val ui = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        startActivityAndWait()
        check(ui.wait(Until.hasObject(By.text("Create local project")),10000))
        for (fixture in listOf("small","large")) {
            startActivityAndWait(Intent().apply {
                component = ComponentName("com.realityforge.scanner", "com.realityforge.scanner.bench97.BenchmarkViewerActivity")
                putExtra("fixture",fixture)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            val viewer = checkNotNull(ui.wait(Until.findObject(By.desc(
                "Photographic 3D model. Drag to rotate and pinch to zoom.")),15000))
            val b = viewer.visibleBounds
            ui.swipe(b.centerX(),b.centerY(),b.centerX()+b.width()/4,b.centerY(),20)
            viewer.pinchOpen(0.5f,1000);viewer.pinchClose(0.5f,1000)
            checkNotNull(ui.findObject(By.text("Reset view"))).click()
            pressHome()
        }
    }
}
