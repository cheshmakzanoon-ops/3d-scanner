package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.importing.*
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.publication.JournaledOriginalPublicationPort
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.engine.*
import com.realityforge.scanner.engine.artifacts.ArtifactBinding
import com.realityforge.scanner.engine.features.FeatureWorkSnapshot
import com.realityforge.scanner.engine.features.FeatureWorkPhase
import com.realityforge.scanner.engine.pipeline.*
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import com.realityforge.scanner.work.execution.ReconstructionExecutionController
import hostadapter.OpaqueBitmapBridge
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

/** Actual fixed licensed wider-orbit eight-photo prefix, interrupted after accepted sparse checkpoint.
 * Selection is predeclared from acquisition coverage, not successful reconstruction. Estimated
 * 88mm-equivalent optics, no measured calibration, scale, full-object or device-quality claim.
 * Utils opaque pixel bridge is the sole platform interop test double. No controller/resource claim.
 */
@RunWith(SparsePrefixRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE, shadows = [OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RealLicensedEightOrbitSparsePrefixHostTest {
    @Test(timeout = 120_000) fun fixedEightLicensedOrbitPhotosPersistSparseOrRecordUnmodifiedProductionFailure() = runBlocking(Dispatchers.IO) {
        // Exact previously reviewed loader assertions, executed inside THIS narrow same-owner sandbox.
        CandidateNativeLoaderTokenHostTest().actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries()
        assertSame(javaClass.classLoader, OpaqueBitmapBridge::class.java.classLoader)
        assertSame(javaClass.classLoader, org.opencv.core.Mat::class.java.classLoader)
        OpaqueBitmapBridge.reset()
        val fixture = Fixture()
        val evidence = File(requireNotNull(System.getProperty("rf.eight.evidenceDir")))
        require(evidence.isDirectory && evidence.list()!!.isEmpty())
        val experimentStartedNanos = System.nanoTime()
        val featureWork = CopyOnWriteArrayList<FeatureWorkSnapshot>()
        val pairWork = CopyOnWriteArrayList<com.realityforge.scanner.engine.matching.PairWorkSnapshot>()
        val progress = CopyOnWriteArrayList<SparsePipeline.Progress>()
        var worker: Deferred<*>? = null
        var token = 0L
        var primary: Throwable? = null
        try {
            fixture.initialize()
            fixture.captureRuntime(evidence, "after-bitmap-room")
            fixture.captureIdentity(evidence)
            val input = fixture.repository.inputSnapshot(fixture.project)
            val revision = input.revision
            assertEquals(8L, revision)
            val calibrationFile = LensCalibration.file(fixture.repository, fixture.project)
            assertFalse(calibrationFile.exists())
            assertNull(LensCalibration.readRecord(fixture.repository, fixture.project))
            val calibrationHash = ProductionPipelineSnapshot.calibrationSha256(calibrationFile)
            assertEquals(sha256Utf8("pinhole-no-profile-v1"), calibrationHash)
            val focal = 88.0 // Explicit estimate, unchanged real-photo policy; not measured intrinsics.
            val resources = ResourceWorkloadSelection.COMPACT
            val settings = JSONObject().put("schema", 2).put("focal35", focal)
                .put("resourceSelection", resources.encode()).toString()
            val operation = UUID.randomUUID().toString()
            val jobs = RoomJobStore(fixture.database, fixture.database.jobDao())
            val job = jobs.enqueue(JobInputSnapshot(fixture.project, revision, operation,
                InputPhotoIdentity.snapshotSha256(input), calibrationHash, sha256Utf8(settings),
                sha256Utf8(ReconstructionExecutionController.ENGINE_VERSION), ReconstructionExecutionController.ENGINE_VERSION,
                settingsJson = settings), System.currentTimeMillis())
            var lease = requireNotNull(jobs.acquireNext("host-sparse-prefix", System.currentTimeMillis(), 300_000)).lease
            assertEquals(job.jobId, lease.jobId)
            val binding = ArtifactBinding(fixture.project, revision, job.jobId.value, lease.attempt)
            val snapshot = ProductionPipelineSnapshot.create(input, calibrationHash, focal, resources)
            val plan = PipelineArtifactSession.identityPlan(snapshot, binding)
            val directory = fixture.repository.directory(fixture.project)
            assertFalse(File(directory, "features").exists())
            assertNull(SparseAlignmentCache(directory).load(plan))
            val started = CopyOnWriteArrayList<ReconstructionStage>()
            val accepted = CopyOnWriteArrayList<ReconstructionStage>()
            val barrier = CompletableDeferred<Unit>()
            val sink = object : PipelineCheckpointSink {
                override suspend fun started(stage: ReconstructionStage) {
                    started += stage
                    check(stage.order <= ReconstructionStage.SPARSE.order) { "Prefix crossed into downstream work: $stage" }
                }
                override suspend fun accepted(cursor: JobStageCursor) {
                    lease = jobs.acceptStage(lease, cursor, System.currentTimeMillis()).lease
                    accepted += cursor.acceptedStage
                    if (cursor.acceptedStage == ReconstructionStage.SPARSE) {
                        barrier.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            token = NativeCore.beginReconstruction()
            assertTrue(token > 0)
            supervisorScope {
                val running = async {
                    fixture.repository.withProjectAccess(ProjectOperationRequest(fixture.project, operation,
                        ProjectOperationKind.RECONSTRUCT, revision, ProjectJobIdentity(job.jobId.value, lease.token))) {
                        SparsePipeline(fixture.repository, resources, binding, sink).apply {
                            onFeatureWork = { featureWork += it }
                            onPairWork = { pairWork += it }
                        }.run(fixture.project, focal, token) { progress += it }
                    }
                }
                worker = running
                var workerFailure: Throwable? = null
                try {
                    withTimeout(90_000) {
                        select<Unit> {
                            barrier.onAwait { }
                            running.onAwait { error("Coordinator returned before controlled SPARSE barrier") }
                        }
                    }
                    running.cancel(CancellationException("HOST_PREFIX_CHECKPOINT_STOP"))
                    withTimeout(10_000) { running.join() }
                    assertTrue(running.isCancelled && running.isCompleted)
                } catch (failure: Throwable) { workerFailure = failure; throw failure }
                finally {
                    try {
                        withContext(NonCancellable) {
                            if (!running.isCompleted) {
                                NativeCore.cancelReconstruction(token)
                                running.cancel()
                                withTimeout(10_000) { running.join() }
                            }
                        }
                    } catch (cleanup: Throwable) {
                        if (workerFailure == null) throw cleanup
                        if (workerFailure !== cleanup) workerFailure.addSuppressed(cleanup)
                    }
                }
            }
            // The worker and its callbacks/project lease are gone before native token disposal.
            assertTrue(requireNotNull(worker).isCompleted)
            assertTrue(NativeCore.finishReconstruction(token)); token = 0
            jobs.relinquish(lease, "HOST_PREFIX_CHECKPOINT_STOP", System.currentTimeMillis())
            assertEquals(listOf(ReconstructionStage.PREPARED, ReconstructionStage.FEATURES,
                ReconstructionStage.MATCHES, ReconstructionStage.SPARSE), accepted.toList())
            assertFalse(started.any { it.order > ReconstructionStage.SPARSE.order })
            assertTrue(progress.isNotEmpty())
            val ready = featureWork.filter { it.phase == FeatureWorkPhase.READY }
            assertEquals((1..8).toSet(), ready.map { it.photoNumber }.toSet())
            ready.forEach { assertEquals(false, it.cacheHit); assertNotNull(it.nativeStartedAfterMillis); assertNotNull(it.nativeFinishedAfterMillis) }
            assertNotNull(OpaqueBitmapBridge.pixels)
            fun assertCache() {
                val restored = requireNotNull(SparseAlignmentCache(directory).load(plan))
                val selectedIds = input.photos.map { checkedPhotoId(it.id) }.toSet()
                val registeredIds = restored.checkpoint.cameras.map { checkedPhotoId(it.photoId) }.toSet()
                assertTrue(selectedIds.containsAll(registeredIds))
                assertEquals((1..28).toSet(), pairWork.filter { it.phase == com.realityforge.scanner.engine.matching.PairWorkPhase.READY }.map { it.pairNumber }.toSet())
                assertTrue(pairWork.all { it.pairCount == 28 })
                val finalOutcome = restored.geometry.getString("finalSparseOutcome")
                require(finalOutcome in setOf("global_typed_ba_admitted12", "verified_anchor_fallback"))
                val safeGeometry = JSONObject().put("finalSparseOutcome", finalOutcome)
                    .put("pointCount", restored.geometry.getInt("pointCount"))
                restored.geometry.optJSONObject("bundleAdjustment")?.let { actual ->
                    val summary = JSONObject()
                    for (field in listOf("inputObservations", "usableObservations", "rejectedObservations", "acceptedSteps", "attemptedIterations"))
                        summary.put(field, actual.getInt(field))
                    for (field in listOf("robustCostBefore", "robustCostAfter", "medianBeforeNormalized", "medianAfterNormalized")) {
                        val value = actual.getDouble(field); require(value.isFinite()); summary.put(field, value)
                    }
                    safeGeometry.put("bundleAdjustment", summary)
                }
                val receipt = JSONObject().put("schema", 1).put("synthetic", false)
                    .put("classification", if (registeredIds == selectedIds && restored.geometry.optString("finalSparseOutcome") == "global_typed_ba_admitted12") "REAL_LICENSED_EIGHT_WIDER_ORBIT_HOST_SPARSE_PREFIX_NOT_DEVICE_OR_SURFACE_QUALITY" else "INCOMPLETE_OR_FALLBACK")
                    .put("inputPhotoIds", org.json.JSONArray(selectedIds.toList()))
                    .put("registeredPhotoIds", org.json.JSONArray(registeredIds.toList()))
                    .put("unregisteredPhotoIds", org.json.JSONArray((selectedIds - registeredIds).toList()))
                    .put("geometry", safeGeometry)
                // Only typed, explicitly allowlisted numerical evidence leaves the private project.
                // The original checkpoint and parent files remain local and are read/validated below.
                captureImmutable(evidence, "sparse-result.json", receipt.toString(2).toByteArray())
                val inventory = org.json.JSONArray()
                val reader = PipelineManifestReader(directory)
                for ((stage, manifest) in restored.parents) {
                    require(stage.name in setOf("FEATURES", "MATCHES", "ALIGNMENT"))
                    val encoded = com.realityforge.scanner.engine.artifacts.StageManifestCodec.encode(manifest)
                    val hash = com.realityforge.scanner.engine.artifacts.Sha256.digest(encoded)
                    val original = reader.readBounded(PipelineArtifactSession.manifestFile(directory, hash), 1024 * 1024)
                    assertTrue(original.contentEquals(encoded))
                    val snapshotBytes = reader.readBounded(PipelineArtifactSession.inputFile(directory, manifest.inputSnapshotRecordHash), 2 * 1024 * 1024)
                    assertEquals(manifest.inputSnapshotRecordHash, com.realityforge.scanner.engine.artifacts.Sha256.digest(snapshotBytes))
                    for (artifact in manifest.artifacts) {
                        inventory.put(JSONObject().put("stage", stage.name)
                            .put("sha256", artifact.contentHash.hex).put("bytes", artifact.byteSize))
                        if (stage.name == "ALIGNMENT" || stage.name == "MATCHES") {
                            val bytes = reader.readBounded(File(directory, artifact.relativePath), 16 * 1024 * 1024)
                            assertEquals(artifact.contentHash, com.realityforge.scanner.engine.artifacts.Sha256.digest(bytes))
                        }
                    }
                }
                captureImmutable(evidence, "artifact-inventory.json", inventory.toString(2).toByteArray())
                // The actual accepted checkpoint's graph is distinct from the 28-pair candidate schedule.
                // These residuals measure internal fit, never independent physical accuracy.
                val sparseDiagnostics = JSONObject().put("schema", 1)
                    .put("graphScope", "REGISTERED_CAMERA_CHECKPOINT_GRAPH_NOT_ALL_INPUT_INITIALIZER_EDGES")
                    .put("allInputVerifiedEdgeIdentities", "NOT_EXPOSED_BY_PRODUCTION_DIAGNOSTICS")
                    .put("registeredPhotoIds", org.json.JSONArray(restored.checkpoint.cameras.map { it.photoId }))
                    .put("cameras", org.json.JSONArray(restored.checkpoint.cameras.map { camera ->
                        JSONObject().put("photoId", checkedPhotoId(camera.photoId)).put("width", camera.width).put("height", camera.height)
                            .put("fx", camera.fx).put("fy", camera.fy).put("cx", camera.cx).put("cy", camera.cy)
                            .put("rotationWorldToCamera", org.json.JSONArray(camera.rotationWorldToCamera))
                            .put("translationWorldToCamera", org.json.JSONArray(camera.translationWorldToCamera))
                    }))
                    .put("tracks", org.json.JSONArray(restored.checkpoint.tracks.mapIndexed { ordinal, track ->
                        JSONObject().put("ordinal", ordinal).put("x", track.x).put("y", track.y).put("z", track.z)
                            .put("reprojectionRmsNormalized", track.reprojectionRmsNormalized)
                            .put("observations", org.json.JSONArray(track.observations.map { observation ->
                                JSONObject().put("cameraIndex", observation.cameraIndex).put("featureIndex", observation.featureIndex)
                                    .put("xNormalized", observation.xNormalized).put("yNormalized", observation.yNormalized)
                            }))
                    }))
                    .put("trackCount", restored.checkpoint.tracks.size)
                    .put("observationCount", restored.checkpoint.tracks.sumOf { it.observations.size })
                    .put("verifiedMatchGraph", org.json.JSONArray(restored.checkpoint.verifiedMatchGraph.map { edge ->
                        JSONObject().put("firstCamera", edge.firstCamera).put("secondCamera", edge.secondCamera)
                            .put("firstPhotoId", restored.checkpoint.cameras[edge.firstCamera].photoId)
                            .put("secondPhotoId", restored.checkpoint.cameras[edge.secondCamera].photoId)
                            .put("verifiedMatches", edge.verifiedMatches).put("inliers", edge.inliers)
                            .put("medianErrorNormalized", edge.medianErrorNormalized)
                    }))
                captureImmutable(evidence, "sparse-diagnostics.json", sparseDiagnostics.toString(2).toByteArray())
                assertTrue(restored.geometry.getJSONArray("points").length() > 0)
                assertTrue(restored.checkpoint.tracks.isNotEmpty())
                val outcome = restored.geometry.optString("finalSparseOutcome", "not_reported")
                assertTrue(outcome in setOf("global_typed_ba_admitted12", "verified_anchor_fallback"))
                if (outcome == "global_typed_ba_admitted12") assertTrue(restored.geometry.has("bundleAdjustment"))
                else assertTrue(restored.geometry.has("finalSparseFailure"))
                // Measured classification, not a fabricated refined success or fallback-as-refinement claim.
                println("HOST_PREFIX_SPARSE outcome=$outcome bundleAdjustment=${restored.geometry.has("bundleAdjustment")} points=${restored.geometry.getJSONArray("points").length()}")
                assertEquals(binding, restored.checkpoint.binding)
            }
            assertCache()
            fixture.assertOriginalsAndNoResult()
            fixture.reopen()
            fixture.captureRuntime(evidence, "after-room-reopen")
            assertCache()
            val persisted = requireNotNull(RoomJobStore(fixture.database, fixture.database.jobDao()).snapshot(job.jobId))
            assertEquals(ReconstructionStage.SPARSE, persisted.cursor.acceptedStage)
            assertEquals(com.realityforge.scanner.data.jobs.ReconstructionJobState.PAUSED, persisted.state)
            fixture.assertOriginalsAndNoResult()
            fixture.captureIdentity(evidence)
            // Preserve the genuine partial checkpoint and reopen evidence BEFORE strict qualification.
            val strict = requireNotNull(SparseAlignmentCache(directory).load(plan))
            assertEquals(input.photos.map { it.id }.toSet(), strict.checkpoint.cameras.map { it.photoId }.toSet())
            assertEquals("global_typed_ba_admitted12", strict.geometry.getString("finalSparseOutcome"))
            assertTrue(strict.geometry.has("bundleAdjustment"))
        } catch (failure: Throwable) {
            primary = failure
            primary = preserveEvidenceFailure(primary) {
                File(evidence, "failure.json").writeText(JSONObject().put("schema", 1)
                    .put("status", "FAILED_NOT_QUALITY_PASS").put("phase", "BEFORE_OR_AT_SPARSE").toString(2))
            }
            throw failure
        }
        finally {
            primary = preserveEvidenceFailure(primary) {
                captureImmutable(evidence, "sparse-timing.json", JSONObject().put("schema", 1)
                    .put("elapsedMillis", (System.nanoTime() - experimentStartedNanos).coerceAtLeast(0L) / 1_000_000L)
                    .put("scope", "HOST_MONOTONIC_NOT_DEVICE_PERFORMANCE")
                    .put("featureEvents", org.json.JSONArray(featureWork.map { item -> JSONObject()
                        .put("photoNumber", item.photoNumber).put("photoCount", item.photoCount).put("phase", item.phase.name)
                        .put("elapsedMillis", item.elapsedMillis).put("nativeElapsedMillis", item.nativeElapsedMillis)
                        .put("cacheHit", item.cacheHit) }))
                    .put("pairEvents", org.json.JSONArray(pairWork.map { item -> JSONObject()
                        .put("pairNumber", item.pairNumber).put("phase", item.phase.name)
                        .put("elapsedMillis", item.elapsedMillis).put("loadElapsedMillis", item.loadElapsedMillis)
                        .put("matchElapsedMillis", item.matchElapsedMillis).put("geometryElapsedMillis", item.geometryElapsedMillis) }))
                    .toString(2).toByteArray())
            }
            primary = preserveEvidenceFailure(primary) {
                File(evidence, "pair-events.json").writeText(org.json.JSONArray(pairWork.map { item -> JSONObject()
                    .put("pairNumber", item.pairNumber).put("pairCount", item.pairCount).put("phase", item.phase.name)
                    .put("firstDescriptorRows", item.firstDescriptorRows).put("secondDescriptorRows", item.secondDescriptorRows)
                    .put("matchCacheHit", item.matchCacheHit).put("geometryEntered", item.geometryEntered) }).toString(2))
            }
            // Never close DB, finish native ownership or delete files beneath a still-live worker.
            if (worker?.isCompleted != false) {
                for (cleanup in listOf<() -> Unit>(
                    { if (token > 0) check(NativeCore.finishReconstruction(token)) },
                    { OpaqueBitmapBridge.reset() }, { fixture.close() },
                )) try { cleanup() } catch (error: Throwable) {
                    if (primary == null) primary = error else if (primary !== error) primary.addSuppressed(error)
                }
            } else {
                val failure = IllegalStateException("Worker remains live; retained fixture/native ownership for process watchdog")
                if (primary == null) primary = failure else primary.addSuppressed(failure)
            }
            primary?.let { throw it }
        }
    }
    private class Fixture {
        private val app: Application = RuntimeEnvironment.getApplication()
        private val root = File(app.cacheDir, "sparse-prefix-${UUID.randomUUID()}")
        private val context = object : ContextWrapper(app) {
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
            project = repository.create("Fixed licensed eight-view wider-orbit sparse prefix; estimated focal")
            val manifest = File(fixtureDir, "observation-inputs.json")
            assertEquals(MANIFEST_SHA256, sha(manifest))
            val optics = JSONObject(manifest.readText())
            assertFalse(optics.getBoolean("synthetic"))
            assertEquals("alansartlog-skull-cameramoves-flash-no-background-originals-01-03-05-07-09-11-13-15", optics.getString("dataset"))
            assertEquals("ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE", optics.getString("intrinsicsProvenance"))
            assertEquals(88.0, optics.getDouble("nominalFocal35"), 0.0)
            assertEquals(4272, optics.getInt("width")); assertEquals(2848, optics.getInt("height"))
            assertEquals(8, optics.getJSONArray("images").length())
            assertEquals(setOf("observation-inputs.json", "images"), fixtureDir.list()!!.toSet())
            assertEquals(HASHES.keys.map { File(it).name }.toSet(), File(fixtureDir, "images").list()!!.toSet())
            for (i in 0..7) {
                val item = optics.getJSONArray("images").getJSONObject(i)
                assertEquals("%02d".format(2 * i + 1), item.getString("id"))
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
            assertEquals(8, result.importedCount)
            assertEquals(0, result.rejectedCount)
            assertEquals(8L, result.finalRevision)
            assertOriginalsAndNoResult()
        }
        suspend fun assertOriginalsAndNoResult() {
            val photos = database.dao().photos(project)
            assertEquals(8, photos.size)
            assertEquals(HASHES.values.toSet(), photos.map { it.sha256 }.toSet())
            photos.forEach { photo ->
                assertEquals("${photo.id}.image", photo.filename)
                assertEquals(4272, photo.width); assertEquals(2848, photo.height)
                // Existing scalar metadata policy refuses anisotropic focal-plane data; 88mm remains an estimate.
                assertNull(photo.focal35mm)
                val file = repository.photoFile(photo)
                assertEquals(photo.bytes, file.length()); assertEquals(photo.sha256, sha(file))
            }
            HASHES.forEach { (name, hash) -> assertEquals(hash, sha(File(fixtureDir, name))) }
            val record = requireNotNull(database.dao().project(project))
            assertEquals(8L, record.revision); assertNull(record.resultRevision); assertNull(record.resultKey)
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
            require(phase in setOf("after-bitmap-room", "after-room-reopen"))
            try { QualifiedCapability.load().captureRuntime(evidence, phase, sqliteVersion) }
            catch (failure: Throwable) {
                // Provider's failed receipt may contain raw exception text: never retain it here.
                val failedReceipt = File(evidence, "runtime-providers-$phase.json")
                try { if (failedReceipt.exists()) check(failedReceipt.delete()) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        suspend fun captureIdentity(evidence: File) {
            val photos = database.dao().photos(project)
            File(evidence, "input-identity.json").writeText(org.json.JSONArray(photos.map { photo -> JSONObject()
                .put("photoId", checkedPhotoId(photo.id)).put("imageSha256", photo.sha256)
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
        private fun checkedPhotoId(value: String): String {
            require(UUID.fromString(value).toString() == value)
            return value
        }
        private fun captureImmutable(root: File, name: String, bytes: ByteArray) {
            require(bytes.size <= 16 * 1024 * 1024)
            val file = File(root, name)
            if (file.exists()) require(file.readBytes().contentEquals(bytes)) else file.writeBytes(bytes)
        }
        private const val MANIFEST_SHA256 = "a69abe719ba5d0ea0de96d36dc452d9a3889a4167a22bccf080f9c1bd8ad33e0"
        private val HASHES = linkedMapOf(
            "images/01.JPG" to "49cc69b7e2e8999252c71536cc5a8a0ee32ae25d56d896969542653fa93e4858",
            "images/03.JPG" to "3aae3bbf830469ea8711915545627692b9655de9f881fa0b9a128543b8fb4be1",
            "images/05.JPG" to "762c2fe0f5780bc57ae7c3e65bafc5046c60756b65428576aa47d54df03e994c",
            "images/07.JPG" to "cef6c51017e11d2c35f359f80920a28d866c6bc7d1d4f6e876e1718a328ec79c",
            "images/09.JPG" to "6190f02734af4cd137426afba19352d2c3b412e4016ec844eae61c882e341eb4",
            "images/11.JPG" to "dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c",
            "images/13.JPG" to "06074dbb72eaf56786bfcb9d69da66aa47657f076090d9e470ba2e5c19169700",
            "images/15.JPG" to "3366c2efb3f7b70114b94792b9c6b85141b4631111f8bb0f447d370ca0fb098f",
        )

        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
