package com.realityforge.scanner.qa

import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RecoveryStageContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(file: File) = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while(true) { val n=stream.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun boundedProviderFaultsPreserveImportedOriginals() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        val repository = app.repository
        val id = repository.create("Synthetic bounded fault fixture")
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        fun uri(mode: String) = DocumentsContract.buildDocumentUri(FaultDocumentProvider.AUTHORITY, mode)
        try {
            assertTrue(repository.importPhoto(id, uri("normal")))
            val before = repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) }
            for(mode in listOf("truncated", "revoked")) {
                val failure = runCatching { repository.importPhoto(id,uri(mode)) }.exceptionOrNull()
                assertNotNull("Fault $mode was accepted",failure)
                assertEquals(before,repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) })
                assertFalse(repository.directory(id).walkTopDown().any { it.name.endsWith(".partial") })
            }
            for(mode in listOf("enospc", "short-write")) {
                val failure = runCatching {
                    val descriptor=requireNotNull(app.contentResolver.openFileDescriptor(uri(mode),"w"))
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(ByteArray(4096)) }
                }.exceptionOrNull()
                assertNotNull("Fault $mode did not fail the writer",failure)
                val errno=if(mode == "enospc") android.system.OsConstants.ENOSPC else android.system.OsConstants.EIO
                val causes=generateSequence(requireNotNull(failure)) { it.cause }.toList()
                assertTrue("Wrong failure for $mode: $failure",causes.any { it is android.system.ErrnoException && it.errno == errno })
            }
            val cancelled = android.os.CancellationSignal().apply { cancel() }
            assertTrue(runCatching { app.contentResolver.openFileDescriptor(uri("slow"), "r", cancelled) }.exceptionOrNull() is android.os.OperationCanceledException)
            val cancelledImport=runCatching {
                kotlinx.coroutines.withTimeout(25) { repository.importPhoto(id,uri("slow")) }
            }.exceptionOrNull()
            assertTrue("Slow import must cancel through real repository copying",cancelledImport is kotlinx.coroutines.TimeoutCancellationException)
            assertFalse(repository.directory(id).walkTopDown().any { it.name.endsWith(".partial") })
            assertEquals(before,repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) })
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity(); repository.delete(id) }
    }

    /** External controller alone enables this multi-invocation contract. No in-process kill simulation. */
    @Test fun externalProcessBoundary() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("recoveryNonce")
        assumeTrue("Requires tools/device/failure_recovery/controller.py", nonce != null)
        require(requireNotNull(nonce).matches(Regex("[0-9a-f]{32}")))
        val stage = requireNotNull(args.getString("recoveryStage"))
        require(stage in setOf("import", "registration", "checkpoint", "mesh-texture", "publication"))
        val phase = requireNotNull(args.getString("recoveryPhase"))
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        val repository = app.repository
        val state = File(app.filesDir,"qa-recovery/$nonce.json").apply { parentFile?.mkdirs() }
        suspend fun pipeline(id: String, stop: Boolean): JSONObject {
            val token = NativeCore.beginReconstruction()
            try { return SparsePipeline(repository).run(id,32.88,token) { progress ->
                val match = when(stage) {
                    "registration" -> progress.stage == "Triangulating and refining sparse geometry" && progress.completed == 0
                    "mesh-texture" -> progress.stage == "Estimating stereo depth, meshing and projecting photographs" && progress.completed == 0
                    "checkpoint" -> progress.stage == "Estimating stereo depth, meshing and projecting photographs" && progress.completed == 1
                    else -> false
                }
                if(stop && match) hold(state,stage)
            } } finally { NativeCore.finishReconstruction(token) }
        }
        if(phase == "prepare") {
            check(!state.exists()) { "Refuse to overwrite an existing recovery session" }
            val id = repository.create("Synthetic external recovery $nonce")
            for(n in 1..3) {
                val file = File(app.cacheDir,"qa-$nonce-$n.jpg")
                try {
                    instrumentation.context.assets.open("relief/view-0$n.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
                    assertTrue(repository.importPhoto(id,Uri.fromFile(file)))
                } finally { file.delete() }
            }
            val metadata = instrumentation.context.assets.open("relief/calibration.json").bufferedReader().use { JSONObject(it.readText()) }
            assertTrue(metadata.getBoolean("synthetic"))
            val k=metadata.getJSONArray("cameras").getJSONObject(0).getJSONArray("K")
            val profile=LensProfile(metadata.getInt("width"),metadata.getInt("height"),k.getJSONArray(0).getDouble(0),k.getJSONArray(1).getDouble(1),k.getJSONArray(0).getDouble(2),k.getJSONArray(1).getDouble(2),0.0,0.0,0.0,0.0,0.0,0.0,10,null)
            repository.writeAtomic(LensCalibration.file(repository,id),LensCalibration.json(profile))
            val project = requireNotNull(repository.dao.project(id))
            val originals=JSONArray();repository.dao.photos(id).forEach { originals.put(JSONObject().put("id",it.id).put("sha256",hash(repository.photoFile(it)))) }
            repository.writeAtomic(state,JSONObject().put("schema",1).put("nonce",nonce).put("project",id).put("revision",project.revision).put("originals",originals).put("preparePid",Process.myPid()).toString())
            repository.dao.state(id,project.revision,"PROCESSING","External recovery fixture")
            if(stage == "import") hold(state,stage)
            val result=pipeline(id,true)
            if(stage == "publication") hold(state,stage)
            error("Requested stage was never reached: $stage / ${result.optString("denseFailure")}")
        } else {
            val saved = JSONObject(state.readText())
            val id=saved.getString("project")
            if(phase == "cleanup") { repository.delete(id); state.delete(); return@runBlocking }
            require(phase == "verify")
            assertNotEquals("Verification must cross a real process boundary",saved.getInt("preparePid"),Process.myPid())
            assertEquals(stage,saved.getString("readyStage"))
            val project=requireNotNull(repository.dao.project(id))
            assertEquals(saved.getLong("revision"),project.revision)
            assertNull("Interrupted work became current",repository.currentPreview(id))
            val original=saved.getJSONArray("originals")
            val before=(0 until original.length()).associate { original.getJSONObject(it).getString("id") to original.getJSONObject(it).getString("sha256") }
            assertEquals(before,repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) })
            val checkpointRoot=File(repository.directory(id),".depth-checkpoints-m18")
            val cached=checkpointRoot.walkTopDown().filter { it.isFile && it.name.matches(Regex("ref-[0-7]\\.bin")) }.toList()
            val stored=saved.optJSONObject("checkpointHashes") ?: JSONObject()
            cached.forEach { assertEquals("Checkpoint changed across process death",stored.getString(it.relativeTo(checkpointRoot).path),hash(it)) }
            if(stage == "checkpoint") {
                assertTrue("Need multiple actual reference caches for selective-reuse case",cached.size>=2)
                // Only this nonce's disposable project; corrupt one reference without filling storage.
                FileOutputStream(cached.first()).use { it.write(byteArrayOf(1,2,3));it.fd.sync() }
            }
            repository.dao.recoverInterrupted()
            val result=pipeline(id,false)
            if(stage == "checkpoint") {
                val dense=result.getJSONObject("densePreview")
                assertTrue("Valid references were not selectively reused",dense.getInt("depthCheckpointResumed")>=cached.size-1)
                assertEquals("Only corrupted reference should recompute",1,dense.getInt("depthCheckpointWritten"))
            }
            val key=result.getString("artifactKey")
            assertEquals(1,repository.dao.complete(id,project.revision,key,"Recovered external-process fixture"))
            assertEquals(key,requireNotNull(repository.currentPreview(id)).getString("artifactKey"))
            assertEquals(before,repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) })
            val receipt=JSONObject().put("nonce",nonce).put("status","PASS").put("stage",stage).put("preparePid",saved.getInt("preparePid")).put("verifyPid",Process.myPid()).put("revision",project.revision).put("artifactKey",key).put("synthetic",true)
            instrumentation.addResults(android.os.Bundle().apply { putString("recoveryReceipt",receipt.toString()) })
        }
    }
    private fun hold(state: File, stage: String): Nothing {
        val saved=JSONObject(state.readText()).put("readyStage",stage)
        val app=instrumentation.targetContext.applicationContext as RealityForgeApplication
        val checkpointRoot=File(app.repository.directory(saved.getString("project")),".depth-checkpoints-m18")
        val checkpoints=JSONObject()
        checkpointRoot.walkTopDown().filter { it.isFile && it.name.matches(Regex("ref-[0-7]\\.bin")) }.forEach { checkpoints.put(it.relativeTo(checkpointRoot).path,hash(it)) }
        saved.put("checkpointHashes",checkpoints)
        FileOutputStream(state).use { it.write(saved.toString().toByteArray());it.fd.sync() }
        val until=android.os.SystemClock.elapsedRealtime()+240000
        while(android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(100)
        error("External controller failed to kill within bounded hold")
    }
}
