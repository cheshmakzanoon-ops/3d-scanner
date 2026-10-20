package com.realityforge.scanner.uiflow

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

/** Run after external088/runner permission reset, outside target process. No physical photograph is fabricated. */
@RunWith(AndroidJUnit4::class)
class CameraPermissionJourneyTest {
    @Test fun deniedThenGrantedCameraFlowKeepsTheInitiatingProjectAndOriginals() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as RealityForgeApplication
        assertEquals("Before instrumentation: adb shell pm revoke com.realityforge.scanner android.permission.CAMERA",PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        val name="Camera UI ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui=UiJourneyDriver()
                ui.startAtLibrary();ui.click("Create local project");ui.setText(name);ui.click("Create","Save");ui.awaitText(name)
                val initiating=runBlocking {app.repository.dao.observeProjects().first().single {it.name==name}}
                ui.scrollToClick("Use camera","Capture")
                ui.click("Grant camera permission","Allow camera")
                ui.click("Don’t allow","Don't allow","Deny")
                assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
                ui.screenshot("camera-denied")
                ui.click("Back")
                ui.awaitText(name)
                scenario.recreate();ui.awaitText(name)
                ui.scrollToClick("Use camera","Capture")
                ui.click("Grant camera permission","Allow camera")
                ui.click("While using the app","While using this app","Only this time")
                ui.await { ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED }
                // Permissions do not invent a captured image or change selected project revision.
                ui.click("Back");ui.awaitText(name)
                ui.screenshot("camera-regranted-project")
                runBlocking {
                    val project=app.repository.dao.observeProjects().first().single { it.name==name }
                    assertEquals(initiating.id,project.id);assertEquals(initiating.revision,project.revision)
                    assertEquals(0L,project.revision);assertTrue(app.repository.dao.photos(project.id).isEmpty())
                }
            }
        } finally { runBlocking { app.repository.dao.observeProjects().first().filter {it.name==name}.forEach {app.repository.delete(it.id)} } }
    }
}
