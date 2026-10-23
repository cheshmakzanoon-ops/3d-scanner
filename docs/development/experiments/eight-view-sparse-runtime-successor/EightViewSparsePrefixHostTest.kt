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

/** Actual fixed eight-photo coordinator prefix, explicitly interrupted after accepted sparse checkpoint.
 * Utils opaque pixel bridge is the sole platform interop test double. No controller/resource claim.
 */
@RunWith(SparsePrefixRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE, shadows = [OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EightViewSparsePrefixHostTest {
    @Test(timeout = 120_000) fun fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure() = runBlocking(Dispatchers.IO) {
        // Exact previously reviewed loader assertions, executed inside THIS narrow same-owner sandbox.
        CandidateNativeLoaderTokenHostTest().actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries()
        assertSame(javaClass.classLoader, OpaqueBitmapBridge::class.java.classLoader)
        assertSame(javaClass.classLoader, org.opencv.core.Mat::class.java.classLoader)
        OpaqueBitmapBridge.reset()
        val fixture = Fixture()
        val evidence = File(requireNotNull(System.getProperty("rf.eight.evidenceDir")))
        require(evidence.isDirectory && evidence.list()!!.isEmpty())
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
            val focal = 200.0 * hypot(36.0, 24.0) / hypot(160.0, 120.0)
            assertEquals(43.26661530556787, focal, 1e-12)
            assertEquals(200.0, ImageLoader.focalPixels(160, 120, focal), 1e-10)
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
            val featureWork = CopyOnWriteArrayList<FeatureWorkSnapshot>()
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
                val selectedIds = input.photos.map { it.id }.toSet()
                val registeredIds = restored.checkpoint.cameras.map { it.photoId }.toSet()
                assertTrue(selectedIds.containsAll(registeredIds))
                assertEquals((1..28).toSet(), pairWork.filter { it.phase == com.realityforge.scanner.engine.matching.PairWorkPhase.READY }.map { it.pairNumber }.toSet())
                assertTrue(pairWork.all { it.pairCount == 28 })
                val receipt = JSONObject().put("schema", 1).put("synthetic", true)
                    .put("classification", if (registeredIds == selectedIds && restored.geometry.optString("finalSparseOutcome") == "global_typed_ba_admitted12") "ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY" else "INCOMPLETE_OR_FALLBACK")
                    .put("inputPhotoIds", org.json.JSONArray(selectedIds.toList()))
                    .put("registeredPhotoIds", org.json.JSONArray(registeredIds.toList()))
                    .put("unregisteredPhotoIds", org.json.JSONArray((selectedIds - registeredIds).toList()))
                    .put("geometry", restored.geometry)
                // Preserve actual accepted checkpoint bytes and parent manifest receipts, not reconstructed synthetic observations.
                val checkpoint = com.realityforge.scanner.engine.artifacts.AlignmentCheckpointCodec.encode(restored.checkpoint)
                captureImmutable(evidence, "rebound-alignment.rfac", checkpoint)
                captureImmutable(evidence, "sparse-result.json", receipt.toString(2).toByteArray())
                val inventory = org.json.JSONArray()
                val reader = PipelineManifestReader(directory)
                for ((stage, manifest) in restored.parents) {
                    val encoded = com.realityforge.scanner.engine.artifacts.StageManifestCodec.encode(manifest)
                    val hash = com.realityforge.scanner.engine.artifacts.Sha256.digest(encoded)
                    val original = reader.readBounded(PipelineArtifactSession.manifestFile(directory, hash), 1024 * 1024)
                    assertTrue(original.contentEquals(encoded))
                    captureImmutable(evidence, "manifest-${stage.name}.rfsm", original)
                    val snapshotBytes = reader.readBounded(PipelineArtifactSession.inputFile(directory, manifest.inputSnapshotRecordHash), 2 * 1024 * 1024)
                    captureImmutable(evidence, "input-${manifest.inputSnapshotRecordHash.hex}.rfis", snapshotBytes)
                    for (artifact in manifest.artifacts) {
                        inventory.put(JSONObject().put("stage", stage.name).put("path", artifact.relativePath)
                            .put("sha256", artifact.contentHash.hex).put("bytes", artifact.byteSize))
                        if (stage.name == "ALIGNMENT" || stage.name == "MATCHES") {
                            val bytes = reader.readBounded(File(directory, artifact.relativePath), 16 * 1024 * 1024)
                            assertEquals(artifact.contentHash, com.realityforge.scanner.engine.artifacts.Sha256.digest(bytes))
                            captureImmutable(evidence, "artifact-${artifact.contentHash.hex}.bin", bytes)
                        }
                    }
                }
                captureImmutable(evidence, "artifact-inventory.json", inventory.toString(2).toByteArray())
                assertTrue(restored.geometry.getJSONArray("points").length() > 0)
                assertTrue(restored.checkpoint.tracks.isNotEmpty())
                val outcome = restored.geometry.optString("finalSparseOutcome", "not_reported")
                assertTrue(outcome in setOf("global_typed_ba_admitted12", "verified_anchor_fallback"))
                if (outcome == "global_typed_ba_admitted12") assertTrue(restored.geometry.has("bundleAdjustment"))
                else assertTrue(restored.geometry.has("finalSparseFailure"))
                // Measured classification, not a fabricated refined success or fallback-as-refinement claim.
                println("HOST_PREFIX_SPARSE outcome=$outcome bundleAdjustment=${restored.geometry.has("bundleAdjustment")} points=${restored.geometry.getJSONArray("points").length()}")
                println("HOST_PREFIX_SPARSE refinementFailure=${restored.geometry.optString("finalSparseFailure", "none")}")
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
        } catch (failure: Throwable) {
            primary = failure
            primary = preserveEvidenceFailure(primary) {
                File(evidence, "failure.json").writeText(JSONObject().put("status", "FAILED_NOT_QUALITY_PASS")
                    .put("type", failure.javaClass.name).put("message", failure.message)
                    .put("lastProgress", progress.lastOrNull()?.stage).toString(2))
            }
            throw failure
        }
        finally {
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
            project = repository.create("Fixed synthetic eight-view sparse prefix")
            val manifest = File(fixtureDir, "observation-inputs.json")
            assertEquals(MANIFEST_SHA256, sha(manifest))
            val optics = JSONObject(manifest.readText())
            assertTrue(optics.getBoolean("synthetic"))
            assertEquals(160, optics.getInt("width")); assertEquals(120, optics.getInt("height"))
            assertEquals(8, optics.getJSONArray("images").length())
            assertEquals(setOf("observation-inputs.json", "images"), fixtureDir.list()!!.toSet())
            assertEquals(HASHES.keys.map { File(it).name }.toSet(), File(fixtureDir, "images").list()!!.toSet())
            for (i in 0..7) {
                val item = optics.getJSONArray("images").getJSONObject(i)
                assertEquals("view-%02d".format(i), item.getString("id"))
                assertEquals(HASHES.keys.toList()[i], item.getString("path"))
                val k = item.getJSONArray("K")
                val expected = arrayOf(doubleArrayOf(200.0,0.0,79.5), doubleArrayOf(0.0,200.0,59.5), doubleArrayOf(0.0,0.0,1.0))
                for (row in 0..2) for (col in 0..2) assertEquals(expected[row][col], k.getJSONArray(row).getDouble(col), 0.0)
                for (d in 0..4) assertEquals(0.0, item.getJSONArray("distortion").getDouble(d), 0.0)
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
                assertEquals(160, photo.width); assertEquals(120, photo.height)
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
            if (file.exists()) require(file.readBytes().contentEquals(bytes)) else file.writeBytes(bytes)
        }
        private const val MANIFEST_SHA256 = "4f58396f5e519cdd959e6dca1af2709dcb685dafdb017c08f3947a667d1a3da2"
        private val HASHES = linkedMapOf(
            "images/view-00.png" to "7afa0439561a42f2e95747f5f176c1298cbbdc078d8c5aa688bdd4e7a808c824",
            "images/view-01.png" to "c27de4f1cdcde8c9a5789a76e43a95a997cb8d59937a963723156a86755baa8a",
            "images/view-02.png" to "8d3e48b9aeccbc83c50d05d3c4f2d43dd2138691e8cce2ad5ca64396bb6fa938",
            "images/view-03.png" to "9105cde8db1ea0459386be997f7ce95606d763053a380a6c505cc9e4a0958e91",
            "images/view-04.png" to "9b37373f1739d8019e89b9bb9360dad8d7bd0b2dde2fbc5f16448cac71571fc5",
            "images/view-05.png" to "e7f46a7b187208a497bfe5f58c1092b4b40eb6cd47407108d58320be284037b6",
            "images/view-06.png" to "cbc1316ed871bc1909368d8b4c28a97273828875387071a003143442eec3ec8a",
            "images/view-07.png" to "83ab44d9c1cd618961f0be934aa64bf4dbfc780dcd8290f09212a752c93a6c5a",
        )
        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
