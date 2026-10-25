package com.realityforge.scanner.hostintegration

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import androidx.room.Room
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.importing.*
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.jobs.ReconstructionJobState as DurableJobState
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.publication.JournaledOriginalPublicationPort
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.engine.*
import com.realityforge.scanner.engine.pipeline.*
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import com.realityforge.scanner.export.RepositoryExportSource
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.work.execution.ReconstructionExecutionController
import hostadapter.OpaqueBitmapBridge
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.annotation.*

/** Closed HOST experiment using actual controller and native geometry;
 * OS capacity is MODELED_HOST_RESOURCES, Bitmap/Mat interop uses the reviewed opaque bridge.
 * Licensed original #10/#11/#12 photos are a flash-lit partial arc, not full-object coverage.
 * Focal input is an estimated 88 mm equivalent, not measured calibration or metric scale.
 * No preflight cameras/poses/features are supplied to production.
 * No installed foreground service, SAF/UI/GLES, physical quality or performance qualification.
 */
@RunWith(SparsePrefixRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE, shadows = [OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RealLicensedThreeCompleteControllerHostTest {
    @Test(timeout = 120_000) fun fixedThreeLicensedPhotosCompletePublishReopenAndExportOrFail() = runBlocking {
        val work = async(Dispatchers.IO) { completeJourney() }
        // Same initial work deadline as the prefix experiment. Drive notification Main dispatch.
        try {
            withTimeout(90_000) {
                while (!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
                work.await()
            }
        } finally {
            withContext(NonCancellable) {
                work.cancel()
                withTimeout(10_000) {
                    while (!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
                    work.join()
                }
            }
        }
    }

    private suspend fun completeJourney() {
        CandidateNativeLoaderTokenHostTest().actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries()
        assertSame(javaClass.classLoader, OpaqueBitmapBridge::class.java.classLoader)
        assertSame(javaClass.classLoader, org.opencv.core.Mat::class.java.classLoader)
        OpaqueBitmapBridge.reset()
        val fixture = Fixture()
        val evidence = File(requireNotNull(System.getProperty("rf.eight.evidenceDir")))
        require(evidence.isDirectory && evidence.list()!!.isEmpty())
        var controller: ReconstructionExecutionController? = null
        var primary: Throwable? = null
        var stage = "INITIALIZING"
        var jobId: JobId? = null
        var partialDiagnosticStatus: String? = null
        var partialDiagnosticStage: String? = null
        try {
            fixture.initialize()
            fixture.captureIdentity(evidence)
            fixture.captureRuntime(evidence, "after-bitmap-room")
            modelResources(fixture.context, evidence)
            val actual = ReconstructionExecutionController(fixture.context, fixture.database, fixture.repository)
            controller = actual
            actual.awaitRecovery()
            val revision = requireNotNull(fixture.database.dao().project(fixture.project)).revision
            assertEquals(3L, revision)
            assertFalse(LensCalibration.file(fixture.repository, fixture.project).exists())
            assertNull(LensCalibration.readRecord(fixture.repository, fixture.project))
            val queued = actual.enqueue(fixture.project, revision, UUID.randomUUID().toString(), 88.0)
            jobId = queued.jobId
            // Production enqueue selects/persists its own tier from the disclosed modeled OS readings.
            captureImmutable(evidence, "job-settings.json", queued.input.settingsJson.toByteArray())
            stage = "CONTROLLER_RUNNING"
            actual.start(onUpdate = {}, onIdle = {})
            val finished = actual.jobs.observe(queued.jobId).filterNotNull().first {
                it.state.isTerminal || it.state == DurableJobState.PAUSED
            }
            // Join the actual production worker; no native-only diagnostic replay is admitted.
            actual.awaitWorkerStopped()
            assertEquals("Bound controller job: ${finished.outcomeCode}", DurableJobState.COMPLETED, finished.state)
            assertEquals(fixture.project, finished.input.projectId)
            assertEquals(revision, finished.input.projectRevision)
            actual.awaitWorkerStopped()
            assertCompletePersistedPairGraph(fixture, finished)
            stage = "VALIDATING_PUBLISHED_RESULT"
            // Run the unchanged strict assertion first, before writing diagnostic records.
            try {
                assertStrictPreview(fixture, requireNotNull(fixture.repository.currentPreview(fixture.project)))
            } catch (original: AssertionError) {
                preserveStrictFailure(original) {
                    try {
                        partialDiagnosticStage = "ELIGIBILITY"
                        assertEligiblePartialPreview(fixture, requireNotNull(fixture.repository.currentPreview(fixture.project)))
                        stage = "DIAGNOSING_NOT_FULL_THREE_ROUNDTRIP"
                        partialDiagnosticStage = "EXPORT_BEFORE"
                        val partialBefore = assertPublishedAndExport(fixture, evidence, "before-reopen", finished, partialDiagnostic = true)
                        fixture.assertOriginals()
                        partialDiagnosticStage = "REOPEN"
                        stopController(actual); controller = null
                        fixture.reopen()
                        fixture.captureRuntime(evidence, "after-room-reopen")
                        partialDiagnosticStage = "EXPORT_AFTER"
                        assertEquals(partialBefore, assertPublishedAndExport(fixture, evidence, "after-reopen", finished, partialDiagnostic = true))
                        fixture.assertOriginals()
                        partialDiagnosticStage = "VERIFY_REOPEN"
                        for (suffix in listOf("preview.json", "publication.json", "artifacts.json", "exports.json")) {
                            require(boundedBytes(File(evidence, "before-reopen-$suffix")).contentEquals(
                                boundedBytes(File(evidence, "after-reopen-$suffix"))))
                        }
                        val reopenedJob = requireNotNull(RoomJobStore(fixture.database, fixture.database.jobDao()).snapshot(queued.jobId))
                        assertEquals(finished.jobId, reopenedJob.jobId); assertEquals(finished.input, reopenedJob.input)
                        assertEquals(finished.attempt, reopenedJob.attempt)
                        assertEquals(DurableJobState.COMPLETED, reopenedJob.state)
                        assertEquals(ReconstructionStage.PUBLISHING, reopenedJob.cursor.acceptedStage)
                        val marker = JSONObject().put("schema", 1).put("classification", "NOT_FULL_THREE")
                            .put("profile", "real-licensed-three-complete").put("requiredCameraCount", 3)
                            .put("registeredCameraCount", 2).put("finalSparseOutcome", "verified_anchor_fallback")
                            .put("roundTripVerified", true).put("originalFailureType", original.javaClass.name)
                            .toString(2).toByteArray()
                        require(original.javaClass == AssertionError::class.java && marker.size <= 4096)
                        currentCoroutineContext().ensureActive()
                        captureImmutable(evidence, "partial-roundtrip.json", marker)
                        partialDiagnosticStatus = "VERIFIED_NOT_FULL_THREE"
                    } catch (diagnostic: Throwable) {
                        partialDiagnosticStatus = when (diagnostic) {
                            is TimeoutCancellationException -> "TIMED_OUT"
                            is CancellationException -> "CANCELLED"
                            else -> "FAILED"
                        }
                        throw diagnostic
                    }
                }
            }
            val before = assertPublishedAndExport(fixture, evidence, "before-reopen", finished)
            fixture.assertOriginals()
            stopController(actual); controller = null
            fixture.reopen()
            stage = "VALIDATING_REOPENED_RESULT"
            fixture.captureRuntime(evidence, "after-room-reopen")
            assertEquals(before, assertPublishedAndExport(fixture, evidence, "after-reopen", finished))
            fixture.assertOriginals()
            val persisted = requireNotNull(RoomJobStore(fixture.database, fixture.database.jobDao()).snapshot(queued.jobId))
            assertEquals(finished.jobId, persisted.jobId); assertEquals(finished.input, persisted.input)
            assertEquals(finished.attempt, persisted.attempt)
            assertEquals(DurableJobState.COMPLETED, persisted.state)
            assertEquals(ReconstructionStage.PUBLISHING, persisted.cursor.acceptedStage)
            captureImmutable(evidence, "complete-result.json", JSONObject().put("schema", 1)
                .put("projectId", persisted.input.projectId).put("projectRevision", persisted.input.projectRevision)
                .put("operationId", persisted.input.requestId).put("attempt", persisted.attempt)
                .put("inputIdentitySha256", persisted.input.inputSha256).put("settingsIdentitySha256", persisted.input.settingsSha256)
                .put("engineVersion", persisted.input.engineVersion).put("state", persisted.state.name)
                .put("acceptedStage", persisted.cursor.acceptedStage.name)
                .put("classification", "REAL_LICENSED_THREE_PARTIAL_ARC_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY")
                .put("synthetic", false).put("modeledResources", true).put("jobId", queued.jobId.value)
                .put("originalsPreserved", true).put("exportHashes", JSONObject(before)).toString(2).toByteArray())
        } catch (failure: Throwable) {
            primary = failure
            try {
                withContext(NonCancellable) { withTimeout(3_000) {
                val snapshot = jobId?.let { controller?.jobs?.snapshot(it) }
                captureImmutable(evidence, "failure.json", JSONObject().put("status", "FAILED_NOT_QUALITY_PASS")
                    .put("stage", stage).put("type", failure.javaClass.name)
                    .put("partialDiagnosticStatus", partialDiagnosticStatus)
                    .put("partialDiagnosticStage", partialDiagnosticStage)
                    .put("jobState", snapshot?.state?.name)
                    .put("acceptedStage", snapshot?.cursor?.acceptedStage?.name).put("outcomeCode", snapshot?.outcomeCode)
                    .toString(2).toByteArray())
                } }
            } catch (diagnostic: Throwable) { if (diagnostic !== failure) failure.addSuppressed(diagnostic) }
            throw failure
        } finally {
            withContext(NonCancellable) {
                var stopped = controller == null
                try {
                    controller?.let { stopController(it) }; stopped = true
                } catch (cleanup: Throwable) {
                    if (primary == null) primary = cleanup else if (primary !== cleanup) primary!!.addSuppressed(cleanup)
                }
                // Never close Room or remove artifacts underneath a live production worker.
                if (stopped) for (cleanup in listOf<() -> Unit>({ OpaqueBitmapBridge.reset() }, { fixture.close() })) {
                    try { cleanup() } catch (failure: Throwable) {
                        if (primary == null) primary = failure else if (primary !== failure) primary!!.addSuppressed(failure)
                    }
                }
                primary?.let { throw it }
            }
        }
    }

    /** Read-only proof of the actual controller's complete fixed pair schedule, never a replay.
     * No coordinates, descriptors, cache payloads or private paths enter public evidence.
     */
    private suspend fun assertCompletePersistedPairGraph(f: Fixture, job: JobSnapshot) {
        require(job.state == DurableJobState.COMPLETED)
        val input = f.repository.inputSnapshot(f.project)
        require(input.revision == job.input.projectRevision && input.photos.size == 3)
        require(InputPhotoIdentity.snapshotSha256(input) == job.input.inputSha256)
        require(sha(job.input.settingsJson.toByteArray()) == job.input.settingsSha256)
        val settings = JSONObject(job.input.settingsJson)
        require(settings.getInt("schema") == 2 && settings.getDouble("focal35") == 88.0)
        require(!LensCalibration.file(f.repository, f.project).exists())
        val snapshot = ProductionPipelineSnapshot.create(input,
            ProductionPipelineSnapshot.calibrationSha256(LensCalibration.file(f.repository, f.project)),
            settings.getDouble("focal35"), ResourceWorkloadSelection.decode(settings.getJSONObject("resourceSelection")))
        val root = f.repository.directory(f.project)
        fun owned(file: File): File {
            require(file.canonicalFile.toPath() == file.absoluteFile.toPath().normalize())
            require(file.toPath().startsWith(root.toPath()) && file != root)
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            return file
        }
        val cache = FeatureMatchCache(root, snapshot.identity(PipelineStage.MATCHING), 3)
        val graphBefore = boundedBytes(owned(cache.file))
        val checksum = owned(File(root, cache.file.name + ".sha256"))
        val checksumBefore = boundedBytes(checksum)
        val context = currentCoroutineContext()
        val matches = requireNotNull(cache.load { context.ensureActive() })
        require(matches.keys == setOf(0 to 1, 0 to 2, 1 to 2))
        require(graphBefore.contentEquals(boundedBytes(owned(cache.file))) &&
            checksumBefore.contentEquals(boundedBytes(checksum)))
    }

    private suspend fun stopController(controller: ReconstructionExecutionController) {
        // Existing HOST lifecycle cleanup pattern; never replace production worker/dispatcher.
        val field = ReconstructionExecutionController::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(controller) as CoroutineScope
        withTimeout(10_000) {
            val current = controller.current.value
            if (current?.state == DurableJobState.RUNNING || current?.state == DurableJobState.QUEUED)
                controller.cancel(requireNotNull(current).jobId)
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        }
    }

    private fun modelResources(context: Context, evidence: File) {
        // Explicit deterministic OS fixtures, not real handset measurements or a policy bypass.
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activity).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            lowMemory = false; availMem = 2L * 1024 * 1024 * 1024; totalMem = 4L * 1024 * 1024 * 1024
        })
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).apply {
            setCurrentThermalStatus(0); setIsPowerSaveMode(false)
        }
        shadowOf(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).apply {
            setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80); setIsCharging(true)
        }
        ShadowStatFs.registerStats(context.filesDir, 2_000_000, 1_500_000, 1_500_000)
        captureImmutable(evidence, "modeled-resources.json", JSONObject().put("classification", "MODELED_HOST_RESOURCES")
            .put("availableSystemBytes", 2L * 1024 * 1024 * 1024).put("thermalStatus", 0)
            .put("batteryPercent", 80).put("charging", true).put("powerSave", false)
            .put("statFsBlocks", 2_000_000).put("statFsAvailableBlocks", 1_500_000)
            .put("managedHeap", "actual JVM runtime; not modeled phone heap")
            .put("storageReservation", "production FileSpaceProbe and StorageBudgetService unchanged")
            .toString(2).toByteArray())
    }

    private suspend fun preserveStrictFailure(original: AssertionError, diagnostic: suspend () -> Unit): Nothing {
        try { diagnostic() }
        catch (secondary: Throwable) { if (secondary !== original) original.addSuppressed(secondary) }
        throw original
    }

    /** Qualification is unchanged; the partial tail may never turn this failure into success. */
    private suspend fun assertStrictPreview(f: Fixture, preview: JSONObject) {
        for (name in listOf("denseStatus", "meshStatus", "textureStatus")) assertEquals(preview.toString().take(4000), "ready", preview.optString(name))
        val importedIds = f.database.dao().photos(f.project).map { it.id }.toSet()
        val cameras = preview.getJSONArray("cameras")
        val registeredIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getString("photoId") }
        assertEquals(3, importedIds.size); assertEquals(3, cameras.length())
        assertEquals(importedIds, registeredIds.toSet())
        assertEquals(3, preview.getInt("registeredCameras"))
        assertEquals("global_typed_ba_admitted12", preview.getString("finalSparseOutcome"))
        assertTrue(preview.has("bundleAdjustment"))
        assertTrue(preview.getInt("pointCount") > 0)
        assertTrue(preview.getJSONObject("densePreview").getInt("pointCount") >= 60)
        assertTrue(preview.getJSONObject("densePreview").getInt("consistentPixels") >= 60)
        assertTrue(preview.getJSONObject("meshPreview").getInt("vertexCount") >= 30)
        assertTrue(preview.getJSONObject("meshPreview").getInt("triangleCount") >= 40)
        assertTrue(preview.getJSONObject("texturePreview").getInt("triangleCount") > 0)
        assertTrue(preview.getJSONObject("texturePreview").getDouble("faceCoverage") > 0.0)
    }

    private suspend fun assertEligiblePartialPreview(f: Fixture, preview: JSONObject) {
        val importedIds = f.database.dao().photos(f.project).map { it.id }.toSet()
        val cameras = preview.getJSONArray("cameras")
        val registeredIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getString("photoId") }
        require(importedIds.size == 3 && cameras.length() == 2 && registeredIds.toSet().size == 2)
        require(importedIds.containsAll(registeredIds))
        require(preview.getInt("registeredCameras") == 2)
        require(preview.getString("finalSparseOutcome") == "verified_anchor_fallback")
        require(listOf("denseStatus", "meshStatus", "textureStatus").all { preview.optString(it) == "ready" })
        assertTrue(preview.getInt("pointCount") > 0)
        assertTrue(preview.getJSONObject("densePreview").getInt("pointCount") >= 60)
        assertTrue(preview.getJSONObject("densePreview").getInt("consistentPixels") >= 60)
        assertTrue(preview.getJSONObject("meshPreview").getInt("vertexCount") >= 30)
        assertTrue(preview.getJSONObject("meshPreview").getInt("triangleCount") >= 40)
        assertTrue(preview.getJSONObject("texturePreview").getInt("triangleCount") > 0)
        assertTrue(preview.getJSONObject("texturePreview").getDouble("faceCoverage") > 0.0)
    }

    private suspend fun assertPublishedAndExport(f: Fixture, evidence: File, phase: String, expectedJob: JobSnapshot, partialDiagnostic: Boolean = false): Map<String, String> {
        val row = requireNotNull(f.database.dao().project(f.project))
        assertEquals(3L, row.revision); assertEquals(row.revision, row.resultRevision)
        val key = requireNotNull(row.resultKey)
        val publication = requireNotNull(f.repository.publications).current(f.project)
        assertTrue(publication is PublicationReadResult.Verified)
        val verified = (publication as PublicationReadResult.Verified).value
        assertEquals(row.revision, verified.manifest.identity.projectRevision)
        val publishedRoot = verified.directory.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(publishedRoot, LinkOption.NOFOLLOW_LINKS))
        require(publishedRoot.toFile().canonicalFile.toPath() == publishedRoot)
        val admittedBytes = linkedMapOf<String, ByteArray>()
        fun publishedBytes(name: String): ByteArray = admittedBytes.getOrPut(name) {
            val file = requireNotNull(verified.artifact(name)) { "Missing published artifact: $name" }
            val path = file.toPath().toAbsolutePath().normalize()
            require(path.startsWith(publishedRoot) && path != publishedRoot)
            require(file.canonicalFile.toPath() == path && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            require(file.length() in 1..16L * 1024 * 1024) { "Published artifact exceeds existing evidence bound" }
            val entry = verified.manifest.entries.single { it.declaration.logicalName == name }
            assertEquals(com.realityforge.scanner.data.publication.ArtifactPresence.PRESENT, entry.presence)
            assertTrue(entry.declaration.required)
            val bytes = boundedBytes(file)
            assertEquals(entry.bytes, bytes.size.toLong()); assertTrue(entry.bytes <= entry.declaration.maxBytes)
            assertEquals(entry.sha256, sha(bytes))
            bytes
        }
        val publishedPreviewBytes = publishedBytes("preview")
        // Preserve the precise immutable published bytes, including any partial/fallback diagnostics.
        captureImmutable(evidence, "$phase-preview.json", publishedPreviewBytes)
        val preview = requireNotNull(f.repository.currentPreview(f.project))
        assertEquals(key, preview.getString("artifactKey")); assertEquals(row.revision, preview.getLong("revision"))
        if (partialDiagnostic) assertEligiblePartialPreview(f, preview) else assertStrictPreview(f, preview)
        val names = listOf("preview", "sparse-glb", "sparse-ply", "dense-glb", "dense-ply", "mesh-glb", "mesh-ply", "textured-glb", "texture-png")
        assertEquals(names.toSet(), verified.manifest.entries.map { it.declaration.logicalName }.toSet())
        val artifactHashes = names.associateWith { name ->
            val bytes = publishedBytes(name)
            val digest = sha(bytes)
            captureImmutable(evidence, "artifact-$digest.bin", bytes)
            digest
        }
        val identityRecord = verified.manifest.identity
        assertEquals(com.realityforge.scanner.data.publication.PublicationPurpose.RESULT_SET, identityRecord.purpose)
        assertEquals(expectedJob.input.projectId, identityRecord.projectId)
        assertEquals(expectedJob.input.projectRevision, identityRecord.projectRevision)
        assertEquals(expectedJob.input.requestId, identityRecord.operationId)
        assertEquals(expectedJob.attempt, identityRecord.attempt)
        assertEquals(expectedJob.input.inputSha256, identityRecord.inputIdentitySha256)
        assertEquals(expectedJob.input.settingsSha256, identityRecord.settingsIdentitySha256)
        assertEquals(expectedJob.input.engineVersion, identityRecord.engineVersion)
        val publicationEntries = org.json.JSONArray()
        verified.manifest.entries.forEach { entry -> publicationEntries.put(JSONObject()
            .put("logicalName", entry.declaration.logicalName).put("relativePath", entry.declaration.relativePath)
            .put("representation", entry.declaration.representation).put("required", entry.declaration.required)
            .put("maxBytes", entry.declaration.maxBytes).put("presence", entry.presence.name)
            .put("bytes", entry.bytes).put("sha256", entry.sha256)) }
        captureImmutable(evidence, "$phase-publication.json", JSONObject().put("schema", 1)
            .put("setId", verified.manifest.setId).put("createdAtEpochMillis", verified.manifest.createdAtEpochMillis)
            .put("purpose", identityRecord.purpose.name).put("projectId", identityRecord.projectId)
            .put("projectRevision", identityRecord.projectRevision).put("operationId", identityRecord.operationId)
            .put("attempt", identityRecord.attempt).put("inputIdentitySha256", identityRecord.inputIdentitySha256)
            .put("settingsIdentitySha256", identityRecord.settingsIdentitySha256).put("engineVersion", identityRecord.engineVersion)
            .put("entries", publicationEntries).toString(2).toByteArray())
        val textured = PhotographicGlb.decode(publishedBytes("textured-glb"))
        assertEquals(preview.getJSONObject("texturePreview").getInt("triangleCount") * 3, textured.indices.size)
        assertTrue(textured.indices.isNotEmpty())
        val source = RepositoryExportSource(f.repository, publication = { requireNotNull(f.repository.publications).current(it) })
        val identity = requireNotNull(source.snapshot(f.project))
        val options = source.available(identity)
        val exports = JSONObject()
        val exportContext = currentCoroutineContext()
        val exportedNames = mapOf("ply" to "sparse-ply", "mesh.ply" to "mesh-ply", "textured.glb" to "textured-glb")
        for (extension in listOf("ply", "mesh.ply", "textured.glb")) {
            val option = options.single { it.id == extension }
            val request = RepositoryExportSource.request(identity, option)
            val target = File(f.context.cacheDir, "complete-export-${UUID.randomUUID()}.$extension")
            try {
                source.materialize(request, target) { exportContext.ensureActive() }
                source.validate(request, target)
                assertTrue(source.isCurrent(identity))
                val exportedBytes = boundedBytes(target)
                val exportHash = sha(exportedBytes)
                assertEquals(option.bytes, exportedBytes.size.toLong()); assertEquals(option.contentSha256, exportHash)
                assertEquals(artifactHashes.getValue(exportedNames.getValue(extension)), exportHash)
                val evidenceName = "artifact-$exportHash.bin"
                captureImmutable(evidence, evidenceName, exportedBytes)
                exports.put(extension, JSONObject().put("path", evidenceName).put("bytes", exportedBytes.size)
                    .put("sha256", exportHash).put("publishedLogicalName", exportedNames.getValue(extension)))
            } finally { check(!target.exists() || target.delete()) }
        }
        captureImmutable(evidence, "$phase-artifacts.json", JSONObject(artifactHashes).toString(2).toByteArray())
        captureImmutable(evidence, "$phase-exports.json", exports.toString(2).toByteArray())
        return artifactHashes
    }

    private class Fixture {
        private val app: Application = RuntimeEnvironment.getApplication()
        private val root = File(app.cacheDir, "complete-controller-${UUID.randomUUID()}")
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }
            override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }
            override fun getApplicationContext(): Context = this
        }
        private val name = "sparse-prefix-${UUID.randomUUID()}.db"
        lateinit var database: ScanDatabase
        lateinit var repository: ProjectRepository
        lateinit var project: String
        private val fixtureDir = File(requireNotNull(System.getProperty("rf.decoder.fixtureDir")))
        private fun open() = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        suspend fun initialize() {
            check(root.mkdir())
            database = open()
            repository = ProjectRepository(context, database.dao(), database = database)
            project = repository.create("Fixed licensed three-view partial-arc controller journey; estimated focal; modeled HOST resources")
            val manifest = File(fixtureDir, "observation-inputs.json")
            assertEquals(MANIFEST_SHA256, sha(manifest))
            val optics = JSONObject(manifest.readText())
            assertFalse(optics.getBoolean("synthetic"))
            assertEquals("alansartlog-skull-cameramoves-flash-no-background-originals-10-11-12", optics.getString("dataset"))
            assertEquals(4272, optics.getInt("width")); assertEquals(2848, optics.getInt("height"))
            assertEquals(3, optics.getJSONArray("images").length())
            assertEquals("ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE", optics.getString("intrinsicsProvenance"))
            assertEquals(88.0, optics.getDouble("nominalFocal35"), 0.0)
            assertEquals(setOf("observation-inputs.json", "images"), fixtureDir.list()!!.toSet())
            assertEquals(HASHES.keys.map { File(it).name }.toSet(), File(fixtureDir, "images").list()!!.toSet())
            for (i in 0..2) {
                val item = optics.getJSONArray("images").getJSONObject(i)
                assertEquals("%02d".format(i + 10), item.getString("id"))
                assertEquals(HASHES.keys.toList()[i], item.getString("path"))
                assertEquals(HASHES.values.toList()[i], item.getString("sha256"))
                assertEquals(setOf("id", "path", "sha256"), item.keys().asSequence().toSet())
            }
            HASHES.forEach { (name, hash) -> assertEquals(hash, sha(File(fixtureDir, name))) }
            val paths = ProjectPathPolicy(File(context.filesDir, "projects"))
            val service = ImportService(ImportSourceAccess { source ->
                require(source.uri in HASHES)
                File(fixtureDir, source.uri).inputStream()
            }, AndroidImportInspector, JournaledOriginalPublicationPort(database.dao(), paths), File(context.cacheDir, "staging"))
            val request = ImportRequest(UUID.randomUUID().toString(), project, 0, HASHES.keys.map(::ImportSource))
            val result = repository.withProjectAccess(ProjectOperationRequest(project, request.operationId,
                ProjectOperationKind.IMPORT_PHOTO, 0)) { service.import(request) }
            assertEquals(3, result.importedCount)
            assertEquals(0, result.rejectedCount)
            assertEquals(3L, result.finalRevision)
            assertOriginalsAndNoResult()
        }
        suspend fun assertOriginals() {
            val photos = database.dao().photos(project)
            assertEquals(3, photos.size)
            assertEquals(HASHES.values.toSet(), photos.map { it.sha256 }.toSet())
            photos.forEach { photo ->
                assertEquals("${photo.id}.image", photo.filename)
                assertEquals(4272, photo.width); assertEquals(2848, photo.height)
                // Current scalar EXIF fallback refuses the originals' anisotropic focal plane.
                // The explicit controller fallback stays an estimate; originals are never rewritten.
                assertNull(photo.focal35mm)
                val file = repository.photoFile(photo)
                assertEquals(photo.bytes, file.length()); assertEquals(photo.sha256, sha(file))
            }
            HASHES.forEach { (name, hash) -> assertEquals(hash, sha(File(fixtureDir, name))) }
        }
        suspend fun assertOriginalsAndNoResult() {
            assertOriginals()
            val record = requireNotNull(database.dao().project(project))
            assertEquals(3L, record.revision); assertNull(record.resultRevision); assertNull(record.resultKey)
            assertEquals(PublicationReadResult.None, requireNotNull(repository.publications).current(project))
        }
        fun captureRuntime(evidence: File, phase: String) {
            // Real native graphics activity, not the Utils Bitmap/Mat bridge shadow.
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(0xff123456.toInt()); assertEquals(0xff123456.toInt(), bitmap.getPixel(1, 1)) }
            finally { bitmap.recycle() }
            val sqliteVersion = database.openHelper.writableDatabase.query("SELECT sqlite_version()").use { cursor ->
                check(cursor.moveToFirst()); cursor.getString(0).also { check(!cursor.moveToNext()) }
            }
            QualifiedCapability.load().captureRuntime(evidence, phase, sqliteVersion)
        }
        suspend fun captureIdentity(evidence: File) {
            val photos = database.dao().photos(project)
            File(evidence, "input-identity.json").writeText(org.json.JSONArray(photos.map { photo -> JSONObject()
                .put("photoId", photo.id).put("imageSha256", photo.sha256)
                .put("fixturePath", HASHES.entries.single { it.value == photo.sha256 }.key) }).toString(2))
        }
        fun reopen() { database.close(); database = open(); repository = ProjectRepository(context, database.dao(), database = database) }
        fun close() {
            var primary: Throwable? = null
            for (action in listOf<() -> Unit>(
                { if (::database.isInitialized) database.close() },
                { check(!context.getDatabasePath(name).exists() || context.deleteDatabase(name)) },
                { check(!root.exists() || root.deleteRecursively()) },
            )) try { action() } catch (error: Throwable) {
                if (primary == null) primary = error else if (primary !== error) primary.addSuppressed(error)
            }
            primary?.let { throw it }
        }
    }
    companion object {
        private fun captureImmutable(root: File, name: String, bytes: ByteArray) {
            require(bytes.size <= 16 * 1024 * 1024)
            val file = File(root, name)
            if (file.exists()) require(boundedBytes(file).contentEquals(bytes)) else file.writeBytes(bytes)
        }
        private const val MANIFEST_SHA256 = "1a7aca44ce3979d274f7790768f74ec667544af7eeb6cee78b3714315d54fde2"
        private val HASHES = linkedMapOf(
            "images/10.JPG" to "9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e",
            "images/11.JPG" to "dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c",
            "images/12.JPG" to "f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1",
        )
        private fun boundedBytes(file: File): ByteArray {
            val path = file.toPath()
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.isRegularFile && before.size() in 1..16L * 1024 * 1024)
            val bytes = ByteArray(before.size().toInt())
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, minOf(65536, bytes.size - offset))
                    require(count > 0) { "Truncated evidence source" }; offset += count
                }
                require(input.read() == -1) { "Evidence source grew beyond its admitted size" }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(after.isRegularFile && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) {
                "Evidence source changed during its bounded read"
            }
            return bytes
        }
        private fun sha(file: File): String = sha(boundedBytes(file))
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
