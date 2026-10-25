package com.realityforge.scanner.uiflow

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
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

    @Test fun documentRowLabelsMatchExactNamesWithoutSelectingPreviewOrPrefixCollisions() {
        assertTrue(documentLabelMatches("normal.jpg","normal.jpg",null))
        assertTrue(documentLabelMatches("normal.jpg",null,"normal.jpg, -1 B, Dec 31, 1969"))
        assertFalse(documentLabelMatches("normal.jpg",null,"Preview the file normal.jpg"))
        assertFalse(documentLabelMatches("normal.jpg",null,"normal.jpg.bak, 1 kB, today"))
        assertFalse(documentLabelMatches("normal.jpg",null,"other-normal.jpg, 1 kB, today"))
    }

    @Test fun dialogAndSearchTextCannotSatisfyCompletedProjectNavigation() {
        val name="UI route barrier ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val ui=driver
                ui.startAtLibrary();ui.click("Create local project");ui.setText(name)
                ui.awaitText(name)
                assertFalse("Dialog input is not a project review",ui.isReviewProject(name))
                ui.click("Save");ui.awaitReview(name)
                runBlocking { assertEquals(1,app.repository.dao.observeProjects().first().count { it.name==name }) }
                ui.back();ui.awaitLibrary()
                ui.setSoleLabeledText(name,"a072_search","Search projects")
                ui.awaitText(name)
                assertFalse("Library search is not a project review",ui.isReviewProject(name))
                ui.openLibraryProject(name)
            }
        } finally { cleanup(name) }
    }

    @Test fun createRotateReopenAndCancelDestructiveActionThroughVisibleControls() {
        val name = "UI journey ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = driver
                ui.startAtLibrary()
                ui.click("Create local project")
                ui.setText(name)
                ui.click("Create", "Save")
                ui.awaitReview(name)
                scenario.recreate()
                ui.awaitReview(name)
                ui.screenshot("create-reopen")
                // Back is an actual accessibility global action, not selecting a project in the controller.
                ui.back()
                ui.openLibraryProject(name)
                ui.awaitReview(name)
                ui.back()
                ui.awaitLibrary()
                ui.setSoleLabeledText(name,"a072_search","Search projects")
                ui.scrollToClick("Delete this local project", "Delete project", "Delete")
                ui.awaitText("originals", exact = false)
                ui.click("Cancel")
                ui.awaitLibrary()
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
                lateinit var model:com.realityforge.scanner.ScannerViewModel
                scenario.onActivity {model=androidx.lifecycle.ViewModelProvider(it)[com.realityforge.scanner.ScannerViewModel::class.java]}
                ui.startAtLibrary()
                ui.click("Create local project"); ui.setText(name); ui.click("Create", "Save"); ui.awaitReview(name)
                // Import three times to avoid relying on vendor-specific multiple-selection gestures.
                for (displayName in displayNames) {
                    ui.scrollToClick("Import photographs", "Import photos")
                    ui.documentsSelect(displayName)
                    ui.awaitReview(name)
                    ui.await(30000) { runBlocking {
                        val project = app.repository.dao.observeProjects().first().singleOrNull { it.name == name }
                        project != null && app.repository.dao.photos(project.id).size >= displayNames.indexOf(displayName)+1
                    } }
                }
                val project = runBlocking { app.repository.dao.observeProjects().first().single { it.name == name } }
                val original = runBlocking { app.repository.dao.photos(project.id).associate { it.id to sha(app.repository.photoFile(it)) } }
                assertEquals(3, original.size)
                ui.setSoleLabeledText("32.88", "a073_focal", "Fallback 35 mm-equivalent focal length")
                ui.scrollToClick("Reconstruct selected photographs", "Reconstruct points + surface", "Reconstruct")
                ui.await(240000) { runBlocking { app.repository.dao.project(project.id)?.let { it.resultRevision == it.revision && it.resultKey != null } == true } }
                ui.screenshot("synthetic-reconstruction")
                ui.scrollToClick("Open previous completed result", "Inspect completed result", "Inspect available results")
                ui.scrollToClick("Inspect photo-textured model", "Open photographic viewer")
                ui.awaitText("Photographic", exact = false)
                ui.screenshot("synthetic-photographic-viewer")
                ui.click("Zoom in")
                ui.click("Orbit left", "Rotate left")
                ui.click("Fit and reset", "Reset view", "Fit model", "Reset")
                ui.click("Close")
                ui.scrollToClick("Export available results")
                ui.scrollToClickPrefix(ui.exportOptionPrefix("a071_photographic", "Photographic surface", "GLB"), "Textured GLB", "Photographic surface · GLB")
                val exportName = "rf-ui-export-$batch.glb"
                exportedName = exportName
                ui.documentsSave(exportName)
                ui.awaitAppWindow()
                ui.await(30000) {!model.busy.value && model.scannerState.value.pendingRequests.isEmpty()}
                // Current071 keeps the export catalog open after picker completion; return through its real Back action.
                ui.back()
                ui.awaitReview(name, timeout = 30000)
                scenario.recreate()
                ui.awaitReview(name)
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
    @Test fun sharedFaultProviderImportUsesTheActualPickerAndPreservesOriginals() {
        val name="Shared provider UI ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui=driver
                ui.startAtLibrary();ui.click("Create local project");ui.setText(name);ui.click("Create","Save");ui.awaitReview(name)
                lateinit var model:com.realityforge.scanner.ScannerViewModel
                scenario.onActivity { model=androidx.lifecycle.ViewModelProvider(it)[com.realityforge.scanner.ScannerViewModel::class.java] }
                ui.scrollToClick("Import photographs","Import photos");ui.sharedProviderSelect("normal.jpg")
                ui.await(30000) { !model.busy.value && runBlocking {
                    val project=app.repository.dao.observeProjects().first().singleOrNull { it.name==name }
                    project!=null && app.repository.dao.photos(project.id).size==1
                } }
                val project=runBlocking { app.repository.dao.observeProjects().first().single {it.name==name} }
                val before=runBlocking { app.repository.dao.photos(project.id).associate {it.id to sha(app.repository.photoFile(it))} }
                val expected=instrumentation.context.assets.open("relief/view-01.jpg").use { sha(it.readBytes()) }
                assertEquals(setOf(expected),before.values.toSet())
                for(mode in listOf("truncated.jpg","revoked.jpg")) {
                    val previousNotice=model.notice.value
                    ui.scrollToClick("Import photographs","Import photos");ui.sharedProviderSelect(mode)
                    ui.await(30000) { !model.busy.value && model.notice.value!=previousNotice }
                    runBlocking { assertEquals(before,app.repository.dao.photos(project.id).associate {it.id to sha(app.repository.photoFile(it))}) }
                }
                ui.screenshot("shared-provider-original-preservation")
            }
        } finally { cleanup(name) }
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
        if(InstrumentationRegistry.getArguments().getString("rfPhysicalCapture")!="true") screenshot("failure")
        throw AssertionError("Actual UI condition exceeded ${timeout}ms; tree=${tree().take(16000)}")
    }
    private fun tree()=nodes(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n") {
        val bounds=android.graphics.Rect().also(it::getBoundsInScreen)
        "${it.viewIdResourceName}: ${it.text} / ${it.contentDescription} class=${it.className} " +
            "visible=${it.isVisibleToUser} enabled=${it.isEnabled} clickable=${it.isClickable} editable=${it.isEditable} bounds=$bounds"
    }
    private fun selectorLabels(value:String):List<String> {
        val resources=when(value) {
            "Skip","Skip guide"->listOf("a075_skip")
            "Create local project"->listOf("a072_create")
            "Create","Save"->listOf("a072_save")
            "Search projects"->listOf("a072_search")
            "Import photographs","Import photos"->listOf("a073_import")
            "Use camera","Capture"->listOf("a073_capture","a071_camera")
            "Reconstruct selected photographs","Reconstruct points + surface","Reconstruct"->listOf("a073_start")
            "Inspect completed result"->listOf("a073_result")
            "Inspect available results","Open previous completed result"->listOf("a074_previous","a073_result")
            "Inspect photo-textured model","Open photographic viewer"->listOf("a071_open_photographic")
            "Photographic"->listOf("a070_photo")
            "Zoom in"->listOf("a070_zoom_in")
            "Orbit left","Rotate left"->listOf("a070_orbit_left")
            "Fit and reset","Reset view","Fit model","Reset"->listOf("a070_fit")
            "Close"->listOf("a070_close")
            "Export available results"->listOf("a071_open_export")
            "Delete this local project","Delete project","Delete"->listOf("a072_delete","a071_delete")
            "originals"->listOf("a072_delete_body","a071_delete_body")
            "Cancel"->listOf("a072_cancel","a071_cancel","a073_cancel")
            "Back"->listOf("a071_back")
            else->emptyList()
        }
        val tags=resources.mapNotNull {when(it) {
            "a075_skip"->"a075-skip";"a072_create"->"a072-create";"a072_save"->"a072-save"
            "a073_import"->"a073-import";"a073_capture"->"a073-camera";"a073_start"->"a073-reconstruct"
            "a073_result"->"a073-result";"a071_open_photographic"->"a071-open-photographic"
            "a071_open_export"->"a071-open-export";"a071_back"->"a071-back";else->null
        }}
        val systemIds=when(value) {
            "Don’t allow","Don't allow","Deny"->listOf("permission_deny_button")
            "While using the app","While using this app"->listOf("permission_allow_foreground_only_button")
            "Only this time"->listOf("permission_allow_one_time_button")
            "Allow"->listOf("permission_allow_button")
            else->emptyList()
        }
        val translated=resources.mapNotNull {name->
            val context=instrumentation.targetContext
            val id=context.resources.getIdentifier(name,"string",context.packageName)
            if(id==0) null else context.getString(id)
        }
        val fallback=when(value) {"Skip"->listOf("Skip guide");"Reset view","Fit model","Reset"->listOf("Fit and reset");else->emptyList()}
        return listOf(value)+translated+tags+systemIds+fallback
    }
    private fun find(vararg labels:String,exact:Boolean=true):AccessibilityNodeInfo? {
        val candidates=labels.flatMap(::selectorLabels).distinct()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { n -> n.isVisibleToUser &&
            candidates.any { value -> n.viewIdResourceName?.substringAfterLast('/')==value ||
                listOf(n.text?.toString(),n.contentDescription?.toString()).any {it!=null && if(exact) it==value else it.contains(value,true)} }
        }
    }
    fun hasLabel(vararg labels:String):Boolean = find(*labels) != null
    fun awaitText(label:String,exact:Boolean=true,timeout:Long=20000) = await(timeout) { find(label,exact=exact)!=null }
    /** A name in an editable dialog/search field is not a completed navigation. */
    fun awaitReview(name:String,timeout:Long=20000) = await(timeout) { isReviewProject(name) }
    fun isReviewProject(name:String):Boolean {
        val root=instrumentation.uiAutomation.rootInActiveWindow
        val all=nodes(root)
        return root?.packageName?.toString()==instrumentation.targetContext.packageName &&
            all.none { it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/') in setOf("a072-name","a072-save") } &&
            all.any { it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/')=="a071-route-review" } &&
            // Scaffold's TopAppBar title is outside the tagged route content subtree.
            all.any { it.isVisibleToUser && it.text?.toString()==name && isOutsideEditableOrDialog(it) }
    }
    private fun isOutsideEditableOrDialog(node:AccessibilityNodeInfo):Boolean {
        var ancestor:AccessibilityNodeInfo?=node
        while(ancestor!=null) {
            if(ancestor.isEditable || ancestor.className?.toString()?.endsWith("Dialog")==true ||
                ancestor.viewIdResourceName?.substringAfterLast('/') in setOf("a072-name","a072-save")) return false
            ancestor=ancestor.parent
        }
        return true
    }
    fun awaitLibrary() = await {
        val root=instrumentation.uiAutomation.rootInActiveWindow
        root?.packageName?.toString()==instrumentation.targetContext.packageName &&
            nodes(root).any { it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/')=="a071-route-library" } &&
            nodes(root).none { it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/')=="a072-save" }
    }
    fun openLibraryProject(name:String) {
        awaitLibrary()
        await {
            val route=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.viewIdResourceName?.substringAfterLast('/')=="a071-route-library"
            } ?: return@await false
            val cards=nodes(route).filter { it.isVisibleToUser && !it.isEditable && it.text?.toString()==name }
                .mapNotNull { clickableWithin(it,route) }.distinct()
            check(cards.size<=1) { "Ambiguous project card: $name" }
            if(cards.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) true
            else { nodes(route).firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        awaitReview(name)
    }
    /** Never climb outside the owning list/route to click an unrelated container. */
    private fun clickableWithin(node:AccessibilityNodeInfo,boundary:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var candidate:AccessibilityNodeInfo?=node
        while(candidate!=null && candidate!=boundary) {
            if(candidate.isEditable) return null
            if(candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable) return candidate
            candidate=candidate.parent
        }
        return null
    }
    private fun clickNode(value:AccessibilityNodeInfo):Boolean {
        var n=value; while(!n.isClickable && n.parent!=null) n=n.parent
        return n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    fun click(vararg labels:String) = awaitEnabledClick(*labels)
    fun awaitEnabledClick(vararg labels:String, timeout:Long=20000) {
        var control:AccessibilityNodeInfo?=null
        await(timeout) {
            var candidate=find(*labels)
            while(candidate!=null && !candidate.isClickable && candidate.parent!=null) candidate=candidate.parent
            control=candidate
            candidate?.let { it.isClickable && it.isEnabled }==true
        }
        check(clickNode(requireNotNull(control))) { "Enabled control rejected click: ${labels.toList()}" }
    }
    fun startAtLibrary() {
        var guideScrolls=0
        var skipped=false
        val guideTitle=localizedString("a075_title","Photographs into observed 3D")
        await(30000) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root?.packageName?.toString()!=instrumentation.targetContext.packageName) false
            else if(find("Create local project")!=null) true
            else {
                val skip=find("a075-skip","Skip guide","Skip")
                if(skip!=null && !skipped) {check(clickNode(skip));skipped=true}
                else if(!skipped) {
                    val current=nodes(root)
                    val route=current.firstOrNull {it.viewIdResourceName?.substringAfterLast('/')=="a071-route-onboarding"}
                    val title=current.firstOrNull {it.text?.toString()==guideTitle || it.contentDescription?.toString()==guideTitle}
                    // Scroll only the positively identified guide in the active app window.
                    if(route!=null || title!=null) {
                        check(guideScrolls<24) {"Known capture guide did not expose its Skip control after bounded scrolling"}
                        var scroller=route?.let {nodes(it).firstOrNull {node->node.isScrollable}}
                        var parent=title?.parent
                        while(scroller==null && parent!=null) {if(parent.isScrollable) scroller=parent else parent=parent.parent}
                        scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        guideScrolls++
                    }
                }
                false
            }
        }
    }
    fun optionalClick(vararg labels:String):Boolean = find(*labels)?.let { clickNode(it) } ?: false
    fun setText(value:String,labelContains:String?=null) {
        val labels=labelContains?.let(::selectorLabels)
            ?: listOf(localizedString("a072_name", "Project name"))
        fun field(): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            val editable = nodes(root).filter { it.isEditable && it.isEnabled && it.isVisibleToUser }
            // Do not type into the library's Search while the create dialog is opening.
            // The DocumentsUI save dialog has its own independently owned filename field.
            if (labelContains == null && root.packageName?.toString()?.contains("documentsui") == true)
                return editable.singleOrNull()
            return editable.singleOrNull { candidate ->
                (labelContains == null && candidate.viewIdResourceName?.substringAfterLast('/') == "a072-name") ||
                    nodes(candidate).any { labelNode -> labels.any { label ->
                        listOf(labelNode.text, labelNode.hintText, labelNode.contentDescription)
                            .any { it?.toString()?.contains(label, true) == true }
                    } }
            }
        }
        val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }
        // A disappearing accessibility node may reject an action; reacquire it before retrying.
        await { field()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args) == true }
        await { field()?.text?.toString() == value }
    }
    private fun localizedString(name:String,fallback:String):String {
        val context=instrumentation.targetContext
        val id=context.resources.getIdentifier(name,"string",context.packageName)
        return if(id!=0) context.getString(id) else fallback
    }
    fun exportOptionPrefix(representationResource:String,fallback:String,format:String):String {
        val context=instrumentation.targetContext
        val label=localizedString(representationResource,fallback)
        val id=context.resources.getIdentifier("a071_export_option","string",context.packageName)
        if(id==0) return "$label · $format · "
        val marker="RF_UI_BYTE_COUNT"
        val rendered=context.getString(id,label,format,marker)
        require(rendered.contains(marker)) {"Export option resource lacks byte-count field"}
        return rendered.substringBefore(marker).also {require(it.contains(label) && it.contains(format))}
    }
    /** Compose may expose a floating label as a separate node. Require its actual localized text and one editable. */
    fun setSoleLabeledText(value:String,labelResource:String,fallback:String) {
        val label=localizedString(labelResource,fallback)
        repeat(35) {
            val visible=nodes(instrumentation.uiAutomation.rootInActiveWindow).filter {it.isVisibleToUser}
            val labelPresent=visible.any {it.text?.toString()==label || it.contentDescription?.toString()==label || it.hintText?.toString()==label}
            val fields=visible.filter {it.isEditable && it.isEnabled}
            if(labelPresent && fields.isNotEmpty()) {
                check(fields.size==1) {"Visible label has multiple editable candidates: $label"}
                val args=Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}
                check(fields.single().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))
                return
            }
            scroll();SystemClock.sleep(100)
        }
        error("Visible localized label and sole editable control missing: $label")
    }
    fun back() { check(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) }
    fun awaitDocuments() = await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
    fun awaitAppWindow() = await {instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==instrumentation.targetContext.packageName}
    /** Real HOME event and task relaunch; this is neither process death nor a lock event. */
    fun homeAndReturn() {
        check(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        val appContext=instrumentation.targetContext
        await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it!=appContext.packageName && !it.contains("documentsui") }==true }
        val intent=requireNotNull(appContext.packageManager.getLaunchIntentForPackage(appContext.packageName))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        appContext.startActivity(intent)
        await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==appContext.packageName }
    }
    private fun scroll():Boolean = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    fun scrollToClick(vararg labels:String) {
        repeat(35) { if(optionalClick(*labels)) return; if(!scroll()) SystemClock.sleep(150); SystemClock.sleep(100) }
        click(*labels)
    }
    fun scrollToClickPrefix(prefix:String,vararg legacyExact:String) {
        require(prefix.isNotBlank())
        repeat(35) {
            val match=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {node->node.isVisibleToUser &&
                listOfNotNull(node.text?.toString(),node.contentDescription?.toString()).any {it.startsWith(prefix) || it in legacyExact}}
            if(match!=null && clickNode(match)) return
            scroll();SystemClock.sleep(100)
        }
        error("Visible export option prefix missing: $prefix")
    }
    fun scrollUntilEditable(label:String) {
        repeat(35) { if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.isEditable && "${it.text} ${it.hintText} ${it.contentDescription}".contains(label,true) }) return; scroll(); SystemClock.sleep(100) }
        error("Editable control missing: $label")
    }
    private fun documentList():AccessibilityNodeInfo? {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if(root.packageName?.toString()?.contains("documentsui")!=true) return null
        return nodes(root).singleOrNull { it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/')=="dir_list" }
    }
    private fun documentRow(displayName:String):AccessibilityNodeInfo? {
        val list=documentList() ?: return null
        val rows=nodes(list).filter { node -> node.isVisibleToUser &&
            documentLabelMatches(displayName,node.text?.toString(),node.contentDescription?.toString())
        }.mapNotNull { documentItemWithin(it,list) }.distinct()
        check(rows.size<=1) { "Ambiguous DocumentsUI row: $displayName" }
        return rows.singleOrNull()
    }
    /** DocumentsUI can dispatch grid touches at its list while every item reports isClickable=false. */
    private fun documentItemWithin(node:AccessibilityNodeInfo,list:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var item=node
        while(item!=list) {
            if(item.isEditable || !item.isVisibleToUser || !item.isEnabled) return null
            val parent=item.parent ?: return null
            if(parent==list) return item
            item=parent
        }
        return null
    }
    private fun clickDocumentRow(displayName:String):Boolean {
        val row=documentRow(displayName) ?: return false
        if(row.isClickable && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val list=documentList() ?: return false
        fun bounds(node:AccessibilityNodeInfo):DocumentRowBounds {
            val rect=android.graphics.Rect().also(node::getBoundsInScreen)
            return DocumentRowBounds(rect.left,rect.top,rect.right,rect.bottom)
        }
        val controls=nodes(row).filter { it!=row && it.isVisibleToUser && it.isEnabled && it.isClickable }.map(::bounds)
        val point=documentRowTapPoint(bounds(row),bounds(list),controls) ?: return false
        val downTime=SystemClock.uptimeMillis()
        fun send(action:Int):Boolean {
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,point.first,point.second,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            return try {instrumentation.uiAutomation.injectInputEvent(event,true)} finally {event.recycle()}
        }
        if(!send(MotionEvent.ACTION_DOWN)) return false
        var released=false
        return try { released=send(MotionEvent.ACTION_UP); released }
        finally { if(!released) runCatching {send(MotionEvent.ACTION_CANCEL)} }
    }
    private fun selectDocumentRow(displayName:String) = await {
        if(clickDocumentRow(displayName)) true
        else { documentList()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
    }
    private fun openDocumentsRoot(label:String) {
        click("Show roots","Open navigation drawer")
        await {
            val roots=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/')=="roots_list"
            } ?: return@await false
            val targets=nodes(roots).filter { it.isVisibleToUser && it.text?.toString()==label }
                .mapNotNull { clickableWithin(it,roots) }.distinct()
            check(targets.size<=1) { "Ambiguous DocumentsUI root: $label" }
            targets.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        }
        awaitDocumentDirectory(label)
    }
    private fun awaitDocumentDirectory(label:String) = await {
        val all=nodes(instrumentation.uiAutomation.rootInActiveWindow)
        documentList()!=null && all.any { node -> node.isVisibleToUser &&
            ((node.viewIdResourceName?.substringAfterLast('/')=="breadcrumb_text" && node.text?.toString()==label) ||
                (node.viewIdResourceName?.substringAfterLast('/')=="toolbar" &&
                    nodes(node).any { it.isVisibleToUser && it.text?.toString()==label }))
        }
    }
    private fun finishDocumentSelection() {
        var confirmed=false
        await {
            val packageName=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if(packageName==instrumentation.targetContext.packageName) true
            else {
                if(!confirmed && packageName?.contains("documentsui")==true) confirmed=optionalClick("Select","Open")
                false
            }
        }
    }
    fun documentsSelect(displayName:String) {
        awaitDocuments()
        if(!clickDocumentRow(displayName)) {
            openDocumentsRoot("Downloads")
            if(!clickDocumentRow(displayName)) {
                selectDocumentRow("RealityForgeUiFixtures")
                awaitDocumentDirectory("RealityForgeUiFixtures")
                selectDocumentRow(displayName)
            }
        }
        finishDocumentSelection()
    }
    fun sharedProviderSelect(displayName:String) {
        require(displayName in setOf("normal.jpg","truncated.jpg","revoked.jpg"))
        awaitDocuments()
        openDocumentsRoot("Synthetic QA faults")
        selectDocumentRow(displayName)
        finishDocumentSelection()
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

/** DocumentsUI grid rows decorate their accessible filename with size/date metadata. */
internal fun documentLabelMatches(displayName:String,text:String?,description:String?):Boolean =
    text==displayName || description==displayName || description?.startsWith("$displayName,")==true
