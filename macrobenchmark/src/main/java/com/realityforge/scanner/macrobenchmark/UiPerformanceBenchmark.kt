package com.realityforge.scanner.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical qualification only. Results use fixed labelled synthetic data and actual production viewer. */
@RunWith(AndroidJUnit4::class)
class UiPerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val packageName = "com.realityforge.scanner"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = UiDevice.getInstance(instrumentation)
    private fun viewerIntent(fixture: String) = Intent().apply {
        component = ComponentName(packageName, "$packageName.bench97.BenchmarkViewerActivity")
        putExtra("fixture", fixture)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    private fun ready() {
        check(ui.wait(Until.hasObject(By.desc("Photographic 3D model. Drag to rotate and pinch to zoom.")), 15000)) {
            "Actual photographic viewer did not appear"
        }
        check(!ui.hasObject(By.textContains("Preview unavailable")))
    }
    private fun colorsVisible() {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val colors = BooleanArray(4)
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val value = bitmap.getPixel(x,y)
                val r = value shr 16 and 255; val g = value shr 8 and 255; val b = value and 255
                if (r > 240 && g < 15 && b < 15) colors[0] = true
                if (g > 240 && r < 15 && b < 15) colors[1] = true
                if (b > 240 && r < 15 && g < 15) colors[2] = true
                if (r > 240 && g > 240 && b < 15) colors[3] = true
            }
            bitmap.recycle()
            if (colors.all { it }) return
            SystemClock.sleep(50)
        }
        error("Synthetic photographic quadrant correctness gate failed")
    }
    @Test fun coldLaunch() = benchmark.measureRepeated(packageName, listOf(StartupTimingMetric()),
        iterations = 10, startupMode = StartupMode.COLD, setupBlock = { pressHome() }) {
        startActivityAndWait()
        check(ui.wait(Until.hasObject(By.text("Create local project")),10000))
    }
    @Test fun warmLaunch() = benchmark.measureRepeated(packageName, listOf(StartupTimingMetric()),
        iterations = 10, startupMode = StartupMode.WARM, setupBlock = { pressHome() }) {
        startActivityAndWait()
    }
    @Test fun libraryNavigation() = benchmark.measureRepeated(packageName, listOf(FrameTimingMetric()),
        iterations = 10, setupBlock = { startActivityAndWait() }) {
        val create = checkNotNull(ui.wait(Until.findObject(By.text("Create local project")),10000))
        create.click()
        check(ui.wait(Until.hasObject(By.text("New scan")),5000))
        checkNotNull(ui.findObject(By.text("Cancel"))).click()
        check(ui.wait(Until.hasObject(By.text("Create local project")),5000))
    }
    private fun reopen(fixture: String) = benchmark.measureRepeated(packageName,
        listOf(StartupTimingMetric(), FrameTimingMetric()), iterations = 10,
        startupMode = StartupMode.COLD, setupBlock = { pressHome() }) {
        startActivityAndWait(viewerIntent(fixture)); ready(); colorsVisible()
    }
    @Test fun reopenSmallAtlasUpload() = reopen("small")
    @Test fun reopenLargeAtlasUpload() = reopen("large")
    @Test fun orbitZoom() = benchmark.measureRepeated(packageName, listOf(FrameTimingMetric()),
        iterations = 10, setupBlock = { startActivityAndWait(viewerIntent("large")); ready(); colorsVisible() }) {
        val model = checkNotNull(ui.findObject(By.desc("Photographic 3D model. Drag to rotate and pinch to zoom.")))
        val bounds = model.visibleBounds
        repeat(4) { ui.swipe(bounds.centerX(),bounds.centerY(),bounds.centerX()+bounds.width()/4,bounds.centerY()+bounds.height()/8,20) }
        model.pinchOpen(50,30);model.pinchClose(50,30)
        checkNotNull(ui.findObject(By.text("Reset view"))).click(); colorsVisible()
    }
    @Test fun viewerLifecycle() = benchmark.measureRepeated(packageName, listOf(FrameTimingMetric()),
        iterations = 10, setupBlock = { startActivityAndWait(viewerIntent("large")); ready(); colorsVisible() }) {
        try {
            ui.setOrientationLeft();ready();colorsVisible()
            ui.setOrientationNatural();ready();colorsVisible()
            pressHome();startActivityAndWait(viewerIntent("large"));ready();colorsVisible()
            checkNotNull(ui.findObject(By.text("Close"))).click()
            startActivityAndWait(viewerIntent("large"));ready();colorsVisible()
        } finally { ui.unfreezeRotation() }
    }
}
