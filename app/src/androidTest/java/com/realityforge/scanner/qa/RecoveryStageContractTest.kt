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
    private fun providerAccess(grant: Boolean) {
        val operation = if (grant) "grant" else "revoke"
        val component = "com.realityforge.scanner.test/com.realityforge.scanner.qa.FaultDocumentProvider\$GrantReceiver"
        // UiAutomation executes argv directly; shell quotes would become literal
        // component characters. The fixed component has no whitespace.
        val command = "am broadcast --receiver-foreground -n $component --es operation $operation"
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertTrue("Explicit test-only URI $operation failed: $output", output.contains("Broadcast completed: result=-1") && output.contains("RF088_$operation"))
    }
    private fun hash(file: File) = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while(true) { val n=stream.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun boundedProviderFaultsPreserveImportedOriginals() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as RealityForgeApplication
        val repository = app.repository
        val id = repository.create("Synthetic bounded fault fixture")
        providerAccess(true)
        fun uri(mode: String) = DocumentsContract.buildDocumentUri(FaultDocumentProvider.AUTHORITY, mode)
        try {
            assertTrue(repository.importPhoto(id, uri("normal")))
            val before = repository.dao.photos(id).associate { it.id to hash(repository.photoFile(it)) }
            val revision = requireNotNull(repository.dao.project(id)).revision
            for(mode in listOf("truncated", "revoked")) {
                val failure = runCatching { repository.importPhoto(id,uri(mode)) }.exceptionOrNull()
                assertTrue("Fault $mode must remain an explicit import rejection: $failure",
                    failure is com.realityforge.scanner.data.operations.PhotoImportFailureException)
                val outcome = (failure as com.realityforge.scanner.data.operations.PhotoImportFailureException).outcome
                assertTrue(outcome is com.realityforge.scanner.data.importing.ImportPublicationResult.Rejected)
                val rejected = outcome as com.realityforge.scanner.data.importing.ImportPublicationResult.Rejected
                assertEquals(if (mode == "revoked") "SOURCE_PERMISSION_LOST" else "IMPORT_REJECTED", rejected.code)
                assertEquals(revision, rejected.revision)
                assertEquals(revision, requireNotNull(repository.dao.project(id)).revision)
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
        } finally { try { providerAccess(false) } finally { repository.delete(id) } }
    }

    /** Shared provider contract only:026 still owns real DestinationTransferService outcome assertions. */
    @Test fun configuredDestinationBytesFaultsAndCleanupAreObservable() {
        val resolver = instrumentation.targetContext.contentResolver
        val authority = Uri.parse("content://${FaultDocumentProvider.AUTHORITY}")
        val requests = mutableListOf<String>()
        fun configure(mode: String = "normal", verification: String = "readback", limit: Int = FaultDocumentProvider.MAX_BYTES,
                      deleteFails: Boolean = false, preExisting: Boolean = false, created: Boolean = true): Pair<String, Uri> {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            val config = JSONObject().put("schema", 1).put("synthetic", true).put("requestId", id)
                .put("mode", mode).put("verification", verification).put("limitBytes", limit)
                .put("deleteFails", deleteFails).put("preExisting", preExisting).put("created", created)
            val result = requireNotNull(resolver.call(authority, "qa.configure", null, android.os.Bundle().apply { putString("config", config.toString()) }))
            requests += id
            return id to DocumentsContract.buildDocumentUri(FaultDocumentProvider.AUTHORITY, requireNotNull(result.getString("documentId")))
        }
        fun snapshot(id: String) = JSONObject(requireNotNull(resolver.call(authority, "qa.snapshot", id, null)?.getString("json")))
        fun write(uri: Uri, bytes: ByteArray) { requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
        fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        fun size(uri: Uri): Long? = requireNotNull(resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
        }
        val bytes = ByteArray(256) { ((it * 17) and 255).toByte() }
        providerAccess(true)
        try {
            val (normal, normalUri) = configure()
            write(normalUri, bytes)
            assertArrayEquals(bytes, read(normalUri)); assertEquals(bytes.size.toLong(), size(normalUri))
            val stored = snapshot(normal)
            assertEquals(256, stored.getInt("storedBytes")); assertEquals(0, stored.getInt("deleteCalls"))
            assertTrue(stored.getJSONArray("events").toString().contains("openRead"))
            // A second call/client reopens durable state and exact retained bytes, without reconfiguration.
            assertEquals(stored.getString("storedSha256"), snapshot(normal).getString("storedSha256"))

            val (truncated, truncatedUri) = configure("silent-truncate", limit = 17)
            write(truncatedUri, bytes) // Deliberately succeeds at write/close while retaining fewer bytes.
            assertArrayEquals(bytes.copyOf(17), read(truncatedUri))
            assertEquals(256, snapshot(truncated).getInt("acceptedBytes")); assertEquals(17, snapshot(truncated).getInt("storedBytes"))
            assertTrue(DocumentsContract.deleteDocument(resolver, truncatedUri))
            assertEquals(1, snapshot(truncated).getInt("deleteCalls")); assertTrue(snapshot(truncated).getBoolean("deleted"))

            for (mode in listOf("enospc", "edquot")) {
                val (id, uri) = configure(mode, limit = 33)
                val failure = runCatching { write(uri, bytes) }.exceptionOrNull()
                assertNotNull("Configured quota was accepted", failure)
                val errno = if (mode == "enospc") android.system.OsConstants.ENOSPC else android.system.OsConstants.EDQUOT
                assertTrue(generateSequence(requireNotNull(failure)) { it.cause }.any { it is android.system.ErrnoException && it.errno == errno })
                assertEquals(33, snapshot(id).getInt("storedBytes"))
                assertArrayEquals(bytes.copyOf(33), read(uri))
            }
            for (verification in listOf("size-only", "none")) {
                val (_, uri) = configure(verification = verification)
                write(uri, bytes)
                assertEquals(if (verification == "size-only") 256L else null, size(uri))
                assertTrue(runCatching { read(uri) }.exceptionOrNull() is java.io.FileNotFoundException)
            }
            val (undeletable, undeletableUri) = configure(deleteFails = true)
            write(undeletableUri, bytes)
            assertFalse("Configured delete failure reported success", runCatching { DocumentsContract.deleteDocument(resolver, undeletableUri) }.getOrDefault(false))
            assertArrayEquals(bytes, read(undeletableUri)); assertEquals(1, snapshot(undeletable).getInt("deleteCalls"))
            assertFalse(snapshot(undeletable).getBoolean("deleted"))
            val (existing, existingUri) = configure(preExisting = true)
            write(existingUri, bytes)
            assertEquals(0, snapshot(existing).getInt("deleteCalls")) // Actual026 ownership policy is a separate consumer test.
            assertArrayEquals(bytes, read(existingUri))
            val (created, _) = configure(created = false)
            val parent = DocumentsContract.buildDocumentUri(FaultDocumentProvider.AUTHORITY, "request-root/$created")
            val createdUri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "synthetic.bin"))
            write(createdUri, bytes); assertArrayEquals(bytes, read(createdUri))
        } finally {
            // Release callbacks can finish asynchronously after close; never clear active descriptors.
            try {
                val failed = mutableListOf<String>()
                for (id in requests) {
                    var removed = false
                    repeat(40) {
                        if (!removed) removed = runCatching { resolver.call(authority, "qa.reset", id, null)?.getBoolean("removed") == true }.getOrDefault(false)
                        if (!removed) Thread.sleep(25)
                    }
                    if (!removed) failed += id
                }
                assertTrue("Synthetic request cleanup failed: $failed", failed.isEmpty())
            } finally { providerAccess(false) }
        }
    }

    @Test fun configuredPermissionFaultsAreDistinctFromMissingAndBounded() {
        val resolver = instrumentation.targetContext.contentResolver
        val authority = Uri.parse("content://${FaultDocumentProvider.AUTHORITY}")
        val ids = mutableListOf<String>()
        fun configure(mode: String): Pair<String, Uri> {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            val config = JSONObject().put("schema", 1).put("synthetic", true).put("requestId", id).put("mode", mode)
            requireNotNull(resolver.call(authority, "qa.configure", null, android.os.Bundle().apply { putString("config", config.toString()) }))
            ids += id
            return id to DocumentsContract.buildDocumentUri(FaultDocumentProvider.AUTHORITY, "request/$id")
        }
        providerAccess(false)
        assertTrue("Provider configuration must reject a consumer without explicit URI access", runCatching {
            resolver.call(authority, "qa.snapshot", "not_configured", null)
        }.exceptionOrNull() is SecurityException)
        providerAccess(true)
        try {
            val (_, denied) = configure("revoked")
            assertTrue(runCatching { resolver.openOutputStream(denied, "wt") }.exceptionOrNull() is SecurityException)
            val (_, missing) = configure("missing")
            assertTrue(runCatching { resolver.openOutputStream(missing, "wt") }.exceptionOrNull() is java.io.FileNotFoundException)
            assertNull("DocumentsProvider translates missing query to a null cursor", resolver.query(missing, null, null, null, null))
            val (id, readbackDenied) = configure("revoke-readback")
            requireNotNull(resolver.openOutputStream(readbackDenied, "wt")).use { it.write(byteArrayOf(1, 2, 3)) }
            assertTrue(runCatching { resolver.openInputStream(readbackDenied) }.exceptionOrNull() is SecurityException)
            val state = JSONObject(requireNotNull(resolver.call(authority, "qa.snapshot", id, null)?.getString("json")))
            assertEquals(3, state.getInt("storedBytes")); assertTrue(state.getJSONArray("events").toString().contains("openPermissionDenied"))
            val (_, revocable) = configure("normal")
            val request = DocumentsContract.getDocumentId(revocable).removePrefix("request/")
            resolver.call(authority, "qa.revoke", request, null)
            assertTrue(runCatching { resolver.openOutputStream(revocable, "wt") }.exceptionOrNull() is SecurityException)
            val oversized = JSONObject().put("schema", 1).put("synthetic", true).put("requestId", "bad_limit")
                .put("limitBytes", FaultDocumentProvider.MAX_BYTES + 1)
            assertTrue(runCatching { resolver.call(authority, "qa.configure", null, android.os.Bundle().apply { putString("config", oversized.toString()) }) }.isFailure)
            val unsafe = JSONObject().put("schema", 1).put("synthetic", true).put("requestId", "../unsafe")
            assertTrue(runCatching { resolver.call(authority, "qa.configure", null, android.os.Bundle().apply { putString("config", unsafe.toString()) }) }.isFailure)
        } finally {
            try {
                val failed = mutableListOf<String>()
                for (id in ids) {
                    var removed = false
                    repeat(40) {
                        if (!removed) removed = runCatching { resolver.call(authority, "qa.reset", id, null)?.getBoolean("removed") == true }.getOrDefault(false)
                        if (!removed) Thread.sleep(25)
                    }
                    if (!removed) failed += id
                }
                assertTrue("Synthetic request cleanup failed: $failed", failed.isEmpty())
            } finally { providerAccess(false) }
        }
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
