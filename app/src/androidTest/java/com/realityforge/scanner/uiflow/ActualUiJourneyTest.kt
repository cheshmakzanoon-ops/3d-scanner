package com.realityforge.scanner.uiflow

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Key operations use real UI controls and Android DocumentsUI, never direct ViewModel actions. */
@RunWith(AndroidJUnit4::class)
class ActualUiJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RealityForgeApplication
    private val driver get() = UiJourneyDriver()

    @Test fun createRotateReopenAndCancelDestructiveActionThroughVisibleControls() {
        val name = "UI journey ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = driver
                ui.startAtLibrary()
                ui.click("Create local project")
                ui.setText(name)
                ui.click("Create", "Save")
                ui.awaitText(name)
                scenario.recreate()
                ui.awaitText(name)
                ui.screenshot("create-reopen")
                // Back is an actual accessibility global action, not selecting a project in the controller.
                ui.back()
                ui.click(name)
                ui.awaitText(name)
                ui.back()
                if (ui.hasLabel("Search projects")) ui.setText(name, "Search projects") else ui.click(name)
                ui.scrollToClick("Delete this local project", "Delete project", "Delete")
                ui.awaitText("originals", exact = false)
                ui.click("Cancel")
                ui.awaitText(name)
                runBlocking {
                    val project = app.repository.dao.observeProjects().first().single { it.name == name }
                    assertNotNull(app.repository.dao.project(project.id))
                }
            }
        } finally { cleanup(name) }
    }

    @Test fun syntheticPhotographsImportReconstructInspectExportAndReopenThroughUi() {
        val name = "Synthetic UI ${UUID.randomUUID()}"
        val media = mutableListOf<Uri>()
        val displayNames = mutableListOf<String>()
        var exportedName: String? = null
        try {
            val fixture = instrumentation.context.assets.open("relief/calibration.json").bufferedReader().use { JSONObject(it.readText()) }
            assertTrue("Fixtures are synthetic and cannot qualify physical capture", fixture.getBoolean("synthetic"))
            val batch = UUID.randomUUID().toString().take(8)
            for (n in 1..3) {
                val displayName = "rf-synthetic-$batch-$n.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/RealityForgeUiFixtures")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                media += uri; displayNames += displayName
                instrumentation.context.assets.open("relief/view-0$n.jpg").use { input -> requireNotNull(app.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(app.contentResolver.update(uri, values, null, null) == 1)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = driver
                ui.startAtLibrary()
                ui.click("Create local project"); ui.setText(name); ui.click("Create", "Save"); ui.awaitText(name)
                // Import three times to avoid relying on vendor-specific multiple-selection gestures.
                for (displayName in displayNames) {
                    ui.scrollToClick("Import photographs", "Import photos")
                    ui.documentsSelect(displayName)
                    ui.awaitText(name)
                    ui.await(30000) { runBlocking {
                        val project = app.repository.dao.observeProjects().first().singleOrNull { it.name == name }
                        project != null && app.repository.dao.photos(project.id).size >= displayNames.indexOf(displayName)+1
                    } }
                }
                val project = runBlocking { app.repository.dao.observeProjects().first().single { it.name == name } }
                val original = runBlocking { app.repository.dao.photos(project.id).associate { it.id to sha(app.repository.photoFile(it)) } }
                assertEquals(3, original.size)
                ui.scrollUntilEditable("Fallback 35 mm")
                ui.setText("32.88", "Fallback")
                ui.scrollToClick("Reconstruct selected photographs", "Reconstruct points + surface", "Reconstruct")
                ui.await(240000) { runBlocking { app.repository.dao.project(project.id)?.let { it.resultRevision == it.revision && it.resultKey != null } == true } }
                ui.screenshot("synthetic-reconstruction")
                ui.optionalClick("Inspect completed result", "Inspect available results")
                ui.scrollToClick("Inspect photo-textured model", "Open photographic viewer")
                ui.awaitText("Photographic", exact = false)
                ui.screenshot("synthetic-photographic-viewer")
                ui.click("Zoom in")
                ui.click("Orbit left", "Rotate left")
                ui.click("Reset view", "Fit model", "Reset")
                ui.click("Close")
                ui.optionalClick("Export available results")
                ui.scrollToClick("Textured GLB", "Photographic surface · GLB", "Photographic surface")
                val exportName = "rf-ui-export-$batch.glb"
                exportedName = exportName
                ui.documentsSave(exportName)
                ui.awaitText(name, timeout = 30000)
                scenario.recreate()
                ui.awaitText(name)
                runBlocking {
                    val completed = requireNotNull(app.repository.dao.project(project.id))
                    assertEquals(project.revision, completed.resultRevision)
                    val source = app.repository.artifact(project.id, requireNotNull(completed.resultKey), "textured.glb")
                    assertEquals(sha(source), ui.downloadSha(exportName))
                    for (photo in app.repository.dao.photos(project.id)) assertEquals(original[photo.id], sha(app.repository.photoFile(photo)))
                }
                ui.screenshot("synthetic-export-reopen")
            }
        } finally { media.forEach { runCatching { app.contentResolver.delete(it,null,null) } }; exportedName?.let { runCatching { driver.removeDownload(it) } }; cleanup(name) }
    }
    private fun cleanup(name: String) = runBlocking {
        app.repository.dao.observeProjects().first().filter { it.name == name }.forEach { app.repository.delete(it.id) }
    }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val md=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer); if(n<0) break; md.update(buffer,0,n) }; md.digest().joinToString(""){"%02x".format(it)}
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}

internal class UiJourneyDriver {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if(root==null) return emptyList(); val result=mutableListOf(root)
        for(i in 0 until root.childCount) result+=nodes(root.getChild(i)); return result
    }
    fun await(timeout:Long=20000, check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) { if(check()) return; SystemClock.sleep(150) }
        screenshot("failure"); throw AssertionError("Actual UI condition exceeded ${timeout}ms; tree=${tree().take(6000)}")
    }
    private fun tree()=nodes(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n") { "${it.viewIdResourceName}: ${it.text} / ${it.contentDescription}" }
    private fun find(vararg labels:String,exact:Boolean=true)=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { n ->
        labels.any { value -> listOf(n.text?.toString(),n.contentDescription?.toString(),n.viewIdResourceName).any { it!=null && if(exact) it==value else it.contains(value,true) } }
    }
    fun hasLabel(vararg labels:String):Boolean = find(*labels) != null
    fun awaitText(label:String,exact:Boolean=true,timeout:Long=20000) = await(timeout) { find(label,exact=exact)!=null }
    private fun clickNode(value:AccessibilityNodeInfo):Boolean {
        var n=value; while(!n.isClickable && n.parent!=null) n=n.parent
        return n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    fun click(vararg labels:String) { var n:AccessibilityNodeInfo?=null; await { n=find(*labels); n!=null }; check(clickNode(requireNotNull(n))) { "Control lacks enabled click action: ${labels.toList()}" } }
    fun startAtLibrary() {
        await { find("Skip", "Create local project") != null }
        optionalClick("Skip")
        awaitText("Create local project")
    }
    fun optionalClick(vararg labels:String):Boolean = find(*labels)?.let { clickNode(it) } ?: false
    fun setText(value:String,labelContains:String?=null) {
        var field:AccessibilityNodeInfo?=null
        await { field=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isEditable && (labelContains==null || "${it.text} ${it.hintText} ${it.contentDescription}".contains(labelContains,true)) }; field!=null }
        val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }
        check(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))
    }
    fun back() { check(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) }
    private fun scroll():Boolean = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    fun scrollToClick(vararg labels:String) {
        repeat(35) { if(optionalClick(*labels)) return; if(!scroll()) SystemClock.sleep(150); SystemClock.sleep(100) }
        click(*labels)
    }
    fun scrollUntilEditable(label:String) {
        repeat(35) { if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.isEditable && "${it.text} ${it.hintText} ${it.contentDescription}".contains(label,true) }) return; scroll(); SystemClock.sleep(100) }
        error("Editable control missing: $label")
    }
    fun documentsSelect(displayName:String) {
        await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
        if(!optionalClick(displayName)) {
            optionalClick("Show roots", "Open navigation drawer")
            optionalClick("Downloads")
            optionalClick("RealityForgeUiFixtures")
            scrollToClick(displayName)
        }
        optionalClick("Select", "Open")
    }
    fun documentsSave(displayName:String) {
        await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
        setText(displayName)
        optionalClick("Show roots", "Open navigation drawer"); optionalClick("Downloads")
        click("Save", "SAVE")
    }
    fun downloadSha(name:String):String {
        require(name.matches(Regex("rf-ui-export-[a-f0-9]{8}\\.glb")))
        val md=MessageDigest.getInstance("SHA-256")
        var count=0L
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")).use { input ->
            val buffer=ByteArray(65536)
            while(true) {val n=input.read(buffer);if(n<0)break;count+=n;require(count<=64L*1024*1024);md.update(buffer,0,n)}
        }
        require(count>12) { "Exported document is missing or truncated" }
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    fun removeDownload(name:String) {
        require(name.matches(Regex("rf-ui-export-[a-f0-9]{8}\\.glb")))
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("rm -f /sdcard/Download/$name")).use { it.readBytes() }
    }
    fun screenshot(label:String) {
        // Capture outside app data so evidence survives AGP uninstall. No shell operators are used.
        val safe=label.replace(Regex("[^a-z0-9-]"),"-")
        val runId=InstrumentationRegistry.getArguments().getString("rfEvidenceRunId")?.also { require(it.matches(Regex("[a-f0-9]{32}"))) } ?: "standalone"
        val pfd=instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/rf-ui-$runId-$safe.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
