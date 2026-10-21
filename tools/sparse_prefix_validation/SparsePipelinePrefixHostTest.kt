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

/** Actual two-photo coordinator prefix, explicitly interrupted after accepted sparse checkpoint.
 * Utils opaque pixel bridge is the sole platform interop test double. No controller/resource claim.
 */
@RunWith(SparsePrefixRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE, shadows = [OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SparsePipelinePrefixHostTest {
    @Test(timeout = 120_000) fun realTwoPhotoCoordinatorPersistsSparseBeforeControlledCallerCancellation() = runBlocking(Dispatchers.IO) {
        // Exact previously reviewed loader assertions, executed inside THIS narrow same-owner sandbox.
        SandboxNativeLoaderTokenHostTest().actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries()
        assertSame(javaClass.classLoader, OpaqueBitmapBridge::class.java.classLoader)
        assertSame(javaClass.classLoader, org.opencv.core.Mat::class.java.classLoader)
        OpaqueBitmapBridge.reset()
        val fixture = Fixture()
        var worker: Deferred<*>? = null
        var token = 0L
        var primary: Throwable? = null
        try {
            fixture.initialize()
            val input = fixture.repository.inputSnapshot(fixture.project)
            val revision = input.revision
            assertEquals(2L, revision)
            val calibrationFile = LensCalibration.file(fixture.repository, fixture.project)
            assertFalse(calibrationFile.exists())
            assertNull(LensCalibration.readRecord(fixture.repository, fixture.project))
            val calibrationHash = ProductionPipelineSnapshot.calibrationSha256(calibrationFile)
            assertEquals(sha256Utf8("pinhole-no-profile-v1"), calibrationHash)
            val focal = 760.0 * hypot(36.0, 24.0) / hypot(800.0, 600.0)
            assertEquals(32.88262763223158, focal, 1e-12)
            assertEquals(760.0, ImageLoader.focalPixels(800, 600, focal), 1e-10)
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
            val progress = CopyOnWriteArrayList<SparsePipeline.Progress>()
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
            assertEquals(setOf(1, 2), ready.map { it.photoNumber }.toSet())
            ready.forEach { assertEquals(false, it.cacheHit); assertNotNull(it.nativeStartedAfterMillis); assertNotNull(it.nativeFinishedAfterMillis) }
            assertNotNull(OpaqueBitmapBridge.pixels)
            fun assertCache() {
                val restored = requireNotNull(SparseAlignmentCache(directory).load(plan))
                assertEquals(input.photos.map { it.id }.toSet(), restored.checkpoint.cameras.map { it.photoId }.toSet())
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
            assertCache()
            val persisted = requireNotNull(RoomJobStore(fixture.database, fixture.database.jobDao()).snapshot(job.jobId))
            assertEquals(ReconstructionStage.SPARSE, persisted.cursor.acceptedStage)
            assertEquals(com.realityforge.scanner.data.jobs.ReconstructionJobState.PAUSED, persisted.state)
            fixture.assertOriginalsAndNoResult()
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
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
            project = repository.create("Synthetic relief two-photo coordinator prefix")
            val calibration = File(fixtureDir, "calibration.json")
            assertEquals("284c73d64c92bbed50f762a5bc74b6f39d0a3955a0bd219217b6d23bbd7fd593", sha(calibration))
            val optics = JSONObject(calibration.readText())
            assertTrue(optics.getBoolean("synthetic"))
            assertEquals(800, optics.getInt("width")); assertEquals(600, optics.getInt("height"))
            val cameras = optics.getJSONArray("cameras")
            for (i in 0..1) {
                assertEquals(HASHES.keys.toList()[i], cameras.getJSONObject(i).getString("image"))
                val k = cameras.getJSONObject(i).getJSONArray("K")
                assertEquals(760.0, k.getJSONArray(0).getDouble(0), 0.0)
                assertEquals(760.0, k.getJSONArray(1).getDouble(1), 0.0)
                assertEquals(399.5, k.getJSONArray(0).getDouble(2), 0.0)
                assertEquals(299.5, k.getJSONArray(1).getDouble(2), 0.0)
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
            assertEquals(2, result.importedCount)
            assertEquals(0, result.rejectedCount)
            assertEquals(2L, result.finalRevision)
            assertOriginalsAndNoResult()
        }
        suspend fun assertOriginalsAndNoResult() {
            val photos = database.dao().photos(project)
            assertEquals(2, photos.size)
            assertEquals(HASHES.values.toSet(), photos.map { it.sha256 }.toSet())
            photos.forEach { photo ->
                assertEquals("${photo.id}.image", photo.filename)
                assertEquals(800, photo.width); assertEquals(600, photo.height)
                assertNull(photo.focal35mm)
                val file = repository.photoFile(photo)
                assertEquals(photo.bytes, file.length()); assertEquals(photo.sha256, sha(file))
            }
            HASHES.forEach { (name, hash) -> assertEquals(hash, sha(File(fixtureDir, name))) }
            val record = requireNotNull(database.dao().project(project))
            assertEquals(2L, record.revision); assertNull(record.resultRevision); assertNull(record.resultKey)
            assertEquals(PublicationReadResult.None, requireNotNull(repository.publications).current(project))
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
        private val HASHES = linkedMapOf(
            "view-01.jpg" to "66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c",
            "view-02.jpg" to "c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b",
        )
        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
