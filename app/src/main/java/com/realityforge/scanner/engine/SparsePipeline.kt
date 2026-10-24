package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.features.FeatureCancellationSignal
import com.realityforge.scanner.engine.matching.PairWorkSnapshot
import com.realityforge.scanner.engine.matching.PairWorkTiming
import com.realityforge.scanner.engine.matching.PairWorkPhase
import com.realityforge.scanner.engine.matching.preservePairFailure
import com.realityforge.scanner.engine.matching.PairCancellationSignal
import com.realityforge.scanner.engine.features.FeatureWorkPhase
import com.realityforge.scanner.engine.features.FeatureWorkSnapshot
import com.realityforge.scanner.engine.features.FeatureWorkTiming
import com.realityforge.scanner.engine.features.NativeFeatureCallEvent
import com.realityforge.scanner.engine.features.FeatureExtractor
import com.realityforge.scanner.engine.features.FeatureFrame
import com.realityforge.scanner.engine.matching.FeatureMatcher
import com.realityforge.scanner.engine.matching.AdmittedPairPlan
import com.realityforge.scanner.engine.matching.PhotoFeatureSummary
import com.realityforge.scanner.engine.matching.PairCandidate
import com.realityforge.scanner.imaging.AndroidImageDecoder

import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.features.SparseFeatureCache
import com.realityforge.scanner.engine.pipeline.SparseAlignmentCache
import com.realityforge.scanner.engine.pipeline.PipelineArtifactSession
import com.realityforge.scanner.engine.pipeline.PipelineCheckpointSink
import com.realityforge.scanner.engine.pipeline.PipelineStageFile
import com.realityforge.scanner.engine.pipeline.FeatureMatchCache
import com.realityforge.scanner.engine.pipeline.FeatureMatchRecord
import com.realityforge.scanner.engine.pipeline.MeasuredAlignmentCheckpoint
import com.realityforge.scanner.engine.artifacts.AlignmentCamera
import com.realityforge.scanner.engine.artifacts.TrackObservation
import com.realityforge.scanner.engine.artifacts.RepresentationKind
import com.realityforge.scanner.engine.artifacts.PipelineStage as ArtifactStage
import com.realityforge.scanner.engine.pipeline.PipelineAdapterTrace
import com.realityforge.scanner.engine.pipeline.PipelineCapacityPolicy
import com.realityforge.scanner.engine.pipeline.PipelineCapacityProfile
import com.realityforge.scanner.engine.pipeline.PipelineInputSnapshot
import com.realityforge.scanner.engine.pipeline.PipelinePhotoInput
import com.realityforge.scanner.engine.pipeline.PipelineStage
import com.realityforge.scanner.engine.pipeline.PipelineStageIdentity
import com.realityforge.scanner.engine.pipeline.PipelineStageOutcome
import com.realityforge.scanner.engine.pipeline.PipelineStageSettings
import com.realityforge.scanner.engine.pipeline.sha256Utf8
import com.realityforge.scanner.engine.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** M08: calibrated joint SfM, bounded CPU photometric depth and conservative open surface meshing. */
class SparsePipeline(
    private val repository: ProjectRepository,
    private val resourceSelection: com.realityforge.scanner.processing.resources.ResourceWorkloadSelection =
        com.realityforge.scanner.processing.resources.ResourceWorkloadSelection.COMPACT,
    private val artifactBinding: com.realityforge.scanner.engine.artifacts.ArtifactBinding? = null,
    private val checkpoints: PipelineCheckpointSink = PipelineCheckpointSink {},
    private val executionProfile: com.realityforge.scanner.engine.pipeline.DensePreviewProfile =
        com.realityforge.scanner.engine.pipeline.DensePreviewProfile.Compatibility,
) {
    data class Progress(val stage: String, val completed: Int, val total: Int,
        val featureWork: FeatureWorkSnapshot? = null, val pairWork: PairWorkSnapshot? = null)
    internal var onPairWork: (PairWorkSnapshot) -> Unit = {}
    internal var onFeatureWork: (FeatureWorkSnapshot) -> Unit = {}
    internal var diagnosticRecorder: RuntimeDiagnosticRecorder? = null
    internal var onDiagnosticProgress: suspend (StageProgress) -> Unit = {}
    internal var activeDiagnosticStage: ScanStage = ScanStage.IMPORT
        private set

    private suspend fun measured(stage: ScanStage, completed: Int, total: Int?, unit: ProgressUnit,
        reusedCheckpoint: Boolean = false) {
        activeDiagnosticStage = stage
        diagnosticRecorder?.progress(stage, completed.toLong(), total?.toLong(), unit, reusedCheckpoint)
            ?.let { onDiagnosticProgress(it) }
    }

    private fun outcome(stage: ScanStage, accepted: Boolean, code: ScanFailureCode,
        action: RecoveryAction, messageKey: String, unavailable: StageOutcome = StageOutcome.PARTIAL) {
        activeDiagnosticStage = stage
        val state = if (accepted) StageOutcome.SUCCEEDED else unavailable
        diagnosticRecorder?.outcome(stage, state, if (accepted) null else ScanFailure(code, stage, state, action, messageKey))
    }
    private fun reject(stage: ScanStage, code: ScanFailureCode, action: RecoveryAction, messageKey: String): Nothing {
        activeDiagnosticStage = stage
        throw DiagnosticOperationException(ScanFailure(code, stage, StageOutcome.FAILED, action, messageKey))
    }
    private data class PairMatches(val rows: DoubleArray, val firstKeys: IntArray, val secondKeys: IntArray) {
        val size: Int get() = firstKeys.size
        fun destinationBySource(): Map<Int, Int> = firstKeys.indices.associate { firstKeys[it] to secondKeys[it] }
    }
    private class Frame(val features: FeatureFrame, val cacheHit: Boolean = false) : Closeable {
        val width = features.width
        val height = features.height
        val xy = features.coordinates()
        val colors = features.colors()
        val projection get() = features.projection
        override fun close() = features.close()
    }
    private val extractor = FeatureExtractor()
    private val matcher = FeatureMatcher()
    private val decoderIdentity = "${AndroidImageDecoder.POLICY_IDENTITY}|analysisEdge=${extractor.settings.analysisEdge}"
    private var activeProfile: LensProfile? = null
    private var fallbackFocal35 = 26.0
    private fun lens(photo: ScanPhoto, width: Int, height: Int): LensProjection =
        activeProfile?.forScan(width, height, photo.focal35mm) ?:
            LensProjection.estimated(width, height,
                ImageLoader.focalPixels(width, height, photo.focal35mm ?: fallbackFocal35))

    suspend fun run(projectId: String, assumedFocal35: Double, nativeToken: Long, progress: suspend (Progress) -> Unit): JSONObject = withContext(Dispatchers.Default) {
        if (assumedFocal35 !in 10.0..200.0) reject(ScanStage.CALIBRATION, ScanFailureCode.INPUT_UNSUPPORTED,
            RecoveryAction.REVIEW_INPUTS, "scan.failure.focal_length_unsupported")
        if (!OpenCVLoader.initLocal()) reject(ScanStage.FEATURE_EXTRACTION, ScanFailureCode.NATIVE_LIBRARY_UNAVAILABLE,
            RecoveryAction.RETRY_JOB, "scan.failure.native_library_unavailable")
        Core.setNumThreads(resourceSelection.nativeThreads)
        val project = requireNotNull(repository.dao.project(projectId))
        diagnosticRecorder?.scope?.let { scope ->
            val bound = requireNotNull(artifactBinding)
            require(scope.sourceRevision == project.revision && bound.jobId == scope.jobId &&
                bound.attempt == scope.attempt.toLong()) { "Diagnostic scope differs from the executing pipeline." }
        }
        val selected = repository.inputSnapshot(projectId)
        val indexed = repository.dao.photos(projectId).associateBy { it.id }
        val photos = selected.photos.map { expected ->
            val photo = indexed[expected.id]
            if (photo == null || !photo.included || photo.sha256 != expected.sha256)
                reject(ScanStage.IMPORT, ScanFailureCode.INPUT_CHANGED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_changed")
            photo
        }
        artifactBinding?.let { check(it.projectId == projectId && it.projectRevision == project.revision) }
        if (photos.size < 2) reject(ScanStage.IMPORT, ScanFailureCode.INSUFFICIENT_OVERLAP,
            RecoveryAction.RECAPTURE_OVERLAP, "scan.failure.insufficient_photographs")
        // Never silently discard photos. Larger product target profiles are routed through the
        // same admission point and rejected explicitly until their combined resource/device gates pass.
        val capacityProfile = PipelineCapacityPolicy.requireSupported(photos.size)
        measured(ScanStage.IMPORT, 0, photos.size, ProgressUnit.PHOTOS)
        for ((index, photo) in photos.withIndex()) {
            coroutineContext.ensureActive()
            val original = repository.photoFile(photo)
            if (!original.isFile || original.length() != photo.bytes)
                reject(ScanStage.IMPORT, ScanFailureCode.INPUT_CORRUPT, RecoveryAction.REVIEW_INPUTS, "scan.failure.original_missing_or_changed")
            val actual = original.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                var consumed = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    coroutineContext.ensureActive()
                    if (count.toLong() > photo.bytes - consumed)
                        reject(ScanStage.IMPORT, ScanFailureCode.INPUT_CHANGED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_changed")
                    consumed += count
                    digest.update(buffer, 0, count)
                }
                if (consumed != photo.bytes)
                    reject(ScanStage.IMPORT, ScanFailureCode.INPUT_CHANGED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_changed")
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if (actual != photo.sha256)
                reject(ScanStage.IMPORT, ScanFailureCode.INPUT_CORRUPT, RecoveryAction.REVIEW_INPUTS, "scan.failure.original_checksum_changed")
            measured(ScanStage.IMPORT, index + 1, photos.size, ProgressUnit.PHOTOS)
        }
        diagnosticRecorder?.outcome(ScanStage.IMPORT, StageOutcome.SUCCEEDED)
        val directory = repository.directory(projectId)
        val revision = project.revision
        activeDiagnosticStage = ScanStage.CALIBRATION
        val calibrationRecord = try { LensCalibration.readRecord(repository, projectId) }
        catch (invalid: Exception) {
            if (invalid !is IllegalArgumentException && invalid !is org.json.JSONException) throw invalid
            reject(ScanStage.CALIBRATION, ScanFailureCode.CALIBRATION_UNAVAILABLE,
                RecoveryAction.RECALIBRATE, "scan.failure.calibration_profile_invalid")
        }
        val calibrationCompatibility = JSONArray()
        calibrationRecord?.let { record ->
            measured(ScanStage.CALIBRATION, 0, photos.size, ProgressUnit.PHOTOS)
            for ((index, photo) in photos.withIndex()) {
                val metadata = try { photo.captureMetadataRecord?.let {
                    com.realityforge.scanner.capture.metadata.CaptureMetadataCodec.decode(it.toByteArray(Charsets.UTF_8))
                } } catch (invalid: Exception) {
                    if (invalid !is IllegalArgumentException && invalid !is org.json.JSONException) throw invalid
                    reject(ScanStage.CALIBRATION, ScanFailureCode.INPUT_CORRUPT,
                        RecoveryAction.REVIEW_INPUTS, "scan.failure.optical_metadata_corrupt")
                }
                if (metadata != null && photo.opticalIdentitySha256 != null &&
                    photo.opticalIdentitySha256 != com.realityforge.scanner.capture.metadata.CaptureMetadataCodec.opticalIdentitySha256(metadata))
                    reject(ScanStage.CALIBRATION, ScanFailureCode.INPUT_CORRUPT,
                        RecoveryAction.REVIEW_INPUTS, "scan.failure.optical_metadata_corrupt")
                // Stored photo dimensions describe encoded pixels, not the EXIF-upright image.
                // Inspect the actual original with the decoder's policy; never infer geometry from
                // merged metadata or allocate a full image merely for calibration preflight.
                coroutineContext.ensureActive()
                val inspection = AndroidImageDecoder.inspectUpright(
                    com.realityforge.scanner.imaging.DecodeRequest(repository.photoFile(photo)))
                coroutineContext.ensureActive()
                val capture = com.realityforge.scanner.capture.calibration.CalibrationCaptureMetadataAdapter
                    .forInspectedScan(metadata, inspection, photo.focal35mm)
                val compatible = com.realityforge.scanner.capture.calibration.CalibrationProfileCompatibility.evaluate(record, capture)
                if (compatible.status == com.realityforge.scanner.capture.calibration.CalibrationCompatibilityStatus.INCOMPATIBLE)
                    reject(ScanStage.CALIBRATION, ScanFailureCode.CALIBRATION_UNAVAILABLE,
                        RecoveryAction.RECALIBRATE, "scan.failure.calibration_incompatible")
                calibrationCompatibility.put(JSONObject().put("photoId", photo.id).put("status", compatible.status.name)
                    .put("reasons", JSONArray(compatible.reasons.map { it.name })))
                measured(ScanStage.CALIBRATION, index + 1, photos.size, ProgressUnit.PHOTOS)
            }
        }
        diagnosticRecorder?.outcome(ScanStage.CALIBRATION,
            if (calibrationRecord == null) StageOutcome.NOT_RUN else StageOutcome.SUCCEEDED)
        activeProfile = calibrationRecord?.profile
        fallbackFocal35 = assumedFocal35
        val calibrationFile = LensCalibration.file(repository, projectId)
        val pipelineSnapshot = com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot.create(
            selected, com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot.calibrationSha256(calibrationFile),
            assumedFocal35, resourceSelection, executionProfile)
        val pipelineTrace = PipelineAdapterTrace(pipelineSnapshot)
        val fingerprint = pipelineSnapshot.identity(PipelineStage.SPARSE).digest
        val artifactKey = "sparse-m16-r$revision-${sha256Utf8(fingerprint + "|" + java.util.UUID.randomUUID()).take(16)}"
        val artifacts = PipelineArtifactSession(directory, pipelineSnapshot,
            artifactBinding ?: com.realityforge.scanner.engine.artifacts.ArtifactBinding(projectId, revision,
                "local-${java.util.UUID.randomUUID()}", 1), checkpoints)
        activeDiagnosticStage = ScanStage.STORAGE
        artifacts.prepared()
        val alignmentCache = SparseAlignmentCache(directory)
        val featureWorkSamples = ArrayList<FeatureWorkSnapshot>(photos.size)
        val cachedAlignment = alignmentCache.load(artifacts.plan)
        var acceptedAlignment = cachedAlignment?.checkpoint
        val output = if (cachedAlignment != null) {
            progress(Progress("Reusing verified sparse alignment", 1, 1))
            artifacts.reuse(ArtifactStage.FEATURES, requireNotNull(cachedAlignment.parents[ArtifactStage.FEATURES]))
            artifacts.reuse(ArtifactStage.MATCHES, requireNotNull(cachedAlignment.parents[ArtifactStage.MATCHES]))
            val saved = alignmentCache.save(artifacts.plan, cachedAlignment.geometry, cachedAlignment.checkpoint)
            artifacts.record(ArtifactStage.ALIGNMENT, saved, "positive-depth-reprojection-and-connected-tracks",
                evidenceHash = cachedAlignment.checkpoint.acceptance.evidenceHash)
            measured(ScanStage.FEATURE_EXTRACTION, photos.size, photos.size, ProgressUnit.PHOTOS, reusedCheckpoint = true)
            diagnosticRecorder?.outcome(ScanStage.FEATURE_EXTRACTION, StageOutcome.SUCCEEDED)
            // This counts one verified graph checkpoint, not freshly executed image pairs.
            measured(ScanStage.MATCHING, 1, 1, ProgressUnit.STEPS, reusedCheckpoint = true)
            diagnosticRecorder?.outcome(ScanStage.MATCHING, StageOutcome.SUCCEEDED)
            pipelineTrace.completed(PipelineStage.FEATURES, "features/")
            pipelineTrace.completed(PipelineStage.MATCHING,
                requireNotNull(cachedAlignment.parents[ArtifactStage.MATCHES]).artifacts.first().relativePath)
            pipelineTrace.completed(PipelineStage.SPARSE, saved.first().file.name)
            cachedAlignment.geometry.put("alignmentReused", true)
        } else {
        activeDiagnosticStage = ScanStage.FEATURE_EXTRACTION
        artifacts.starting(ArtifactStage.FEATURES)
        val cache = File(directory, "features").apply { check(isDirectory || mkdirs()) }
        check(!java.nio.file.Files.isSymbolicLink(cache.toPath())) { "Feature cache cannot be a symbolic link." }
        val matchCache = FeatureMatchCache(directory, pipelineSnapshot.identity(PipelineStage.MATCHING), photos.size)
        val matchingContext = coroutineContext
        val savedMatches = matchCache.load { matchingContext.ensureActive() }.orEmpty().toMutableMap()
        var checkpointObservations = emptyMap<Int, List<TrackObservation>>()
        // Registration only consumes the complete persisted schedule below. No hidden
        // matching or additional unbounded pair cache can escape that durable boundary.
        suspend fun featurePairs(a: Int, b: Int): FeaturePairs {
            coroutineContext.ensureActive()
            require(a in photos.indices && b in photos.indices && a != b)
            val key = minOf(a,b) to maxOf(a,b)
            val record = savedMatches.getValue(key)
            val pair = FeaturePairs(record.firstKeys, record.secondKeys)
            return if(a == key.first) pair else pair.reversed()
        }
        val summaries = ArrayList<PhotoFeatureSummary>(photos.size)
        for ((index, photo) in photos.withIndex()) {
            coroutineContext.ensureActive()
            measured(ScanStage.FEATURE_EXTRACTION, index, photos.size, ProgressUnit.PHOTOS)
            progress(Progress("Detecting SIFT features", index, photos.size))
            val timing = FeatureWorkTiming(index + 1, photos.size, changed = onFeatureWork)
            loadFrame(photo, cache, timing).use { frame ->
                val descriptors = FloatArray(frame.features.count * 128)
                frame.features.withDescriptors { if (descriptors.isNotEmpty()) it.get(0, 0, descriptors) }
                summaries.add(PhotoFeatureSummary.siftMean(photo.id, descriptors))
            }
            featureWorkSamples.add(timing.snapshot())
        }
        progress(Progress("Detecting SIFT features", photos.size, photos.size))
        measured(ScanStage.FEATURE_EXTRACTION, photos.size, photos.size, ProgressUnit.PHOTOS)
        val verifiedFeatures = SparseFeatureCache(cache)
        artifacts.record(ArtifactStage.FEATURES, photos.map { photo ->
            check(verifiedFeatures.load(photo.sha256) != null) { "Feature checkpoint is unavailable or corrupt." }
            PipelineStageFile(verifiedFeatures.artifactFile(photo.sha256), RepresentationKind.FEATURE_SET)
        }, "bounded-sift-v2-embedded-identity-and-sha256")
        pipelineTrace.completed(PipelineStage.FEATURES, "features/")
        diagnosticRecorder?.outcome(ScanStage.FEATURE_EXTRACTION, StageOutcome.SUCCEEDED)
        activeDiagnosticStage = ScanStage.MATCHING
        artifacts.starting(ArtifactStage.MATCHES)
        // Summary similarity schedules work, never proves overlap. Every admitted pair is
        // considered, including summary-zero/orthogonal fallbacks; no import-order truncation.
        val planningContext = coroutineContext
        val candidatePlan = AdmittedPairPlan.build(summaries) { planningContext.ensureActive() }
        val photoIndex = photos.withIndex().associate { it.value.id to it.index }
        val candidates = candidatePlan.pairs.map { pair ->
            val first = photoIndex.getValue(pair.firstPhotoId)
            val second = photoIndex.getValue(pair.secondPhotoId)
            minOf(first, second) to maxOf(first, second)
        }
        val verifiedPairIds = linkedSetOf<Pair<String, String>>()
        var best: JSONObject? = null
        var bestScore = -1.0
        var anchorPair: Pair<Int, Int>? = null
        var anchorMatches: PairMatches? = null
        val failures = JSONArray()
        for ((index, pair) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            measured(ScanStage.MATCHING, index, candidates.size, ProgressUnit.PAIRS)
            progress(Progress("Verifying overlapping photo pairs", index, candidates.size))
            val first = photos[pair.first]; val second = photos[pair.second]
            val timing = PairWorkTiming(index + 1, candidates.size, changed = onPairWork)
            val signal = PairCancellationSignal(coroutineContext[kotlinx.coroutines.Job], timing)
            var pairOutcome = PairWorkPhase.FAILED
            var pairFailure: Throwable? = null
            try {
                timing.begin()
                loadFrame(first, cache).use { a ->
                    timing.frameLoaded(true, a.cacheHit, a.features.count)
                    timing.phase(PairWorkPhase.LOAD_SECOND)
                    loadFrame(second, cache).use { b ->
                    timing.frameLoaded(false, b.cacheHit, b.features.count)
                    timing.phase(PairWorkPhase.MATCHING)
                    val fA = min(a.projection.fx, a.projection.fy)
                    val fB = min(b.projection.fx, b.projection.fy)
                    timing.matchCache(savedMatches.containsKey(pair))
                    val rows = savedMatches[pair]?.let { PairMatches(it.rows, it.firstKeys, it.secondKeys) }
                        ?: matches(a, b, timing).also { savedMatches[pair] = FeatureMatchRecord(it.rows, it.firstKeys, it.secondKeys) }
                    if (rows.size >= 20) {
                        timing.phase(PairWorkPhase.GEOMETRY)
                        val calibrated = CalibratedInitializerInput.create(
                            a.projection, a.width, a.height, a.xy, rows.firstKeys,
                            b.projection, b.width, b.height, b.xy, rows.secondKeys)
                        val result = com.realityforge.scanner.engine.nativebridge.NativeResultJson.decode(nativeCall(nativeToken,
                            { update -> progress(update.copy(pairWork = timing.snapshot())) }) { NativeCore.reconstructCalibratedCancellable(rows.rows, calibrated.observed, calibrated.domains, 1.5 / min(fA, fB), nativeToken) })
                        coroutineContext.ensureActive()
                        if (!result.has("error")) {
                            verifiedPairIds.add(minOf(first.id, second.id) to maxOf(first.id, second.id))
                            val score = result.getInt("pointCount") * min(result.getDouble("medianParallaxDegrees"), 8.0) /
                                (1.0 + result.getDouble("medianErrorNormalized") * (fA + fB) / 2)
                            if (score > bestScore) {
                                result.put("firstPhotoId", first.id).put("secondPhotoId", second.id)
                                result.put("firstFocalPixels", fA).put("secondFocalPixels", fB)
                                result.put("calibration", if (activeProfile == null) "Estimated pinhole (no measured lens distortion)" else "Local checkerboard Brown-Conrady correction; RMS ${activeProfile!!.reprojectionRmsPixels} px")
                                result.put("assumedFocal35mm", assumedFocal35)
                                result.put("metadataFocalAvailable", first.focal35mm != null && second.focal35mm != null)
                                best = result; bestScore = score
                                anchorPair = pair; anchorMatches = rows
                            }
                        } else failures.put(JSONObject().put("pair", "${pair.first + 1}/${pair.second + 1}").put("reason", result.getString("error")))
                    } else failures.put(JSONObject().put("pair", "${pair.first + 1}/${pair.second + 1}").put("reason", "Fewer than 20 reciprocal SIFT matches"))
                } }
                pairOutcome = PairWorkPhase.READY
            } catch (failure: Throwable) {
                pairFailure = failure
                if (failure is java.util.concurrent.CancellationException) {
                    pairOutcome = PairWorkPhase.CANCELLED
                    preservePairFailure(failure) { timing.cancellationObserved() }
                }
                throw failure
            } finally {
                signal.close()
                preservePairFailure(pairFailure) { timing.finish(pairOutcome) }
            }
        }
        progress(Progress("Verifying overlapping photo pairs", candidates.size, candidates.size))
        measured(ScanStage.MATCHING, candidates.size, candidates.size, ProgressUnit.PAIRS)
        // Persist every considered pair, not just the winning anchor. These are reciprocal
        // correspondences; native geometry is reverified when this checkpoint is resumed.
        val matchFiles = matchCache.save(candidates.associateWith { savedMatches.getValue(it) }) { matchingContext.ensureActive() }
        artifacts.record(ArtifactStage.MATCHES, matchFiles, "bounded-reciprocal-match-graph")
        var output = best ?: reject(ScanStage.MATCHING, ScanFailureCode.INSUFFICIENT_OVERLAP,
            RecoveryAction.RECAPTURE_OVERLAP, "scan.failure.insufficient_overlap")
        diagnosticRecorder?.outcome(ScanStage.MATCHING, StageOutcome.SUCCEEDED)
        activeDiagnosticStage = ScanStage.CAMERA_REGISTRATION
        artifacts.starting(ArtifactStage.ALIGNMENT)
        pipelineTrace.completed(PipelineStage.MATCHING, matchCache.file.name)
        val selected = requireNotNull(anchorPair)
        val pairTracks = requireNotNull(anchorMatches)
        checkpointObservations = (0 until output.getJSONArray("points").length()).associate { index ->
            val track = output.getJSONArray("points").getJSONArray(index).getInt(8)
            require(track in 0 until pairTracks.size)
            track to listOf(TrackObservation(0, pairTracks.firstKeys[track], pairTracks.rows[track * 7], pairTracks.rows[track * 7 + 1]),
                TrackObservation(1, pairTracks.secondKeys[track], pairTracks.rows[track * 7 + 2], pairTracks.rows[track * 7 + 3]))
        }
        var cameraArray = JSONArray()
        cameraArray.put(JSONObject().put("photoId", photos[selected.first].id).put("rotation", JSONArray(listOf(1,0,0,0,1,0,0,0,1)))
            .put("translation", JSONArray(listOf(0,0,0))).put("kind", "anchor"))
        cameraArray.put(JSONObject().put("photoId", photos[selected.second].id).put("rotation", output.getJSONArray("rotation"))
            .put("translation", output.getJSONArray("translation")).put("kind", "anchor"))
        var cameraFailures = JSONArray()
        val first = photos[selected.first]; val second = photos[selected.second]
        val anchorPoints = output.getJSONArray("points")
        val outstanding = photos.indices.filter { it != selected.first && it != selected.second }
        measured(ScanStage.CAMERA_REGISTRATION, cameraArray.length(), photos.size, ProgressUnit.CAMERAS)
        // The features anchoring world XYZ are identified by matchIndex, not rounded coordinates.
        loadFrame(first, cache).use { a -> loadFrame(second, cache).use { b ->
            val fA = min(a.projection.fx, a.projection.fy)
            val fB = min(b.projection.fx, b.projection.fy)
            for ((step, candidateIndex) in outstanding.withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Registering additional camera poses", step, outstanding.size))
                val third = photos[candidateIndex]
                loadFrame(third, cache).use { c ->
                    val fC = min(c.projection.fx, c.projection.fy)
                    // Require a cycle-consistent third keypoint match to BOTH anchor views.
                    val ac = featurePairs(selected.first, candidateIndex).let { rows ->
                        rows.first.indices.associate { rows.first[it] to rows.second[it] }
                    }
                    val bc = featurePairs(selected.second, candidateIndex).let { rows ->
                        rows.first.indices.associate { rows.first[it] to rows.second[it] }
                    }
                    val packed = ArrayList<Double>()
                    for (i in 0 until anchorPoints.length()) {
                        val point = anchorPoints.getJSONArray(i)
                        val trackIndex = point.getInt(8)
                        if (trackIndex !in 0 until pairTracks.size) continue
                        val fromA = ac[pairTracks.firstKeys[trackIndex]] ?: continue
                        val fromB = bc[pairTracks.secondKeys[trackIndex]] ?: continue
                        if (fromA != fromB) continue
                        val observation = runCatching {
                            c.projection.normalized(c.xy[2 * fromA].toDouble(), c.xy[2 * fromA + 1].toDouble())
                        }.getOrNull() ?: continue
                        packed.add(point.getDouble(0));packed.add(point.getDouble(1));packed.add(point.getDouble(2))
                        packed.add(observation.first);packed.add(observation.second)
                    }
                    if (packed.size / 5 < 16) {
                        cameraFailures.put(JSONObject().put("photoId",third.id).put("reason","Fewer than 16 cycle-consistent 3D-2D tracks"))
                    } else {
                        val pose = com.realityforge.scanner.engine.nativebridge.NativeResultJson.decode(nativeCall(nativeToken, progress) { NativeCore.registerCameraCancellable(packed.toDoubleArray(),2.0 / fC,nativeToken) })
                        coroutineContext.ensureActive()
                        if (pose.has("error")) cameraFailures.put(JSONObject().put("photoId",third.id).put("reason",pose.getString("error")))
                        else cameraArray.put(pose.put("photoId",third.id).put("kind","registered"))
                    }
                }
                measured(ScanStage.CAMERA_REGISTRATION, cameraArray.length(), photos.size, ProgressUnit.CAMERAS)
            }
        } }
        progress(Progress("Registering additional camera poses", outstanding.size, outstanding.size))
        // Extend from newly triangulated landmarks, not just the original anchor intersection.
        // Failure leaves every accepted M02 pose and original photograph intact.
        // Also persist/validate an already complete >=3-camera frontier. A two-photo
        // anchor-only capture has no incremental session and claims no frontier evidence.
        if (photos.size >= 3) {
            try {
                val templates=photos.map { photo -> loadFrame(photo,cache).use { frame ->
                    SparseView(frame.width,frame.height,min(frame.projection.fx,frame.projection.fy),
                        frame.xy.copyOf(),frame.colors.copyOf(),
                        doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
                        doubleArrayOf(0.0,0.0,0.0),projection=frame.projection)
                } }
                val initial=(0 until cameraArray.length()).map { local ->
                    val camera=cameraArray.getJSONObject(local)
                    val photo=photos.indexOfFirst { it.id==camera.getString("photoId") }
                    require(photo>=0)
                    RegisteredFrame(photo,templates[photo].copy(
                        rotation=DoubleArray(9) { camera.getJSONArray("rotation").getDouble(it) },
                        translation=DoubleArray(3) { camera.getJSONArray("translation").getDouble(it) }))
                }
                val nativeFrontier = NativeFrontierGeometry.forOperation(nativeToken)
                val session=AdmittedRegistrationSession.grow(directory,fingerprint,photos.map { it.id },
                    templates,initial,::featurePairs,nativeFrontier.triangulate,nativeFrontier.register,
                    progress={stage,done,total -> progress(Progress(stage,done,total))})
                val growth=session.growth
                coroutineContext.ensureActive()
                // Resume can restore cameras missing from this attempt's initial PnP pass.
                // Match validated source IDs, never assume a growth-list prefix length.
                for (camera in growth.cameras) {
                    val photoId=photos[camera.photo].id
                    val existing=(0 until cameraArray.length()).firstOrNull {
                        cameraArray.getJSONObject(it).getString("photoId")==photoId
                    }
                    val accepted=JSONObject().put("photoId",photoId)
                        .put("rotation",JSONArray(camera.view.rotation.toList()))
                        .put("translation",JSONArray(camera.view.translation.toList()))
                        .put("kind",if(existing!=null)cameraArray.getJSONObject(existing).optString("kind","registered") else "incremental")
                    if(existing!=null)cameraArray.put(existing,accepted) else cameraArray.put(accepted)
                }
                val attempts=JSONArray()
                for(attempt in growth.attempts) attempts.put(JSONObject()
                    .put("photoId",photos[attempt.photo].id).put("registeredBasis",attempt.registeredBasis)
                    .put("candidateTracks",attempt.tracks).put("accepted",attempt.accepted).put("reason",attempt.reason))
                output.put("incrementalRegistration",JSONObject().put("initialCameraCount",initial.size)
                    .put("addedCameraCount",growth.cameras.size-initial.size).put("attempts",attempts)
                    .put("remainingPhotoIds",JSONArray(growth.unregistered.map { photos[it].id }))
                    .put("frontierResumeStatus",session.resumeStatus)
                    .put("restoredCameraCount",session.restoredCameraCount)
                    .put("durableFrontierWrites",session.persistedCheckpoints)
                    .put("failedFrontierWrites",session.failedCheckpointWrites)
                    .put("frontierDurability",if(session.failedCheckpointWrites==0 && session.persistedCheckpoints>0) "saved" else "not_guaranteed")
                    .put("frontierIsCompletedAlignment",false))
                // Superseded anchor-only failures must not be shown for newly aligned photographs.
                val remaining=JSONArray()
                for(photo in growth.unregistered) remaining.put(JSONObject().put("photoId",photos[photo].id)
                    .put("reason",growth.attempts.lastOrNull { it.photo==photo }?.reason ?:
                        "No connected, triangulated landmark support"))
                cameraFailures=remaining
            } catch(cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch(failure: Exception) {
                rethrowOperationalFailure(failure)
                output.put("incrementalRegistrationFailure",failure.message ?: "Registration expansion unavailable")
            }
        }
        // M03: Build a shared observation graph across *registered* views, including points not seen
        // by both anchors. Final typed BA fixes the first pose and baseline length, not the second pose.
        measured(ScanStage.CAMERA_REGISTRATION, cameraArray.length(), photos.size, ProgressUnit.CAMERAS)
        measured(ScanStage.SPARSE_GEOMETRY, 0, 1, ProgressUnit.STEPS)
        var geometryWarning = "M03 sparse multi-view triangulation did not produce an acceptable result; retained the verified M02 anchor cloud. No dense mesh or textures."
        try {
            coroutineContext.ensureActive()
            progress(Progress("Building multi-view feature tracks", 0, 1))
            val registeredPhotoIndices = (0 until cameraArray.length()).map { camera ->
                val id = cameraArray.getJSONObject(camera).getString("photoId")
                photos.indexOfFirst { it.id == id }.also { check(it >= 0) { "Missing registered photograph" } }
            }
            val views = registeredPhotoIndices.mapIndexed { index, source ->
                val photo = photos[source]
                loadFrame(photo, cache).use { frame ->
                    val camera = cameraArray.getJSONObject(index)
                    val rotationJson = camera.getJSONArray("rotation")
                    val translationJson = camera.getJSONArray("translation")
                    SparseView(
                        frame.width, frame.height,
                        min(frame.projection.fx, frame.projection.fy),
                        frame.xy.copyOf(), frame.colors.copyOf(),
                        DoubleArray(9) { rotationJson.getDouble(it) },
                        DoubleArray(3) { translationJson.getDouble(it) },
                        projection = frame.projection,
                    )
                }
            }
            val graph = MultiViewTrackGraph(views)
            val graphPairs = buildList {
                for (i in views.indices) for (j in i + 1 until views.size) {
                    add(i to j)
                }
            }.also { check(it.size <= AdmittedPairPlan.MAX_PAIRS) }
            for ((step, pair) in graphPairs.withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Verifying cross-camera feature tracks", step, graphPairs.size))
                val correspondence = featurePairs(registeredPhotoIndices[pair.first],registeredPhotoIndices[pair.second])
                for (k in correspondence.first.indices) {
                    if (k % 256 == 0) coroutineContext.ensureActive()
                    graph.add(pair.first,correspondence.first[k],pair.second,correspondence.second[k])
                }
            }
            progress(Progress("Verifying cross-camera feature tracks", graphPairs.size, graphPairs.size))
            // Full accepted graph, never the capped legacy native snapshot or last frontier window.
            check(graph.matchedTrackCount() <= 15000) { "Complete sparse track budget exceeded" }
            val sourcePhotoIds = registeredPhotoIndices.map { photos[it].id }
            val stableTracks = graph.stableSnapshot(sourcePhotoIds)
            output.put("finalSparseInputTracks", stableTracks.tracks.size)
                .put("finalSparseInputObservations", stableTracks.tracks.sumOf { it.observations.size })
            val graphGroups = stableTracks.tracks.map { track -> track.observations.associate { source ->
                sourcePhotoIds.indexOf(source.photo).also { check(it >= 0) } to source.keypoint
            } }
            val graphObservations = graphGroups.map { group -> group.map { (camera, key) ->
                val normalized = views[camera].normalized(key)
                TrackObservation(camera, key, normalized.first, normalized.second)
            } }
            val anchorGroups = graphGroups.mapIndexedNotNull { index, group -> group[0]?.let { it to index } }.toMap()
            checkpointObservations = checkpointObservations.mapValues { (track, anchor) ->
                val group = anchorGroups[pairTracks.firstKeys[track]]
                if (group != null && graphGroups[group][1] == pairTracks.secondKeys[track]) graphObservations[group] else anchor
            }
            output.put("multiViewCandidateTracks", graph.matchedTrackCount())
            output.put("multiViewAcceptedEdges", graph.acceptedEdges)
            output.put("multiViewRejectedEdges", graph.rejectedEdges)
            val threshold = 3.0 / views.minOf { it.focalPixels }
            val sparseContext = coroutineContext
            val frontier = NativeFrontierGeometry.forOperation(nativeToken)
            progress(Progress("Validating complete sparse tracks and optimizing camera poses", 0, 1))
            measured(ScanStage.BUNDLE_ADJUSTMENT, 0, 1, ProgressUnit.STEPS)
            val coordinated = nativeCall(nativeToken, progress) {
                FinalSparseExecution.execute(sourcePhotoIds, views, stableTracks,
                    if (activeProfile != null) requireNotNull(
                        com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot.calibrationSha256(calibrationFile)) else null,
                    threshold, kotlin.math.ceil(output.getInt("pointCount") * .85).toInt(),
                    checkpoint = { sparseContext.ensureActive() },
                    onSeedReceipt = { output.put("finalSparseAccounting", FinalSparseExecution.accounting(it)) },
                    triangulate = { window ->
                        frontier.triangulate(window.cameraRows, window.normalizedRows, threshold).map { point ->
                            require(point.track in window.globalTrackIds.indices)
                            BoundedSparseCoordinator.Candidate(window.globalTrackIds[point.track],
                                point.xyz[0], point.xyz[1], point.xyz[2])
                        }
                    },
                    bundle = { input ->
                        com.realityforge.scanner.engine.nativebridge.NativeResultJson.decode(
                            NativeCore.bundleAdjustProblemCancellable(input.cameraRows, input.seedRows,
                                input.pixelRows, input.intrinsicRows, input.groupIds, input.photoIds,
                                input.sourceTrackIds, input.measured, false, threshold, 15, nativeToken))
                    })
            }
            coroutineContext.ensureActive()
            val staged = FinalSparsePublication.prepare(output, cameraArray, sourcePhotoIds,
                views, stableTracks, coordinated, checkpoint = { sparseContext.ensureActive() })
            val stagedObservations = staged.observations.mapValues { (_, observations) -> observations.map {
                TrackObservation(it.camera, it.key, it.nx, it.ny)
            } }
            // All callbacks/serialization and provenance checks precede the atomic reference swap.
            // A failure here leaves verified anchor points, poses and observations untouched.
            measured(ScanStage.BUNDLE_ADJUSTMENT, 1, 1, ProgressUnit.STEPS)
            progress(Progress("Triangulating and refining sparse geometry", 1, 1))
            coroutineContext.ensureActive()
            output = staged.output
            cameraArray = staged.cameras
            checkpointObservations = stagedObservations
            geometryWarning = "Complete admitted sparse tracks were validated against original observations and jointly optimized with fixed first pose, baseline length and lens models. Still no dense surface, texture or measured scale."
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            rethrowOperationalFailure(failure)
            output.put("finalSparseOutcome", "verified_anchor_fallback")
                .put("finalSparseFailure", failure.message ?: "Invalid complete sparse observations")
                .put("bundleAdjustmentFailure", failure.message ?: "Global sparse finalization failed")
            geometryWarning = "Complete sparse finalization unavailable: ${failure.message ?: "invalid observations"}. Retained the verified M02 anchor point cloud; no global sparse result was published."
        }
        output.put("cameras", cameraArray).put("registeredCameras", cameraArray.length())
            .put("cameraRegistrationFailures", cameraFailures).put("pairsExamined", candidates.size)
            .put("failedPairs", failures).put("warning", geometryWarning)
        val registeredIds = (0 until cameraArray.length()).map { cameraArray.getJSONObject(it).getString("photoId") }.toSet()
        output.put("overlapDiagnostics", JSONObject()
            .put("policy", "summary-ranked-exhaustive-admitted12-v1")
            .put("candidatePairs", candidates.size)
            .put("summaryRetrievedPairs", candidatePlan.retrieval.pairs.size)
            .put("fallbackPairs", candidates.size - candidatePlan.retrieval.pairs.size)
            .put("summaryCandidateComponents", JSONArray(candidatePlan.retrieval.candidateComponents.map { JSONArray(it) }))
            .put("zeroSummaryPhotoIds", JSONArray(candidatePlan.retrieval.zeroEvidencePhotos))
            .put("duplicateSummaryGroups", JSONArray(candidatePlan.retrieval.duplicateSummaryGroups.map { JSONArray(it) }))
            .put("geometricallyVerifiedPairs", verifiedPairIds.size)
            .put("geometricallyVerifiedComponents", JSONArray(candidatePlan.verifiedComponents(verifiedPairIds).map { JSONArray(it) }))
            .put("unregisteredPhotoIds", JSONArray(candidatePlan.photoIds.filter { it !in registeredIds }))
            .put("summarySimilarityIsGeometricEvidence", false))
        val checkpointCameras = (0 until cameraArray.length()).map { index ->
            val camera = cameraArray.getJSONObject(index)
            val photo = photos.single { it.id == camera.getString("photoId") }
            loadFrame(photo, cache).use { frame ->
                AlignmentCamera(photo.id, frame.width, frame.height, frame.projection.fx, frame.projection.fy,
                    frame.projection.cx, frame.projection.cy,
                    List(9) { camera.getJSONArray("rotation").getDouble(it) },
                    List(3) { camera.getJSONArray("translation").getDouble(it) })
            }
        }
        val fullCheckpoint = MeasuredAlignmentCheckpoint.build(artifacts.plan, output, checkpointCameras, checkpointObservations)
        val saved = alignmentCache.save(artifacts.plan, output, fullCheckpoint)
        artifacts.record(ArtifactStage.ALIGNMENT, saved, "positive-depth-reprojection-and-connected-tracks",
            evidenceHash = fullCheckpoint.acceptance.evidenceHash)
        pipelineTrace.completed(PipelineStage.SPARSE, saved.first().file.name)
        acceptedAlignment = fullCheckpoint
        output
        }
        val cameraArray = output.getJSONArray("cameras")
        val alignmentReused = cachedAlignment != null
        measured(ScanStage.CAMERA_REGISTRATION, cameraArray.length(), photos.size, ProgressUnit.CAMERAS, alignmentReused)
        outcome(ScanStage.CAMERA_REGISTRATION, cameraArray.length() == photos.size,
            ScanFailureCode.CAMERA_REGISTRATION_FAILED, RecoveryAction.RECAPTURE_OVERLAP, "scan.failure.camera_registration_failed")
        measured(ScanStage.SPARSE_GEOMETRY, 1, 1, ProgressUnit.STEPS, alignmentReused)
        outcome(ScanStage.SPARSE_GEOMETRY, output.has("multiViewStats"), ScanFailureCode.SPARSE_FALLBACK_RETAINED,
            RecoveryAction.RECAPTURE_OVERLAP, "scan.failure.sparse_fallback_retained")
        if (output.has("bundleAdjustment") || output.has("bundleAdjustmentFailure"))
            outcome(ScanStage.BUNDLE_ADJUSTMENT, output.has("bundleAdjustment"), ScanFailureCode.SPARSE_REFINEMENT_UNAVAILABLE,
                RecoveryAction.RECALIBRATE, "scan.failure.sparse_refinement_unavailable")
        activeDiagnosticStage = ScanStage.DENSE_DEPTH
        artifacts.starting(ArtifactStage.DENSE_DEPTH)
        // M09: retain all accepted geometry if appearance reconstruction is unavailable.
        // Native CPU depth remains a fallback when conservative triangle meshing fails.
        var geometryReused = false
        var textureReused = false
        try {
            coroutineContext.ensureActive()
            measured(ScanStage.DENSE_DEPTH, 0, 1, ProgressUnit.STEPS)
            progress(Progress("Estimating stereo depth, meshing and projecting photographs", 0, 1))
            val denseContext = coroutineContext
            val verifiedDense = com.realityforge.scanner.engine.pipeline.VerifiedDenseAlignment.prepare(
                artifacts.plan, output, requireNotNull(acceptedAlignment), resourceSelection.maximumViews,
                checkActive = { denseContext.ensureActive() })
            val typedSurface = DensePreview.reconstructTyped(
                repository, projectId, artifactKey, photos, cameraArray,
                output.getJSONArray("points"), assumedFocal35, activeProfile, nativeToken,
                pipelineSnapshot, resourceSelection = resourceSelection,
                selection = verifiedDense.selection, executionProfile = executionProfile,
                acceptedSparseSupport = verifiedDense.support,
                sparseThresholdNormalized = verifiedDense.thresholdNormalized,
                surfaceCheckpoint = com.realityforge.scanner.engine.pipeline.PipelineSurfaceCache(directory).load(artifacts.plan, output),
            ) { stage, done, total -> progress(Progress(stage, done, total)) }
            typedSurface.outcomes.forEach(pipelineTrace::record)
            val surface = typedSurface.payload
            geometryReused = surface.optBoolean("geometryReused", false)
            textureReused = surface.optBoolean("textureReused", false)
            coroutineContext.ensureActive()
            output.put("densePreview",surface.getJSONObject("dense"))
            output.put("denseStatus","ready")
            if(surface.has("mesh")) {
                output.put("meshPreview",surface.getJSONObject("mesh"))
                output.put("meshStatus","ready")
                if(surface.has("texture")) {
                    output.put("texturePreview",surface.getJSONObject("texture"))
                    output.put("textureStatus","ready")
                } else {
                    output.put("textureStatus","unavailable")
                    output.put("textureFailure",surface.optString("textureError",
                        "No consistent photograph projection was supported"))
                }
            } else {
                output.put("meshStatus","unavailable")
                output.put("meshFailure",surface.optString("meshError","Insufficient supported surface geometry"))
                output.put("textureStatus","unavailable")
                output.put("textureFailure","A supported triangle mesh is required before UV projection")
            }
            progress(Progress("Estimating stereo depth, meshing and projecting photographs", 1, 1))
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            rethrowOperationalFailure(failure)
            val denseFailure = failure.message ?: "Unreliable dense depth"
            if (pipelineTrace.outcome(PipelineStage.DENSE) == null) {
                pipelineTrace.unavailable(PipelineStage.DENSE, "DENSE_RECONSTRUCTION_FAILED", denseFailure)
            }
            if (pipelineTrace.outcome(PipelineStage.SURFACE) == null) {
                pipelineTrace.unavailable(PipelineStage.SURFACE, "DENSE_PREREQUISITE_UNAVAILABLE",
                    "Depth prerequisite failed; no supported triangles")
            }
            if (pipelineTrace.outcome(PipelineStage.TEXTURE) == null) {
                pipelineTrace.unavailable(PipelineStage.TEXTURE, "SURFACE_PREREQUISITE_UNAVAILABLE",
                    "Depth or mesh prerequisite unavailable")
            }
            output.put("denseStatus","unavailable")
                .put("denseFailure",denseFailure)
                .put("meshStatus","unavailable")
                .put("meshFailure","Depth prerequisite failed; no supported triangles")
                .put("textureStatus","unavailable")
                .put("textureFailure","Depth or mesh prerequisite unavailable")
        }
        measured(ScanStage.DENSE_DEPTH, 1, 1, ProgressUnit.STEPS, geometryReused)
        outcome(ScanStage.DENSE_DEPTH, output.optString("denseStatus") == "ready", ScanFailureCode.DENSE_DEPTH_UNAVAILABLE,
            RecoveryAction.RECAPTURE_TEXTURE, "scan.failure.dense_depth_unavailable", StageOutcome.FAILED)
        // These count one completed typed result evaluation, not inferred native work or coverage.
        measured(ScanStage.SURFACE, 1, 1, ProgressUnit.STEPS, geometryReused)
        outcome(ScanStage.SURFACE, output.optString("meshStatus") == "ready", ScanFailureCode.SURFACE_UNAVAILABLE,
            RecoveryAction.RETRY_STAGE, "scan.failure.surface_unavailable",
            if (output.optString("denseStatus") == "ready") StageOutcome.FAILED else StageOutcome.BLOCKED_BY_DEPENDENCY)
        measured(ScanStage.TEXTURE, 1, 1, ProgressUnit.STEPS, textureReused)
        outcome(ScanStage.TEXTURE, output.optString("textureStatus") == "ready", ScanFailureCode.TEXTURE_UNAVAILABLE,
            RecoveryAction.RECAPTURE_TEXTURE, "scan.failure.texture_unavailable",
            if (output.optString("meshStatus") == "ready") StageOutcome.FAILED else StageOutcome.BLOCKED_BY_DEPENDENCY)
        output.put("artifactKey", artifactKey).put("settingsFingerprint", fingerprint).put("revision", revision).put("photosExamined", photos.size)
            .put("engineVersion", pipelineSnapshot.engineVersion).put("calibrationProfileApplied", activeProfile != null)
            .put("pipeline", pipelineJson(pipelineSnapshot, capacityProfile, pipelineTrace))
        // Always preserve measured evidence and actionable failure reasons in
        // the same revision-bound JSON that the library reopens.
        artifactBinding?.let { output.put("jobId", it.jobId).put("jobAttempt", it.attempt) }
        output.put("calibrationCompatibility", calibrationCompatibility)
        calibrationRecord?.let { output.put("calibrationProfileId", it.profileId)
            .put("calibrationRmsPixels", it.profile.reprojectionRmsPixels) }
        coroutineContext.ensureActive()
        if (repository.dao.project(projectId)?.revision != revision)
            reject(ScanStage.EXPORT, ScanFailureCode.REVISION_MISMATCH, RecoveryAction.REVIEW_INPUTS, "scan.failure.revision_mismatch")
        val exportCount = if (output.has("densePreview")) 4 else 2
        measured(ScanStage.EXPORT, 0, exportCount, ProgressUnit.ITEMS)
        progress(Progress("Saving diagnostic exports", 0, 2))
        val points = output.getJSONArray("points")
        val packed = DoubleArray(points.length() * 6) { i -> points.getJSONArray(i / 6).getDouble(i % 6) }
        for ((index, extension) in listOf("glb", "ply").withIndex()) {
            coroutineContext.ensureActive()
            val target = File(directory, "$artifactKey.$extension")
            val temp = File(directory, "${target.name}.partial")
            requireFreshOutput(target, temp)
            try {
                NativeCore.exportPointCloud(packed, temp.path, extension == "glb", NativeCore.SPARSE_POINTS)
                FileOutputStream(temp, true).use { it.fd.sync() }
                check(temp.renameTo(target)) { "Could not publish $extension export." }
            } finally { temp.delete() }
            progress(Progress("Saving diagnostic exports", index + 1, 2))
            measured(ScanStage.EXPORT, index + 1, exportCount, ProgressUnit.ITEMS)
        }
        // Export dense POINTS separately. Never alias the sparse file paths.
        if (output.has("densePreview")) {
            val densePoints = output.getJSONObject("densePreview").getJSONArray("points")
            val densePacked = DoubleArray(densePoints.length()*6) { i ->
                densePoints.getJSONArray(i/6).getDouble(i%6)
            }
            for ((index, extension) in listOf("dense.glb", "dense.ply").withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Saving dense point-cloud exports",index,2))
                val target=File(directory,"$artifactKey.$extension")
                val temporary=File(directory,"${target.name}.partial")
                requireFreshOutput(target, temporary)
                try {
                    NativeCore.exportPointCloud(densePacked,temporary.path,extension=="dense.glb",NativeCore.DENSE_POINTS)
                    FileOutputStream(temporary,true).use { it.fd.sync() }
                    check(temporary.renameTo(target)) { "Could not publish dense point-cloud export" }
                } finally { temporary.delete() }
                measured(ScanStage.EXPORT, index + 3, exportCount, ProgressUnit.ITEMS)
            }
            progress(Progress("Saving dense point-cloud exports",2,2))
        }
        diagnosticRecorder?.outcome(ScanStage.EXPORT, StageOutcome.SUCCEEDED)
        output.put("scanDiagnostics", ScanQualityReport.build(output, photos.size, diagnosticRecorder?.snapshot()))
        // Additive operational evidence, separate from scan quality/coverage and its schema.
        output.put("featureWorkDiagnostics", JSONObject().put("schema", 1)
            .put("alignmentReused", cachedAlignment != null)
            .put("samples", JSONArray(featureWorkSamples.map { sample -> JSONObject()
                .put("photoNumber", sample.photoNumber).put("photoCount", sample.photoCount)
                .put("phase", sample.phase.name).put("cacheHit", sample.cacheHit ?: JSONObject.NULL)
                .put("elapsedMillis", sample.elapsedMillis)
                .put("nativeStartedAfterMillis", sample.nativeStartedAfterMillis ?: JSONObject.NULL)
                .put("nativeFinishedAfterMillis", sample.nativeFinishedAfterMillis ?: JSONObject.NULL)
                .put("nativeElapsedMillis", sample.nativeElapsedMillis ?: JSONObject.NULL)
                .put("nativeCallInterruptible", sample.nativeCallInterruptible)
                .put("cancellationObserved", sample.cancellationObserved)
                .put("cancellationObservedToCleanupMillis", sample.cancellationObservedToCleanupMillis ?: JSONObject.NULL)
            })))
        repository.writeAtomic(File(directory, "$artifactKey.json"), output.toString())
        com.realityforge.scanner.engine.pipeline.PipelineDownstreamCheckpoints.record(artifacts, output, artifactKey)
        output
    }

    private fun requireFreshOutput(target: File, temporary: File) {
        check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "A point-cloud output already exists; preserve it and retry project recovery."
        }
    }

    private fun rethrowOperationalFailure(failure: Exception) {
        if (failure is com.realityforge.scanner.processing.resources.ResourceCheckpointPause ||
            failure is com.realityforge.scanner.engine.nativebridge.NativeResourceExhaustedException ||
            failure is DiagnosticOperationException) throw failure
    }

    private suspend fun <T> nativeCall(token: Long, progress: suspend (Progress) -> Unit, block: () -> T): T =
        com.realityforge.scanner.engine.pipeline.withNativeStageProgress(token,
            { stage, done, total -> progress(Progress(stage, done, total)) }, block)

    private fun pipelineJson(
        snapshot: PipelineInputSnapshot,
        capacityProfile: PipelineCapacityProfile,
        trace: PipelineAdapterTrace,
    ): JSONObject {
        val identities = JSONObject()
        snapshot.identitiesThrough(PipelineStage.TEXTURE).forEach { identity ->
            identities.put(identity.stage.name.lowercase(), JSONObject()
                .put("schema", identity.schema)
                .put("digest", identity.digest)
                .put("parentDigest", identity.parentDigest ?: JSONObject.NULL)
                .put("representation", identity.stage.representation.name))
        }
        val outcomes = JSONArray()
        trace.outcomes.forEach { outcome ->
            val row = JSONObject().put("stage", outcome.stage.name.lowercase())
            when (outcome) {
                is PipelineStageOutcome.Completed -> {
                    val artifact = outcome.artifact
                    row.put("state", "completed")
                        .put("representation", artifact.representation.name)
                        .put("artifact", JSONObject()
                            .put("id", artifact.artifactId)
                            .put("identity", artifact.identity.digest)
                            .put("primaryLocation", artifact.primaryLocation)
                            .put("companionLocations", JSONArray(artifact.companionLocations))
                            .put("contentSha256", artifact.contentSha256 ?: JSONObject.NULL))
                }
                is PipelineStageOutcome.Unavailable -> {
                    val failure = outcome.failure
                    row.put("state", "unavailable")
                        .put("failure", JSONObject()
                            .put("code", failure.code)
                            .put("message", failure.message)
                            .put("recoverable", failure.recoverable))
                }
            }
            outcomes.put(row)
        }
        return JSONObject()
            .put("contract", "C2")
            .put("schema", PipelineStageIdentity.SCHEMA)
            .put("entryPoint", "SparsePipeline.run")
            .put("profile", capacityProfile.name)
            .put("photoCount", snapshot.photos.size)
            .put("engineVersion", snapshot.engineVersion)
            .put("stageIdentities", identities)
            .put("stageOutcomes", outcomes)
    }

    private suspend fun loadFrame(photo: ScanPhoto, cache: File, timing: FeatureWorkTiming? = null): Frame {
        val signal = timing?.let { FeatureCancellationSignal(coroutineContext[kotlinx.coroutines.Job], it) }
        var outcome = FeatureWorkPhase.FAILED
        try {
            timing?.begin()
            val frame = loadFrameInternal(photo, cache, timing)
            outcome = FeatureWorkPhase.READY
            return frame
        } catch (cancelled: java.util.concurrent.CancellationException) {
            timing?.cancellationObserved()
            outcome = FeatureWorkPhase.CANCELLED
            throw cancelled
        } finally {
            signal?.close()
            timing?.finish(outcome)
        }
    }

    private suspend fun loadFrameInternal(photo: ScanPhoto, cache: File, timing: FeatureWorkTiming?): Frame {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancelled = { job?.isActive == false }
        coroutineContext.ensureActive()
        val settings = extractor.settings.identity(decoderIdentity)
        val store = SparseFeatureCache(cache)
        val record = store.load(photo.sha256)
        timing?.cacheResult(record != null)
        if (record != null) {
            var descriptors: Mat? = null
            try {
                val owned = if (record.count == 0) Mat() else Mat(record.count, 128, CvType.CV_32F)
                descriptors = owned
                if (record.count != 0) owned.put(0, 0, record.descriptors())
                coroutineContext.ensureActive()
                val features = FeatureFrame(photo.id, photo.sha256, decoderIdentity, settings,
                    record.width, record.height, record.coordinates(), record.colors(), owned,
                    lens(photo, record.width, record.height))
                val frame = Frame(features, cacheHit = true)
                descriptors = null
                return frame
            } finally { descriptors?.release() }
        }
        val features = extractor.extractObserved(photo.id, photo.sha256, repository.photoFile(photo),
            decoderIdentity, { width, height -> lens(photo, width, height) }, cancelled) { event ->
                when (event) {
                    NativeFeatureCallEvent.STARTED -> timing?.nativeStarted()
                    NativeFeatureCallEvent.FINISHED -> timing?.nativeFinished()
                }
            }
        try {
            val values = FloatArray(features.count * 128)
            features.withDescriptors { if (values.isNotEmpty()) it.get(0, 0, values) }
            timing?.cacheWriteStarted()
            check(store.store(photo.sha256, features.width, features.height,
                features.coordinates(), features.colors(), values)) {
                "Cannot persist verified feature checkpoint; free storage and retry."
            }
            coroutineContext.ensureActive()
            return Frame(features)
        } catch (failure: Throwable) { features.close(); throw failure }
    }

    private suspend fun matches(a: Frame, b: Frame, timing: PairWorkTiming? = null): PairMatches {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val canonical = a.features.photoId < b.features.photoId
        val first = if (canonical) a.features else b.features
        val second = if (canonical) b.features else a.features
        val tentative = matcher.match(first, second,
            PairCandidate(first.photoId, second.photoId, 0.0, "coordinator-request-not-verified-overlap"),
            work = { direction, start, end, total, running -> timing?.block(direction, start, end, total, running) }) {
                job?.isActive == false
            }
        val rows = DoubleArray(tentative.count * 7)
        val firstKeys = IntArray(tentative.count)
        val secondKeys = IntArray(tentative.count)
        for ((index, m) in tentative.matches.withIndex()) {
            val firstKey = if (canonical) m.firstKey else m.secondKey
            val secondKey = if (canonical) m.secondKey else m.firstKey
            firstKeys[index] = firstKey
            secondKeys[index] = secondKey
            val base = index * 7
            rows[base] = if (canonical) m.ax else m.bx
            rows[base + 1] = if (canonical) m.ay else m.by
            rows[base + 2] = if (canonical) m.bx else m.ax
            rows[base + 3] = if (canonical) m.by else m.ay
            val color = a.colors[firstKey]
            rows[base + 4] = (color shr 16 and 255).toDouble()
            rows[base + 5] = (color shr 8 and 255).toDouble()
            rows[base + 6] = (color and 255).toDouble()
        }
        return PairMatches(rows, firstKeys, secondKeys)
    }
}
