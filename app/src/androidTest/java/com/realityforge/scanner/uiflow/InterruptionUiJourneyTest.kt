package com.realityforge.scanner.uiflow

import android.content.ContentValues
import android.app.KeyguardManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.PendingRequestState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import android.accessibilityservice.AccessibilityService

/** All mutations use visible controls/DocumentsUI. C1/repository reads only assert outcomes. */
@RunWith(AndroidJUnit4::class)
class InterruptionUiJourneyTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as RealityForgeApplication
    private val ui get()=UiJourneyDriver()
    private lateinit var model:ScannerViewModel

    @Test fun pickerCancelRecreateRepeatedImportAndChangedInputStayBoundToTheInitiatingProject() {
        val names=listOf("Other synthetic UI ${UUID.randomUUID()}","Bound synthetic UI ${UUID.randomUUID()}")
        var second:Uri?=null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val driver=ui
                driver.startAtLibrary()
                create(driver,names[0]);driver.back();driver.awaitLibrary()
                create(driver,names[1]);bind(scenario)
                val project=project(names[1]);val other=project(names[0]);val initialRevision=project.revision
                val requestIds=mutableListOf<String>()

                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                val cancelled=pending(driver,project.id,initialRevision);requestIds+=cancelled.requestId.value
                driver.back();driver.awaitReview(names[1]);awaitSettled(driver,project.id,0)
                assertEquals(initialRevision,revision(project.id));assertTrue(hashes(project.id).isEmpty())

                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                val first=pending(driver,project.id,initialRevision);requestIds+=first.requestId.value
                // Recreate the initiating Activity while real DocumentsUI still owns the foreground.
                scenario.recreate();bind(scenario);driver.awaitDocuments()
                assertEquals(first,pending(driver,project.id,initialRevision))
                driver.sharedProviderSelect("normal.jpg");driver.awaitReview(names[1]);awaitSettled(driver,project.id,1)
                val originals=hashes(project.id);val importedRevision=revision(project.id)
                val expected=instrumentation.context.assets.open("relief/view-01.jpg").use { digest(it.readBytes()) }
                assertEquals(setOf(expected),originals.values.toSet());assertEquals(initialRevision+1,importedRevision)

                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                requestIds+=pending(driver,project.id,importedRevision).requestId.value
                driver.sharedProviderSelect("normal.jpg");driver.awaitReview(names[1]);awaitSettled(driver,project.id,1)
                assertEquals("Repeated source must not duplicate publication",originals,hashes(project.id))
                assertEquals(importedRevision,revision(project.id))

                val displayName="rf-synthetic-change-${UUID.randomUUID().toString().take(8)}.jpg"
                second=syntheticDownload(displayName,"relief/view-02.jpg")
                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                requestIds+=pending(driver,project.id,importedRevision).requestId.value
                driver.documentsSelect(displayName);driver.awaitReview(names[1]);awaitSettled(driver,project.id,2)
                val changed=hashes(project.id)
                val secondHash=instrumentation.context.assets.open("relief/view-02.jpg").use { digest(it.readBytes()) }
                assertEquals(setOf(expected,secondHash),changed.values.toSet())
                originals.forEach { (id,hash)->assertEquals(hash,changed[id]) }
                assertEquals(importedRevision+1,revision(project.id))
                assertEquals(project.id,model.selectedId.value)
                assertEquals(other.revision,revision(other.id));assertTrue(hashes(other.id).isEmpty())
                assertEquals(4,requestIds.toSet().size)
                requestIds.forEach { assertEquals(it,UUID.fromString(it).toString()) }
                scenario.recreate();bind(scenario);driver.awaitReview(names[1])
                assertEquals(changed,hashes(project.id));assertEquals(project.id,model.selectedId.value)
                driver.screenshot("picker-repeat-changed-input")
                val evidence=JSONObject().put("synthetic",true).put("project_id",project.id)
                    .put("other_project_id",other.id).put("initial_revision",initialRevision)
                    .put("final_revision",revision(project.id)).put("request_ids",JSONArray(requestIds))
                    .put("original_hashes",JSONObject(changed))
                instrumentation.addResults(Bundle().apply {putString("rfInputJourneyEvidence",evidence.toString())})
            }
        } finally { second?.let { runCatching {app.contentResolver.delete(it,null,null)} };cleanup(names) }
    }

    @Test fun homeReturnRecreationAndReopenPreserveTheProjectAndOriginals() {
        val name="Background synthetic UI ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val driver=ui;driver.startAtLibrary();create(driver,name);bind(scenario)
                val before=project(name)
                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                pending(driver,before.id,before.revision)
                driver.sharedProviderSelect("normal.jpg");driver.awaitReview(name);awaitSettled(driver,before.id,1)
                val original=hashes(before.id);val revision=revision(before.id)
                driver.homeAndReturn();driver.awaitReview(name)
                assertEquals(before.id,model.selectedId.value);assertEquals(original,hashes(before.id))
                scenario.recreate();bind(scenario);driver.awaitReview(name)
                driver.back();driver.awaitLibrary();driver.openLibraryProject(name);driver.awaitReview(name)
                assertEquals(before.id,model.selectedId.value);assertEquals(revision,revision(before.id))
                assertEquals(original,hashes(before.id));assertTrue(model.scannerState.value.pendingRequests.isEmpty())
                driver.screenshot("background-return-reopen")
                instrumentation.addResults(Bundle().apply {putString("rfBackgroundJourneyEvidence",JSONObject()
                    .put("synthetic",true).put("project_id",before.id).put("revision",revision)
                    .put("original_hashes",JSONObject(original)).put("home_observed",true)
                    .put("process_death_tested",false).put("screen_lock_tested",false).toString())})
            }
        } finally {cleanup(listOf(name))}
    }

    @Test fun nonsecureScreenLockWakeAndReturnPreserveTheProjectAndOriginals() {
        val keyguard=requireNotNull(app.getSystemService(KeyguardManager::class.java))
        val power=requireNotNull(app.getSystemService(PowerManager::class.java))
        require(!keyguard.isDeviceSecure && !keyguard.isKeyguardSecure) {"Use an authorized nonsecure test fixture; no credential bypass is supported"}
        require(power.isInteractive && !keyguard.isKeyguardLocked) {"Test fixture must begin awake and unlocked"}
        val name="Lock synthetic UI ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val driver=ui;driver.startAtLibrary();create(driver,name);bind(scenario)
                val before=project(name)
                driver.scrollToClick("Import photographs","Import photos");driver.awaitDocuments()
                pending(driver,before.id,before.revision)
                driver.sharedProviderSelect("normal.jpg");driver.awaitReview(name);awaitSettled(driver,before.id,1)
                val original=hashes(before.id);val revision=revision(before.id)
                check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {"Actual lock action was refused"}
                driver.await {keyguard.isKeyguardLocked && !power.isInteractive}
                val lockedAt=SystemClock.elapsedRealtime()
                // These commands execute through the shell identity, outside the target UID.
                require(!keyguard.isDeviceSecure && !keyguard.isKeyguardSecure)
                shell("input keyevent 224");shell("wm dismiss-keyguard")
                driver.await {power.isInteractive && !keyguard.isKeyguardLocked}
                val unlockedAt=SystemClock.elapsedRealtime()
                driver.homeAndReturn();driver.awaitReview(name)
                assertEquals(before.id,model.selectedId.value);assertEquals(revision,revision(before.id))
                assertEquals(original,hashes(before.id));assertTrue(model.scannerState.value.pendingRequests.isEmpty())
                scenario.recreate();bind(scenario);driver.awaitReview(name)
                assertEquals(before.id,model.selectedId.value);assertEquals(original,hashes(before.id))
                driver.screenshot("nonsecure-lock-wake-return")
                instrumentation.addResults(Bundle().apply {putString("rfScreenLockJourneyEvidence",JSONObject()
                    .put("synthetic",true).put("project_id",before.id).put("revision",revision)
                    .put("original_hashes",JSONObject(original)).put("locked_observed",true)
                    .put("locked_at_elapsed_ms",lockedAt).put("unlocked_at_elapsed_ms",unlockedAt)
                    .put("active_job_recovery_tested",false).put("process_death_tested",false).toString())})
            }
        } finally {
            if(!keyguard.isDeviceSecure && !keyguard.isKeyguardSecure) {runCatching {shell("input keyevent 224");shell("wm dismiss-keyguard")}}
            cleanup(listOf(name))
        }
    }

    private fun bind(scenario:ActivityScenario<MainActivity>) {scenario.onActivity {model=ViewModelProvider(it)[ScannerViewModel::class.java]}}
    private fun shell(command:String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {input->
            val bytes=ByteArray(4097);val n=input.read(bytes)
            require(n<4097) {"Shell response exceeded budget"}
            val output=if(n>0) String(bytes,0,n,Charsets.UTF_8) else ""
            require(!Regex("exception|error:|unknown command",RegexOption.IGNORE_CASE).containsMatchIn(output)) {"External wake/unlock command failed: $output"}
        }
    }
    private fun create(driver:UiJourneyDriver,name:String) {driver.click("Create local project");driver.setText(name);driver.click("Create","Save");driver.awaitReview(name)}
    private fun project(name:String)=runBlocking {app.repository.dao.observeProjects().first().single {it.name==name}}
    private fun revision(id:String)=runBlocking {requireNotNull(app.repository.dao.project(id)).revision}
    private fun pending(driver:UiJourneyDriver,id:String,revision:Long):PendingRequestState {
        driver.await {model.scannerState.value.pendingRequests.size==1}
        return model.scannerState.value.pendingRequests.single().also {
            assertEquals(OperationKind.IMPORT_PHOTOS,it.kind);assertEquals(id,it.project.projectId);assertEquals(revision,it.project.revision)
        }
    }
    private fun awaitSettled(driver:UiJourneyDriver,id:String,count:Int) {driver.await(30000) {
        !model.busy.value && model.scannerState.value.pendingRequests.isEmpty() && runBlocking {app.repository.dao.photos(id).size==count}
    }}
    private fun hashes(id:String)=runBlocking {app.repository.dao.photos(id).associate {it.id to digest(app.repository.photoFile(it))}}
    private fun syntheticDownload(name:String,asset:String):Uri {
        val values=ContentValues().apply {put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"image/jpeg");put(MediaStore.Downloads.RELATIVE_PATH,"Download/RealityForgeUiFixtures");put(MediaStore.Downloads.IS_PENDING,1)}
        val uri=requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        try {
            instrumentation.context.assets.open(asset).use {input->requireNotNull(app.contentResolver.openOutputStream(uri)).use {input.copyTo(it)}}
            values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);check(app.contentResolver.update(uri,values,null,null)==1)
            return uri
        } catch(error:Throwable) {runCatching {app.contentResolver.delete(uri,null,null)};throw error}
    }
    private fun digest(file:File)=file.inputStream().use {input->val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var count=0L
        while(true) {val n=input.read(buffer);if(n<0)break;count+=n;require(count<=150L*1024*1024);md.update(buffer,0,n)}
        md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun cleanup(names:List<String>)=runBlocking {app.repository.dao.observeProjects().first().filter {it.name in names}.forEach {app.repository.delete(it.id)}}
}
