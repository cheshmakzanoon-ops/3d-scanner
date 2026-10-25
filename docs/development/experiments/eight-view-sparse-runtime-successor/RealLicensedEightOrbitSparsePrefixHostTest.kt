package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.cachegc.CacheStageManifestStore
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
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
        var captureFailureCaches: (suspend () -> Unit)? = null
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
            captureFailureCaches = {
                val diagnosticContext = currentCoroutineContext()
                fun diagnosticCheck() {
                    diagnosticContext.ensureActive()
                    check(System.nanoTime() - experimentStartedNanos < 90_000_000_000L)
                }
                diagnosticCheck()
                check(worker?.isCompleted == true && !File(evidence, "sparse-result.json").exists())
                check(accepted.toList() == listOf(ReconstructionStage.PREPARED, ReconstructionStage.FEATURES, ReconstructionStage.MATCHES))
                check(started.none { it.order >= ReconstructionStage.SPARSE.order })
                fixture.assertOriginalsAndNoResult()
                FailureCacheSummary(directory, ::diagnosticCheck).capture(fixture, input, snapshot, binding, pairWork.toList(), evidence)
            }
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
            // Original worker has already unwound; diagnostic failure never replaces its failure.
            if (worker?.isCompleted == true) try { captureFailureCaches?.invoke() }
            catch (diagnostic: Throwable) {
                if (diagnostic !== failure) failure.addSuppressed(IllegalStateException("CACHE_SUMMARY_UNAVAILABLE"))
            }
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
    /** Counts only from this failed invocation's exact, complete production caches. No native replay.
     * The reader never calls FeatureCache.load (which mutates mtime), save, extraction or matching.
     * All descriptor arrays are decoded one photo at a time; only XY/projection/summary stay live.
     */
    private class FailureCacheSummary(private val directory: File, private val checkActive: () -> Unit) {
        private data class UnixMetadata(val uid: Int, val gid: Int, val mode: Int,
            val changeTime: java.nio.file.attribute.FileTime)
        private data class Stamp(val bytes: Long, val modified: java.nio.file.attribute.FileTime,
            val created: java.nio.file.attribute.FileTime, val unix: UnixMetadata,
            val key: Any, val digest: String, val accessBefore: java.nio.file.attribute.FileTime,
            val accessAfter: java.nio.file.attribute.FileTime) {
            fun sameProtectedMetadata(other: Stamp): Boolean =
                copy(accessBefore = other.accessBefore, accessAfter = other.accessAfter) == other
        }
        private data class Watched(val role: String, val index: Int, val file: File, val maximum: Int, val before: Stamp)
        private data class Frame(val width: Int, val height: Int, val xy: FloatArray, val count: Int,
            val projection: LensProjection)
        private val watched = mutableListOf<Watched>()
        private val reader = PipelineManifestReader(directory)
        private fun unixMetadata(file: File): UnixMetadata {
            require("unix" in file.toPath().fileSystem.supportedFileAttributeViews())
            val values = java.nio.file.Files.readAttributes(file.toPath(), "unix:uid,gid,mode,ctime",
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            return UnixMetadata(values.getValue("uid") as Int, values.getValue("gid") as Int,
                values.getValue("mode") as Int, values.getValue("ctime") as java.nio.file.attribute.FileTime)
        }
        private fun stamp(file: File, maximum: Int): Stamp {
            checkActive()
            require(file.canonicalFile.toPath() == file.absoluteFile.toPath().normalize())
            PipelineArtifactSession.relativePath(directory, file)
            val before = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(before.isRegularFile && before.size() in 1..maximum.toLong())
            val unixBefore = unixMetadata(file)
            val key = requireNotNull(before.fileKey())
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            file.inputStream().use { stream ->
                val block = ByteArray(65536)
                while (true) {
                    checkActive(); val size = stream.read(block); if (size < 0) break
                    count += size; require(count <= maximum); digest.update(block, 0, size)
                }
            }
            val after = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(after.isRegularFile && count == before.size() && after.size() == count &&
                after.fileKey() == key && after.lastModifiedTime() == before.lastModifiedTime() &&
                after.creationTime() == before.creationTime() && unixMetadata(file) == unixBefore)
            // Read-triggered OS atime is observed separately; never restored or treated as a cache write.
            return Stamp(count, before.lastModifiedTime(), before.creationTime(), unixBefore, key,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                before.lastAccessTime(), after.lastAccessTime())
        }
        private fun watch(role: String, index: Int, file: File, maximum: Int) {
            require(watched.none { it.file == file || it.role == role && it.index == index })
            watched += Watched(role, index, file, maximum, stamp(file, maximum))
        }
        suspend fun capture(fixture: Fixture, input: ProjectInputSnapshot, snapshot: PipelineInputSnapshot,
            binding: ArtifactBinding, events: List<com.realityforge.scanner.engine.matching.PairWorkSnapshot>, evidence: File) {
            checkActive()
            require(!File(evidence, "sparse-result.json").exists())
            val current = fixture.repository.inputSnapshot(fixture.project)
            require(current.revision == input.revision && current.photos.map { it.id } == input.photos.map { it.id } &&
                InputPhotoIdentity.snapshotSha256(current) == InputPhotoIdentity.snapshotSha256(input))
            require(input.photos.size == 8 && input.photos.map { it.sha256 }.toSet() == HASHES.values.toSet())
            require(!LensCalibration.file(fixture.repository, fixture.project).exists())
            require(ProductionPipelineSnapshot.create(current,
                ProductionPipelineSnapshot.calibrationSha256(LensCalibration.file(fixture.repository, fixture.project)),
                88.0, ResourceWorkloadSelection.COMPACT) == snapshot)
            val plan = PipelineArtifactSession.identityPlan(snapshot, binding)
            val manifestDirectory = PipelineArtifactSession.manifestFile(directory, plan.snapshot.recordHash).parentFile
            require(manifestDirectory.canonicalFile.toPath() == manifestDirectory.absoluteFile.toPath().normalize())
            require(java.nio.file.Files.isDirectory(manifestDirectory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val manifestFiles = java.nio.file.Files.newDirectoryStream(manifestDirectory.toPath()).use { stream ->
                stream.asSequence().take(4).map { it.toFile() }.toList().sortedBy { it.name }
            }
            require(manifestFiles.size in 1..3 && manifestFiles.all { it.name.matches(Regex("[0-9a-f]{64}\\.rfsm")) })
            manifestFiles.forEachIndexed { index, file -> watch("MANIFEST", index, file,
                com.realityforge.scanner.engine.artifacts.StageManifestCodec.MAX_MANIFEST_BYTES) }
            val manifests = CacheStageManifestStore(directory).read()
            checkActive()
            require(manifests.size == manifestFiles.size)
            require(manifests.all { it.binding == binding && it.inputSnapshotRecordHash == plan.snapshot.recordHash &&
                it.completion == com.realityforge.scanner.engine.artifacts.CompletionState.COMPLETE &&
                it.identity.stage in setOf(ArtifactStage.FEATURES, ArtifactStage.MATCHES) &&
                it.identity.identityHash == plan[it.identity.stage].identityHash })
            val matching = manifests.single { it.identity.stage == ArtifactStage.MATCHES }
            val featuresManifest = manifests.single { it.identity.stage == ArtifactStage.FEATURES }
            require(manifests.size == 2)
            watch("INPUT_RECORD", 0, PipelineArtifactSession.inputFile(directory, plan.snapshot.recordHash), 2 * 1024 * 1024)
            // Resolve only already-bound manifest artifacts. Cache constructors/path helpers can
            // mkdirs on a missing root and therefore are forbidden in this read-only diagnostic.
            require(featuresManifest.artifacts.size == 8 && featuresManifest.artifacts.all {
                it.representation == com.realityforge.scanner.engine.artifacts.RepresentationKind.FEATURE_SET })
            val featureFiles = featuresManifest.artifacts.map { File(directory, it.relativePath) }
            require(featureFiles.toSet().size == 8)
            featureFiles.forEachIndexed { index, file -> watch("FEATURE", index, file,
                com.realityforge.scanner.engine.features.FeatureCacheFormat.MAX_BYTES) }
            require(watched.filter { it.role == "FEATURE" }.sumOf { it.before.bytes } <=
                com.realityforge.scanner.engine.features.SparseFeatureCache.BYTE_BUDGET)
            val matchCache = FeatureMatchCache(directory, snapshot.identity(PipelineStage.MATCHING), 8)
            val checksum = File(directory, matchCache.file.name + ".sha256")
            watch("MATCHES", 0, matchCache.file, 128 + 28 * (12 + 4000 * 64))
            watch("MATCHES_CHECKSUM", 0, checksum, 64)
            require(featuresManifest.artifacts.map { it.relativePath }.toSet() == featureFiles.map {
                PipelineArtifactSession.relativePath(directory, it) }.toSet())
            require(matching.artifacts.map { it.relativePath }.toSet() == setOf(matchCache.file, checksum).map {
                PipelineArtifactSession.relativePath(directory, it) }.toSet())
            checkActive()
            val verified = reader.verify(matching)
            require(verified.keys == setOf(ArtifactStage.FEATURES, ArtifactStage.MATCHES))
            require(reader.input(plan.snapshot.recordHash) == plan.snapshot)
            checkActive()
            val framesByPhoto = linkedMapOf<String, Frame>()
            val summariesByPhoto = linkedMapOf<String, com.realityforge.scanner.engine.matching.PhotoFeatureSummary>()
            val featureFileIndices = linkedMapOf<File, Int>()
            // At most 8*8 bounded production decodes. A wrong identity rejects before arrays
            // are decoded; only one actual descriptor set is retained during each iteration.
            featureFiles.forEach { file ->
                checkActive()
                val bytes = reader.readBounded(file, com.realityforge.scanner.engine.features.FeatureCacheFormat.MAX_BYTES)
                var selected: com.realityforge.scanner.engine.features.CachedFeatures? = null
                var selectedIndex = -1
                input.photos.forEachIndexed { index, photo ->
                    checkActive()
                    val candidate = try { com.realityforge.scanner.engine.features.FeatureCacheFormat.decode(bytes,
                        com.realityforge.scanner.engine.features.SparseFeatureCache.identity(photo.sha256)) }
                    catch (_: IllegalArgumentException) { null }
                    if (candidate != null) {
                        require(selected == null)
                        selected = candidate; selectedIndex = index
                    }
                }
                val decoded = requireNotNull(selected)
                require(selectedIndex in input.photos.indices)
                val photo = input.photos[selectedIndex]
                require(photo.id !in framesByPhoto && selectedIndex !in featureFileIndices.values)
                require(decoded.width in 3..1600 && decoded.height in 3..1600 && decoded.count in 0..4000)
                val projection = LensProjection.estimated(decoded.width, decoded.height,
                    ImageLoader.focalPixels(decoded.width, decoded.height, photo.focal35mm ?: 88.0))
                val descriptors = decoded.descriptors()
                summariesByPhoto[photo.id] = com.realityforge.scanner.engine.matching.PhotoFeatureSummary.siftMean(checkedPhotoId(photo.id), descriptors)
                framesByPhoto[photo.id] = Frame(decoded.width, decoded.height, decoded.coordinates(), decoded.count, projection)
                featureFileIndices[file] = selectedIndex
            }
            require(framesByPhoto.keys == input.photos.map { it.id }.toSet() &&
                featureFileIndices.values.toSet() == (0..7).toSet())
            val frames = input.photos.map { framesByPhoto.getValue(it.id) }
            val summaries = input.photos.map { summariesByPhoto.getValue(it.id) }
            val featureEvidence = org.json.JSONArray(input.photos.mapIndexed { index, photo ->
                val frame = frames[index]
                JSONObject().put("photoId", checkedPhotoId(photo.id)).put("imageSha256", photo.sha256)
                    .put("width", frame.width).put("height", frame.height).put("featureCount", frame.count)
            })
            val matches = requireNotNull(matchCache.load(checkActive))
            require(matches.keys == (0 until 8).flatMap { a -> (a + 1 until 8).map { b -> a to b } }.toSet())
            val candidates = com.realityforge.scanner.engine.matching.AdmittedPairPlan.build(summaries, checkActive)
            val indices = input.photos.withIndex().associate { it.value.id to it.index }
            val ready = events.filter { it.phase == com.realityforge.scanner.engine.matching.PairWorkPhase.READY }
            require(ready.size == 28 && ready.map { it.pairNumber } == (1..28).toList() && events.all { it.pairCount == 28 })
            val pairEvidence = org.json.JSONArray()
            candidates.pairs.forEachIndexed { index, candidate ->
                checkActive()
                val ai = indices.getValue(candidate.firstPhotoId); val bi = indices.getValue(candidate.secondPhotoId)
                val a = minOf(ai, bi); val b = maxOf(ai, bi)
                val first = frames[a]; val second = frames[b]; val record = matches.getValue(a to b)
                val event = ready[index]
                require(event.firstDescriptorRows == first.count && event.secondDescriptorRows == second.count &&
                    event.geometryEntered == (record.firstKeys.size >= 20) && event.matchCacheHit == false)
                val calibrated = CalibratedInitializerInput.create(first.projection, first.width, first.height, first.xy, record.firstKeys,
                    second.projection, second.width, second.height, second.xy, record.secondKeys)
                val distinctKeys = HashSet<Pair<Int, Int>>()
                val distinctCoordinates = HashSet<List<Double>>()
                val distinctRays = HashSet<List<Double>>()
                record.firstKeys.indices.forEach { row ->
                    if (row % 128 == 0) checkActive()
                    val x = calibrated.observed[row * 4]; val y = calibrated.observed[row * 4 + 1]
                    val u = calibrated.observed[row * 4 + 2]; val v = calibrated.observed[row * 4 + 3]
                    val ar = first.projection.normalized(x, y); val br = second.projection.normalized(u, v)
                    require(record.rows[row * 7] == ar.first && record.rows[row * 7 + 1] == ar.second &&
                        record.rows[row * 7 + 2] == br.first && record.rows[row * 7 + 3] == br.second)
                    distinctKeys += record.firstKeys[row] to record.secondKeys[row]
                    // Exact numeric equality, canonicalizing signed zero; never a near-duplicate tolerance.
                    distinctCoordinates += listOf(x, y, u, v).map { if (it == 0.0) 0.0 else it }
                    distinctRays += (0..3).map { record.rows[row * 7 + it].let { value -> if (value == 0.0) 0.0 else value } }
                }
                require(distinctKeys.size == record.firstKeys.size)
                pairEvidence.put(JSONObject().put("ordinal", index + 1).put("firstPhotoId", checkedPhotoId(input.photos[a].id))
                    .put("secondPhotoId", checkedPhotoId(input.photos[b].id)).put("firstDescriptorRows", first.count)
                    .put("secondDescriptorRows", second.count).put("reciprocalMatchCount", record.firstKeys.size)
                    .put("distinctKeyPairCount", distinctKeys.size).put("distinctCoordinatePairCount", distinctCoordinates.size)
                    .put("distinctNormalizedRayPairCount", distinctRays.size)
                    .put("geometryEntered", event.geometryEntered))
            }
            val proofs = org.json.JSONArray()
            watched.map { item -> if (item.role == "FEATURE") item.copy(index = featureFileIndices.getValue(item.file)) else item }
                .sortedWith(compareBy<Watched> { it.role }.thenBy { it.index }).forEach { item ->
                val after = stamp(item.file, item.maximum); require(after.sameProtectedMetadata(item.before))
                val accessTimeChanged = item.before.accessBefore != item.before.accessAfter ||
                    item.before.accessAfter != after.accessBefore || after.accessBefore != after.accessAfter
                proofs.put(JSONObject().put("role", item.role).put("index", item.index).put("bytes", after.bytes)
                    .put("beforeSha256", item.before.digest).put("afterSha256", after.digest)
                    .put("accessTimeChanged", accessTimeChanged))
            }
            require(manifestFiles == java.nio.file.Files.newDirectoryStream(manifestDirectory.toPath()).use { stream ->
                stream.asSequence().take(4).map { it.toFile() }.toList().sortedBy { it.name } })
            val afterInput = fixture.repository.inputSnapshot(fixture.project)
            require(afterInput.revision == input.revision && InputPhotoIdentity.snapshotSha256(afterInput) == InputPhotoIdentity.snapshotSha256(input))
            require(!LensCalibration.file(fixture.repository, fixture.project).exists())
            fixture.assertOriginalsAndNoResult()
            checkActive()
            val result = JSONObject().put("schema", 1).put("scope", "FAILED_INITIALIZER_CACHE_DIAGNOSTIC")
                .put("snapshotRecordSha256", plan.snapshot.recordHash.hex)
                .put("matchingStageSha256", plan[ArtifactStage.MATCHES].identityHash.hex)
                .put("coordinatePairDomain", "OBSERVED_PIXEL_QUADRUPLE_EXACT")
                .put("metadataPolicy", "CONTENT_IDENTITY_SIZE_CREATION_MODIFIED_UNIX_OWNER_MODE_CHANGE_TIME")
                .put("cacheProofs", proofs).put("features", featureEvidence).put("pairs", pairEvidence)
                .put("originalNativeCategory", "NOT_RETAINED")
                .put("replay", JSONObject().put("status", "NOT_RUN").put("reason", "REPLAY_UNAVAILABLE"))
            val bytes = result.toString(2).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32 * 1024)
            checkActive()
            captureImmutable(evidence, "cache-pair-summary.json", bytes)
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
