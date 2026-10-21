package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.cachegc.CacheStageManifestStore
import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import com.realityforge.scanner.export.LegacyPlyValidation
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.viewer.loading.GlbStructure
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

/** Actual three-photo core return plus file validation; current result publication remains separate.
 * Utils opaque pixel bridge is the sole platform interop test double. No controller/resource claim.
 */
@RunWith(SparsePrefixRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE, shadows = [OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SparsePipelineQualityCaptureHostTest {
    @Test(timeout = 180_000) fun realThreePhotoCoordinatorReturnsValidatedDenseMeshTextureAndExports() = runBlocking(Dispatchers.IO) {
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
            assertEquals(3L, revision)
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
            val sink = object : PipelineCheckpointSink {
                override suspend fun started(stage: ReconstructionStage) {
                    started += stage
                }
                override suspend fun accepted(cursor: JobStageCursor) {
                    lease = jobs.acceptStage(lease, cursor, System.currentTimeMillis()).lease
                    accepted += cursor.acceptedStage
                }
            }
            token = NativeCore.beginReconstruction()
            assertTrue(token > 0)
            val result = supervisorScope {
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
                    withTimeout(150_000) { running.await() }
                } catch (failure: Throwable) {
                    workerFailure = failure
                    println("HOST_FULL_CORE runFailure=${failure.javaClass.name}: ${failure.message}; lastProgress=${progress.lastOrNull()?.stage}")
                    throw failure
                }
                finally {
                    try {
                        withContext(NonCancellable) {
                            if (!running.isCompleted) {
                                var cleanupFailure: Throwable? = null
                                for (action in listOf<suspend () -> Unit>(
                                    { NativeCore.cancelReconstruction(token); Unit },
                                    { running.cancel() },
                                    { withTimeout(10_000) { running.join() } },
                                )) try { action() } catch (failure: Throwable) {
                                    val first = cleanupFailure
                                    if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
                                }
                                cleanupFailure?.let { throw it }
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
            jobs.relinquish(lease, "HOST_CORE_RETURN_UNPUBLISHED", System.currentTimeMillis())
            println("HOST_FULL_CORE sparse=${result.optString("finalSparseOutcome")} dense=${result.optString("denseStatus")} mesh=${result.optString("meshStatus")} texture=${result.optString("textureStatus")}")
            println("HOST_FULL_CORE failures sparse=${result.optString("finalSparseFailure", "none")} dense=${result.optString("denseFailure", "none")} mesh=${result.optString("meshFailure", "none")} texture=${result.optString("textureFailure", "none")}")
            println("HOST_FULL_CORE counts cameras=${result.optInt("registeredCameras")} sparsePoints=${result.optInt("pointCount")} dense=${result.optJSONObject("densePreview")?.optInt("pointCount")} triangles=${result.optJSONObject("meshPreview")?.optInt("triangleCount")}")
            assertEquals(listOf(ReconstructionStage.PREPARED, ReconstructionStage.FEATURES,
                ReconstructionStage.MATCHES, ReconstructionStage.SPARSE, ReconstructionStage.DENSE,
                ReconstructionStage.SURFACE, ReconstructionStage.TEXTURE), accepted.toList())
            assertEquals("global_typed_ba_admitted12", result.getString("finalSparseOutcome"))
            assertTrue(result.has("bundleAdjustment"))
            assertEquals(3, result.getJSONArray("cameras").length())
            for (field in listOf("denseStatus", "meshStatus", "textureStatus")) assertEquals(field, "ready", result.getString(field))
            assertTrue(started.containsAll(listOf(ReconstructionStage.DENSE, ReconstructionStage.SURFACE, ReconstructionStage.TEXTURE)))
            assertFalse(started.contains(ReconstructionStage.PUBLISHING))
            assertTrue(progress.isNotEmpty())
            val ready = featureWork.filter { it.phase == FeatureWorkPhase.READY }
            assertEquals(setOf(1, 2, 3), ready.map { it.photoNumber }.toSet())
            ready.forEach { assertEquals(false, it.cacheHit); assertNotNull(it.nativeStartedAfterMillis); assertNotNull(it.nativeFinishedAfterMillis) }
            assertNotNull(OpaqueBitmapBridge.pixels)
            fun assertCache() {
                val restored = requireNotNull(SparseAlignmentCache(directory).load(plan))
                val outcome = restored.geometry.optString("finalSparseOutcome", "not_reported")
                // Print real receipts before strict assertions; fallback is a failure for THIS case.
                println("HOST_FULL_CORE outcome=$outcome bundleAdjustment=${restored.geometry.has("bundleAdjustment")} points=${restored.geometry.getJSONArray("points").length()} cameras=${restored.checkpoint.cameras.size}")
                println("HOST_FULL_CORE refinementFailure=${restored.geometry.optString("finalSparseFailure", "none")} cameraFailures=${restored.geometry.opt("cameraRegistrationFailures")}")
                println("HOST_FULL_CORE incrementalRegistration=${restored.geometry.opt("incrementalRegistration")} incrementalFailure=${restored.geometry.optString("incrementalRegistrationFailure", "none")}")
                assertEquals("global_typed_ba_admitted12", outcome)
                assertTrue(restored.geometry.has("bundleAdjustment"))
                assertEquals(3, restored.checkpoint.cameras.size)
                assertEquals(input.photos.map { it.id }.toSet(), restored.checkpoint.cameras.map { it.photoId }.toSet())
                assertTrue(restored.geometry.getJSONArray("points").length() > 0)
                assertTrue(restored.checkpoint.tracks.isNotEmpty())
                assertEquals(binding, restored.checkpoint.binding)
            }
            fun assertArtifacts() {
                val key = result.getString("artifactKey") // exact coordinator key; never inject metadata
                assertTrue(PreviewSchemaValidator.validate(result, revision, key) is ReopenResult.Ready)
                val persistedJson = File(directory, "$key.json")
                require(persistedJson.length() in 1..PreviewSchemaValidator.MAX_BYTES.toLong())
                val reopened = JSONObject(persistedJson.readText())
                assertTrue(PreviewSchemaValidator.validate(reopened, revision, key) is ReopenResult.Ready)
                assertEquals(result.toString(), reopened.toString())
                val stages = CacheStageManifestStore(directory).read()
                val texture = stages.single { it.binding == binding && it.identity.stage == ArtifactStage.TEXTURE }
                val closure = PipelineManifestReader(directory).verify(texture)
                assertTrue(closure.keys.containsAll(listOf(ArtifactStage.FEATURES, ArtifactStage.MATCHES,
                    ArtifactStage.ALIGNMENT, ArtifactStage.DENSE_DEPTH, ArtifactStage.SURFACE, ArtifactStage.TEXTURE)))
                assertNotNull(PipelineSurfaceCache(directory).load(plan, result))
                fun artifact(extension: String) = File(directory, "$key.$extension").also {
                    require(it.isFile && it.length() in 1..(32L * 1024 * 1024))
                }
                assertEquals(0, GlbStructure.validate(artifact("glb"), 0).primitiveMode)
                assertEquals(0, GlbStructure.validate(artifact("dense.glb"), 0).primitiveMode)
                assertEquals(4, GlbStructure.validate(artifact("mesh.glb"), 4).primitiveMode)
                LegacyPlyValidation.validate(artifact("ply"), false)
                LegacyPlyValidation.validate(artifact("mesh.ply"), true)
                validateDensePly(artifact("dense.ply"), result.getJSONObject("densePreview").getInt("pointCount"))
                val photographic = PhotographicGlb.read(artifact("textured.glb"))
                val atlas = artifact("texture.png")
                GlbStructure.validatePng(atlas)
                assertArrayEquals(photographic.png, atlas.readBytes())
                assertTrue(photographic.indices.isNotEmpty())
                assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(key) && it.name.endsWith(".partial") })
            }
            assertCache()
            assertArtifacts()
            fixture.assertOriginalsAndNoResult()
            fixture.reopen()
            assertCache()
            assertArtifacts()
            val persisted = requireNotNull(RoomJobStore(fixture.database, fixture.database.jobDao()).snapshot(job.jobId))
            assertEquals(ReconstructionStage.TEXTURE, persisted.cursor.acceptedStage)
            assertEquals(com.realityforge.scanner.data.jobs.ReconstructionJobState.PAUSED, persisted.state)
            fixture.assertOriginalsAndNoResult()
            // Evidence-only copy AFTER all original assertions; no truth or altered metadata enters solver.
            val capture=File(requireNotNull(System.getProperty("rf.quality.output"))).canonicalFile
            require(!capture.exists() && !capture.toPath().startsWith(directory.canonicalFile.toPath()))
            check(capture.mkdir())
            val key=result.getString("artifactKey")
            val entries=JSONObject()
            for((extension,name) in listOf("json" to "result.json","ply" to "sparse.ply",
                "dense.ply" to "dense.ply","mesh.ply" to "mesh.ply")) {
                val source=File(directory,"$key.$extension")
                require(source.isFile && source.length() in 1..(32L*1024*1024))
                val before=sha(source);val target=File(capture,name)
                source.copyTo(target,overwrite=false)
                assertEquals(source.length(),target.length());assertEquals(before,sha(target));assertEquals(before,sha(source))
                entries.put(name,JSONObject().put("sha256",before).put("bytes",target.length()))
            }
            val identities=org.json.JSONArray()
            fixture.database.dao().photos(fixture.project).forEach { photo ->
                identities.put(JSONObject().put("photoId",photo.id).put("sha256",photo.sha256))
            }
            File(capture,"capture.json").writeText(JSONObject().put("schema",1).put("synthetic",true)
                .put("artifacts",entries).put("photos",identities).put("artifactKey",key)
                .put("scope","post-return evidence only; not solver input").toString())
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
    /** Test-only reader for the exact dense point writer; never relax the production sparse validator. */
    private fun validateDensePly(file: File, expectedCount: Int) {
        require(expectedCount in 1..2_000_000 && file.length() in 1..(32L * 1024 * 1024))
        file.inputStream().buffered().use { input ->
            fun line(): String {
                val text = StringBuilder()
                while (true) {
                    val byte = input.read()
                    require(byte >= 0) { "Truncated dense PLY" }
                    if (byte == 10) return text.toString()
                    require(byte in 32..126 && text.length < 1024) { "Invalid/oversized dense PLY line" }
                    text.append(byte.toChar())
                }
            }
            val header = listOf("ply", "format ascii 1.0",
                "comment RealityForge dense_points; arbitrary scale; x-right y-down z-forward",
                "comment representation dense_points", "element vertex $expectedCount",
                "property float x", "property float y", "property float z",
                "property uchar red", "property uchar green", "property uchar blue", "end_header")
            header.forEach { assertEquals(it, line()) }
            repeat(expectedCount) {
                val cells = line().split(' ')
                require(cells.size == 6)
                for (column in 0..2) {
                    val value = cells[column].toDouble()
                    require(value.isFinite() && value.toFloat().isFinite())
                }
                for (column in 3..5) require(cells[column].toInt() in 0..255)
            }
            assertEquals("Trailing dense PLY data", -1, input.read())
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
            project = repository.create("Synthetic relief three-photo coordinator prefix")
            val calibration = File(fixtureDir, "calibration.json")
            assertEquals("284c73d64c92bbed50f762a5bc74b6f39d0a3955a0bd219217b6d23bbd7fd593", sha(calibration))
            val optics = JSONObject(calibration.readText())
            assertTrue(optics.getBoolean("synthetic"))
            assertEquals(800, optics.getInt("width")); assertEquals(600, optics.getInt("height"))
            val cameras = optics.getJSONArray("cameras")
            for (i in 0..2) {
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
            assertEquals(3, result.importedCount)
            assertEquals(0, result.rejectedCount)
            assertEquals(3L, result.finalRevision)
            assertOriginalsAndNoResult()
        }
        suspend fun assertOriginalsAndNoResult() {
            val photos = database.dao().photos(project)
            assertEquals(3, photos.size)
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
            assertEquals(3L, record.revision); assertNull(record.resultRevision); assertNull(record.resultKey)
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
            "view-03.jpg" to "a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f",
        )
        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
