package com.realityforge.scanner.engine

import androidx.exifinterface.media.ExifInterface
import com.realityforge.scanner.engine.dense.DenseViewSelection
import com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore
import com.realityforge.scanner.engine.registration.FrontierCamera
import com.realityforge.scanner.engine.registration.RegistrationFrontier
import com.realityforge.scanner.imaging.AndroidImageDecoder
import com.realityforge.scanner.engine.tracks.SourceObservation
import com.realityforge.scanner.engine.features.CachedFeatures
import com.realityforge.scanner.engine.features.FeatureCache
import com.realityforge.scanner.engine.features.FeatureCacheIdentity
import com.realityforge.scanner.engine.features.FeatureCacheRead
import com.realityforge.scanner.engine.features.FeatureExtractor
import com.realityforge.scanner.engine.features.FeatureFrame
import com.realityforge.scanner.engine.matching.FeatureMatcher
import com.realityforge.scanner.engine.matching.OverlapGraph
import com.realityforge.scanner.engine.matching.PairCandidate
import com.realityforge.scanner.engine.matching.PhotoFeatureSummary
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.pipeline.DensePreviewProfile
import com.realityforge.scanner.engine.pipeline.DenseExecutionPolicy
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
import com.realityforge.scanner.engine.pipeline.withNativeStageProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** M08: calibrated joint SfM, bounded CPU photometric depth and conservative open surface meshing. */
class SparsePipeline(
    private val repository: ProjectRepository,
    private val executionProfile: DensePreviewProfile = DensePreviewProfile.Compatibility,
) {
    data class Progress(val stage: String, val completed: Int, val total: Int)
    private data class PairMatches(val rows: DoubleArray, val firstKeys: IntArray, val secondKeys: IntArray) {
        val size: Int get() = firstKeys.size
        fun destinationBySource(): Map<Int, Int> = firstKeys.indices.associate { firstKeys[it] to secondKeys[it] }
    }
    private class Frame(val features: FeatureFrame) : Closeable {
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
        require(assumedFocal35 in 10.0..200.0) { "Use a plausible 35 mm-equivalent focal length." }
        check(OpenCVLoader.initLocal()) { "OpenCV could not load on this device." }
        val project = requireNotNull(repository.dao.project(projectId))
        val photos = repository.dao.photos(projectId)
        require(photos.size >= 2) { "Import at least two overlapping photographs." }
        // Never silently discard photos. Larger product target profiles are routed through the
        // same admission point and rejected explicitly until their combined resource/device gates pass.
        val capacityProfile = PipelineCapacityPolicy.requireSupported(photos.size)
        val directory = repository.directory(projectId)
        val revision = project.revision
        activeProfile = LensCalibration.read(repository, projectId)
        fallbackFocal35 = assumedFocal35
        val calibrationFile = LensCalibration.file(repository, projectId)
        val calibrationSource = if (calibrationFile.isFile) calibrationFile.readText() else "pinhole-no-profile-v1"
        val memoryTier = DenseWorkload.tier(Runtime.getRuntime().maxMemory())
        val pipelineSnapshot = PipelineInputSnapshot(
            projectId = projectId,
            revision = revision,
            photos = photos.map { photo ->
                PipelinePhotoInput(
                    id = photo.id,
                    contentSha256 = photo.sha256,
                    width = photo.width,
                    height = photo.height,
                    byteCount = photo.bytes,
                    focal35mm = photo.focal35mm,
                )
            },
            calibrationIdentity = sha256Utf8(calibrationSource),
            engineVersion = "m20-agent31-60-v2|${LensProjection.COORDINATE_CONVENTION}|rectifier-alpha-v2|dense-selection-verified-v1",
            settings = PipelineStageSettings(
                features = "${extractor.settings.identity(decoderIdentity)}|cache-v2",
                matching = "${matcher.settingsIdentity}|summary-graph-small-exhaustive-v1",
                sparse = "stable-frontier-v2|typed-bundle-pixel-v1|profile=$executionProfile|unknown-lens-groups-fixed|focal-policy-prior5pct-bounds15pct|assumed-focal35=$assumedFocal35",
                dense = "native-tiled-depth-v3|${DenseExecutionPolicy.depth(executionProfile)}|rectifier-alpha-v2|verified-selection-v1|profile=$executionProfile|planes-56|memory-tier=$memoryTier",
                surface = "${DenseExecutionPolicy.SURFACE_ENGINE}|quality-gated",
                texture = "${DenseExecutionPolicy.ATLAS_ENGINE}|${DenseExecutionPolicy.texture(executionProfile)}|rectifier-alpha-v2|profile=$executionProfile|memory-tier=$memoryTier",
                export = "diagnostic-sparse-and-dense-glb-ply-v1",
            ),
        )
        val pipelineTrace = PipelineAdapterTrace(pipelineSnapshot)
        val fingerprint = pipelineSnapshot.identity(PipelineStage.SPARSE).digest
        // Stage/cache identities remain deterministic; completed publication paths are attempt-owned.
        // A failed rerun must never replace the files referenced by the prior completed database row.
        val artifactExtensions=listOf("json","glb","ply","dense.glb","dense.ply","mesh.glb","mesh.ply","textured.glb","texture.png")
        val artifactKey=generateSequence {
            "sparse-m16-r$revision-${sha256Utf8(fingerprint+java.util.UUID.randomUUID().toString()).take(16)}"
        }.take(32).firstOrNull { key -> artifactExtensions.none { extension ->
            File(directory,"$key.$extension").exists() || File(directory,"$key.$extension.partial").exists()
        } } ?: error("Cannot allocate a private reconstruction attempt")
        var publicationComplete=false
        try {
            val cachePath = File(directory, "features")
            // The parent is the repository-owned private project directory, not user input.
            require(!java.nio.file.Files.isSymbolicLink(cachePath.toPath())) { "Invalid derived cache root" }
            val cache = cachePath.canonicalFile
            require(cache.parentFile == directory.canonicalFile) { "Derived cache escaped project" }
            // At most 66 pairs for twelve photos; cache key IDs, never image pixels/descriptors.
            val featurePairCache = HashMap<Pair<Int, Int>, FeaturePairs>()
            suspend fun featurePairs(a: Int, b: Int): FeaturePairs {
                require(a in photos.indices && b in photos.indices && a != b)
                val key = minOf(a,b) to maxOf(a,b)
                val pair = featurePairCache[key] ?: loadFrame(photos[key.first],cache).use { left ->
                    loadFrame(photos[key.second],cache).use { right ->
                        val match = matches(left,right)
                        FeaturePairs(match.firstKeys,match.secondKeys)
                    }
                }.also { featurePairCache[key] = it }
                return if(a == key.first) pair else pair.reversed()
            }
            val summaries = ArrayList<PhotoFeatureSummary>(photos.size)
            for ((index, photo) in photos.withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Detecting SIFT features", index, photos.size))
                loadFrame(photo, cache).use { frame ->
                    val values = FloatArray(frame.features.count * 128)
                    frame.features.withDescriptors { if (values.isNotEmpty()) it.get(0, 0, values) }
                    summaries += PhotoFeatureSummary.siftMean(photo.id, values)
                }
            }
            progress(Progress("Detecting SIFT features", photos.size, photos.size))
            pipelineTrace.completed(PipelineStage.FEATURES, "features/")
            // The admitted <=12-photo compatibility profile uses exhaustive bounded
            // summary retrieval. Candidate evidence is never registered coverage.
            val candidateGraph = OverlapGraph(maxNeighbors = 12).build(summaries)
            val photoIndex = photos.withIndex().associate { it.value.id to it.index }
            val candidates = candidateGraph.pairs.map { pair ->
                minOf(photoIndex.getValue(pair.firstPhotoId), photoIndex.getValue(pair.secondPhotoId)) to
                    maxOf(photoIndex.getValue(pair.firstPhotoId), photoIndex.getValue(pair.secondPhotoId))
            }
            // Retrieval candidates only schedule verification. Only a successful
            // measured initializer can admit an edge to registration neighbors.
            val verifiedPairKeys = linkedSetOf<Pair<Int,Int>>()
            var best: JSONObject? = null
            var bestScore = -1.0
            var anchorPair: Pair<Int, Int>? = null
            var anchorMatches: PairMatches? = null
            val failures = JSONArray()
            for ((index, pair) in candidates.withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Verifying overlapping photo pairs", index, candidates.size))
                val first = photos[pair.first]; val second = photos[pair.second]
                loadFrame(first, cache).use { a -> loadFrame(second, cache).use { b ->
                    val fA = min(a.projection.fx, a.projection.fy)
                    val fB = min(b.projection.fx, b.projection.fy)
                    val rows = matches(a, b)
                    if (rows.size >= 20) {
                        val result = JSONObject(nativeCall(nativeToken, progress) { NativeCore.reconstructCancellable(rows.rows, 1.5 / min(fA, fB), nativeToken) })
                        coroutineContext.ensureActive()
                        if (!result.has("error")) {
                            verifiedPairKeys.add(pair)
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
            }
            progress(Progress("Verifying overlapping photo pairs", candidates.size, candidates.size))
            val output = best ?: error("No reliable sparse preview. Use a textured, non-flat object, more overlap, and sideways movement. $failures")
            pipelineTrace.completed(PipelineStage.MATCHING, "$artifactKey.json#verifiedMatches")
            val selected = requireNotNull(anchorPair)
            val pairTracks = requireNotNull(anchorMatches)
            val cameraArray = JSONArray()
            cameraArray.put(JSONObject().put("photoId", photos[selected.first].id).put("rotation", JSONArray(listOf(1,0,0,0,1,0,0,0,1)))
                .put("translation", JSONArray(listOf(0,0,0))).put("kind", "anchor"))
            cameraArray.put(JSONObject().put("photoId", photos[selected.second].id).put("rotation", output.getJSONArray("rotation"))
                .put("translation", output.getJSONArray("translation")).put("kind", "anchor"))
            var cameraFailures = JSONArray()
            val first = photos[selected.first]; val second = photos[selected.second]
            val anchorPoints = output.getJSONArray("points")
            val outstanding = photos.indices.filter { it != selected.first && it != selected.second }
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
                        val ac = matches(a, c).destinationBySource()
                        val bc = matches(b, c).destinationBySource()
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
                            val pose = JSONObject(nativeCall(nativeToken, progress) { NativeCore.registerCameraCancellable(packed.toDoubleArray(),2.0 / fC,nativeToken) })
                            coroutineContext.ensureActive()
                            if (pose.has("error")) cameraFailures.put(JSONObject().put("photoId",third.id).put("reason",pose.getString("error")))
                            else cameraArray.put(pose.put("photoId",third.id).put("kind","registered"))
                        }
                    }
                }
            } }
            progress(Progress("Registering additional camera poses", outstanding.size, outstanding.size))
            // Extend from verified landmarks in one shared frame. This also runs
            // when manual PnP aligned every photo, so a complete camera frontier is
            // saved without pretending that it is a completed reconstruction.
            try {
                val templates=photos.map { photo -> loadFrame(photo,cache).use { frame ->
                    SparseView(frame.width,frame.height,min(frame.projection.fx,frame.projection.fy),
                        frame.xy.copyOf(),frame.colors.copyOf(),
                        doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
                        doubleArrayOf(0.0,0.0,0.0),projection=frame.projection)
                } }
                val sourcePhotoIds=photos.map {it.id}
                val initial=(0 until cameraArray.length()).map { local ->
                    val camera=cameraArray.getJSONObject(local)
                    val photo=photoIndex.getValue(camera.getString("photoId"))
                    RegisteredFrame(photo,templates[photo].copy(
                        rotation=DoubleArray(9) { camera.getJSONArray("rotation").getDouble(it) },
                        translation=DoubleArray(3) { camera.getJSONArray("translation").getDouble(it) }))
                }
                val anchors=initial.filter {it.photo==selected.first || it.photo==selected.second}
                check(anchors.size==2)
                val neighbors=Array(photos.size) {linkedSetOf<Int>()}
                for((a,b) in verifiedPairKeys) {neighbors[a].add(b);neighbors[b].add(a)}
                check(neighbors.all {it.size<=candidateGraph.maxNeighbors})
                val registrationJob=coroutineContext[kotlinx.coroutines.Job]
                val cancelled={registrationJob?.isActive==false}
                val frontierStore=SparseFrontierCheckpointStore(directory.canonicalFile)
                var checkpointRejection:String?=null
                var checkpointPublished=false
                var resumedCheckpoint=false
                val checkpointBytes=frontierStore.load(fingerprint,cancelled)
                var resume=if(checkpointBytes==null)null else try {
                    RegistrationFrontier.decode(checkpointBytes)
                } catch(failure: java.io.IOException) {
                    checkpointRejection="Malformed registration frontier; recomputed"
                    null
                } catch(failure: IllegalArgumentException) {
                    checkpointRejection="Invalid registration frontier; recomputed"
                    null
                }
                if(resume!=null) {
                    val recovered=requireNotNull(resume)
                    val recoveredById=recovered.cameras.associateBy {it.photoId}
                    val sameAnchors=anchors.all { anchor ->
                        val camera=recoveredById[sourcePhotoIds[anchor.photo]]
                        camera!=null && camera.rotation.toDoubleArray().contentEquals(anchor.view.rotation) &&
                            camera.translation.toDoubleArray().contentEquals(anchor.view.translation)
                    }
                    if(recovered.photoIds.toSet()!=sourcePhotoIds.toSet() || !sameAnchors) {
                        checkpointRejection="Registration photos or verified anchor frame changed; recomputed"
                        resume=null
                    } else {
                        // Keep checkpoint poses in their original common frame and add
                        // any freshly verified manual cameras missing from that prefix.
                        val merged=recovered.cameras.toMutableList()
                        for(camera in initial)if(sourcePhotoIds[camera.photo] !in recoveredById)
                            merged.add(FrontierCamera(sourcePhotoIds[camera.photo],camera.view.rotation.toList(),camera.view.translation.toList()))
                        val registeredIds=merged.map {it.photoId}.toSet()
                        resume=RegistrationFrontier(recovered.inputIdentity,sourcePhotoIds,merged,
                            recovered.failureReasons.filterKeys {it !in registeredIds},recovered.trackSnapshot)
                    }
                }
                val nativeGeometry=NativeFrontierGeometry.forOperation(nativeToken)
                suspend fun grow(frontier:RegistrationFrontier?)=IncrementalRegistration.grow(
                    templates,if(frontier==null)initial else anchors,::featurePairs,
                    nativeGeometry.triangulate,nativeGeometry.register,
                    photoIds=sourcePhotoIds,resume=frontier,
                    checkpoint={ value ->
                        checkpointPublished=frontierStore.publish(fingerprint,value.encode(),cancelled) || checkpointPublished
                    },neighbors={ photo -> neighbors[photo].toSet() },pairBudget=100000,
                    progress={ stage,done,total -> progress(Progress(stage,done,total)) })
                val resumeCameraIds=resume?.cameras?.map {it.photoId}?.toSet() ?: emptySet()
                val growth=if(resume==null)grow(null) else try {
                    grow(resume).also {resumedCheckpoint=true}
                } catch(failure: IllegalArgumentException) {
                    // These checks precede all matching/native work in grow. An
                    // unrelated algorithm/pose failure must not become a cache miss.
                    if(failure.message !in setOf("Stale registration frontier","Resume changed anchor frame"))throw failure
                    checkpointRejection="Registration feature inputs or anchor frame changed; recomputed"
                    grow(null)
                }
                coroutineContext.ensureActive()
                val existingById=(0 until cameraArray.length()).associate {
                    cameraArray.getJSONObject(it).getString("photoId") to it
                }
                val anchorIds=anchors.map {sourcePhotoIds[it.photo]}.toSet()
                for(camera in growth.cameras) {
                    val id=sourcePhotoIds[camera.photo]
                    if(id in anchorIds)continue // Preserve the independently verified gauge exactly.
                    val row=JSONObject().put("photoId",id)
                        .put("rotation",JSONArray(camera.view.rotation.toList()))
                        .put("translation",JSONArray(camera.view.translation.toList()))
                        .put("kind",if(resumedCheckpoint && id in resumeCameraIds)"resumed" else "incremental")
                    val existing=existingById[id]
                    if(existing==null)cameraArray.put(row) else cameraArray.put(existing,row)
                }
                val initialIds=initial.map {sourcePhotoIds[it.photo]}.toSet()
                val attempts=JSONArray()
                for(attempt in growth.attempts)attempts.put(JSONObject()
                    .put("photoId",sourcePhotoIds[attempt.photo]).put("registeredBasis",attempt.registeredBasis)
                    .put("candidateTracks",attempt.tracks).put("accepted",attempt.accepted).put("reason",attempt.reason))
                output.put("incrementalRegistration",JSONObject().put("initialCameraCount",initial.size)
                    .put("addedCameraCount",growth.cameras.count {sourcePhotoIds[it.photo] !in initialIds})
                    .put("attempts",attempts).put("sourcePhotoIds",JSONArray(sourcePhotoIds))
                    .put("verifiedNeighborPairs",verifiedPairKeys.size)
                    .put("checkpointStageIdentity",fingerprint).put("checkpointPublished",checkpointPublished)
                    .put("checkpointResumed",resumedCheckpoint)
                    .put("checkpointRejection",checkpointRejection ?: JSONObject.NULL)
                    .put("remainingPhotoIds",JSONArray(growth.unregistered.map {sourcePhotoIds[it]})))
                val remaining=JSONArray()
                for(photo in growth.unregistered)remaining.put(JSONObject().put("photoId",sourcePhotoIds[photo])
                    .put("reason",growth.attempts.lastOrNull {it.photo==photo}?.reason ?:
                        "No connected, triangulated landmark support"))
                cameraFailures=remaining
            } catch(cancelled: java.util.concurrent.CancellationException) {throw cancelled}
            catch(failure: Exception) {
                output.put("incrementalRegistrationFailure",failure.message ?: "Registration expansion unavailable")
            }
            // M03: Build a shared observation graph across *registered* views, including points not seen
            // by both anchors. Native results state their actual gauge and retained support.
            // Accepted anchor landmarks are actual two-view support, even if later refinement fails.
            // Stable source IDs derive from the exact feature keys, never temporary native ordinals.
            val anchorIds = (0 until anchorPoints.length()).map { index ->
                val match = anchorPoints.getJSONArray(index).getInt(8)
                require(match in 0 until pairTracks.size)
                minOf(SourceObservation(first.id,pairTracks.firstKeys[match]).identity(),
                    SourceObservation(second.id,pairTracks.secondKeys[match]).identity())
            }.toSet()
            var acceptedSparseSupport: Map<Int,Set<String>> = (0 until anchorPoints.length()).associate { index ->
                anchorPoints.getJSONArray(index).getInt(8) to setOf(first.id,second.id)
            }
            var sparseThresholdNormalized=1.5/minOf(output.getDouble("firstFocalPixels"),output.getDouble("secondFocalPixels"))
            val qualityByPhoto=photos.associate { photo ->
                require(photo.sharpness.isFinite() && photo.sharpness>=0)
                photo.id to photo.sharpness
            }
            val maximumSharpness=qualityByPhoto.values.maxOrNull() ?: 0.0
            var denseSelection: DenseViewSelection.Plan? = DenseViewSelection.select(
                (0 until cameraArray.length()).map { index ->
                    val camera=cameraArray.getJSONObject(index)
                    val rotation=camera.getJSONArray("rotation");val translation=camera.getJSONArray("translation")
                    val centre=(0..2).map { column -> -(0..2).sumOf { row -> rotation.getDouble(row*3+column)*translation.getDouble(row) } }
                    val id=camera.getString("photoId")
                    DenseViewSelection.Camera(id,centre,if(id==first.id || id==second.id)anchorIds else emptySet(),
                        if(maximumSharpness>0)qualityByPhoto.getValue(id)/maximumSharpness else 0.0)
                })
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
                }.take(66)
                for ((step, pair) in graphPairs.withIndex()) {
                    coroutineContext.ensureActive()
                    progress(Progress("Verifying cross-camera feature tracks", step, graphPairs.size))
                    val correspondence = featurePairs(registeredPhotoIndices[pair.first],registeredPhotoIndices[pair.second])
                    for (k in correspondence.first.indices) {
                        graph.add(pair.first,correspondence.first[k],pair.second,correspondence.second[k])
                    }
                }
                progress(Progress("Verifying cross-camera feature tracks", graphPairs.size, graphPairs.size))
                val nativeBatch = graph.nativeSnapshot(registeredPhotoIndices.map { photos[it].id })
                val observations = nativeBatch.rows
                output.put("multiViewCandidateTracks", graph.matchedTrackCount())
                output.put("multiViewAcceptedEdges", graph.acceptedEdges)
                output.put("multiViewRejectedEdges", graph.rejectedEdges)
                if (observations.size >= 14) {
                    val poses = DoubleArray(views.size * 12) { i ->
                        val camera = views[i / 12]
                        if (i % 12 < 9) camera.rotation[i % 12] else camera.translation[i % 12 - 9]
                    }
                    progress(Progress("Triangulating and refining sparse geometry", 0, 1))
                    val threshold = 3.0 / views.minOf { it.focalPixels }
                    val refined = JSONObject(nativeCall(nativeToken, progress) { NativeCore.reconstructMultiviewCancellable(poses, observations, threshold, 12,nativeToken) })
                    coroutineContext.ensureActive()
                    if (!refined.has("error") &&
                        refined.optInt("pointCount", 0) >= (output.getInt("pointCount") * .85).toInt() &&
                        refined.getDouble("medianErrorAfterNormalized") < threshold * 2) {
                        // Joint BA starts ONLY from geometrically accepted M03 sparse tracks.
                        // It uses the same observation graph and a checked native gauge,
                        // and publishes nothing unless the robust objective improves.
                        var selectedGeometry = refined
                        var jointAccepted = false
                        if (cameraArray.length() >= 3 && refined.getJSONArray("points").length() >= 24) {
                            try {
                                coroutineContext.ensureActive()
                                progress(Progress("Joint camera and point bundle adjustment", 0, 1))
                                val cameraRows = refined.getJSONArray("cameras")
                                val baCameras = DoubleArray(cameraRows.length() * 12) { i ->
                                    val pose = cameraRows.getJSONObject(i / 12)
                                    if (i % 12 < 9) pose.getJSONArray("rotation").getDouble(i % 12)
                                    else pose.getJSONArray("translation").getDouble(i % 12 - 9)
                                }
                                val seeds = refined.getJSONArray("points")
                                val baPoints = DoubleArray(seeds.length() * 7) { i ->
                                    val row = seeds.getJSONArray(i / 7)
                                    if (i % 7 == 0) row.getDouble(8)
                                    else row.getDouble(i % 7 - 1)
                                }
                                val joint = if(executionProfile==DensePreviewProfile.VerifiedRefinedCompact) {
                                    val seeded=(0 until seeds.length()).map {seeds.getJSONArray(it).getInt(8)}.toSet()
                                    val pixelValues=ArrayList<Double>()
                                    for(base in observations.indices step 7) {
                                        if(observations[base].toInt() !in seeded)continue
                                        val camera=observations[base+1].toInt()
                                        val projection=requireNotNull(views[camera].projection)
                                        val distorted=projection.distort(observations[base+2],observations[base+3])
                                        pixelValues+=observations[base];pixelValues+=observations[base+1]
                                        pixelValues+=distorted.first*projection.fx+projection.cx
                                        pixelValues+=distorted.second*projection.fy+projection.cy
                                        for(k in 4..6)pixelValues+=observations[base+k]
                                    }
                                    val intrinsicRows=DoubleArray(views.size*11)
                                    val groupIds=Array(views.size) { camera ->
                                        val view=views[camera];val projection=requireNotNull(view.projection)
                                        val row=doubleArrayOf(view.width.toDouble(),view.height.toDouble(),projection.fx,projection.fy,
                                            projection.cx,projection.cy,projection.k1,projection.k2,projection.p1,projection.p2,projection.k3)
                                        row.copyInto(intrinsicRows,camera*11)
                                        val source=photos[registeredPhotoIndices[camera]]
                                        val lensIdentity=if(activeProfile!=null) "measured-project:${sha256Utf8(calibrationSource)}" else runCatching {
                                            val exif=ExifInterface(repository.photoFile(source))
                                            val make=exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
                                            val model=exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
                                            val lens=exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty()
                                            // Unknown lens sharing must not manufacture multi-view observability.
                                            if(make.isBlank()||model.isBlank()||lens.isBlank()) "unknown-lens:${source.id}"
                                            else "estimated-exif:$make:$model:$lens:${source.focal35mm}"
                                        }.getOrDefault("unknown-lens:${source.id}")
                                        sha256Utf8(lensIdentity+row.joinToString(":") { java.lang.Double.toHexString(it) })
                                    }
                                    JSONObject(nativeCall(nativeToken,progress) {
                                        NativeCore.bundleAdjustProblemCancellable(baCameras,baPoints,pixelValues.toDoubleArray(),
                                            intrinsicRows,groupIds,nativeBatch.photoIds.toTypedArray(),nativeBatch.trackIds.toTypedArray(),
                                            activeProfile!=null,true,threshold,15,nativeToken)
                                    })
                                } else JSONObject(nativeCall(nativeToken, progress) { NativeCore.bundleAdjustCancellable(baCameras, baPoints, observations, threshold, 15,nativeToken) })
                                coroutineContext.ensureActive()
                                if(!joint.has("error") && executionProfile==DensePreviewProfile.VerifiedRefinedCompact) {
                                    require(joint.getInt("schema")==6 && joint.getString("representation")=="sparse_points" && joint.getString("scale")=="arbitrary")
                                    require(joint.getJSONArray("cameraIntrinsics").length()==views.size)
                                    require(joint.getString("intrinsicPolicy").isNotBlank())
                                    val proposals=joint.getInt("loopProposalCount")
                                    val verified=joint.getInt("verifiedLoopCount")
                                    val accepted=joint.getInt("acceptedLoopCorrections")
                                    require(proposals in 0..64 && verified in 0..proposals && accepted in 0..verified)
                                    require(joint.getInt("acceptedIntrinsicGroups") in 0..views.size)
                                }
                                val acceptable = !joint.has("error") &&
                                    joint.optInt("pointCount") >= (seeds.length() * 0.90).toInt() &&
                                    joint.optDouble("robustCostAfter", Double.POSITIVE_INFINITY) <=
                                        joint.optDouble("robustCostBefore", 0.0) + 1e-12 &&
                                    joint.optDouble("medianErrorAfterNormalized", Double.POSITIVE_INFINITY) <= threshold * 2 &&
                                    joint.optDouble("medianErrorAfterNormalized", Double.POSITIVE_INFINITY) <=
                                        refined.getDouble("medianErrorAfterNormalized") * 1.2 + 1e-8
                                if (acceptable) {
                                    joint.optJSONArray("cameraIntrinsics")?.let { models ->
                                        require(models.length()==views.size)
                                        for(camera in views.indices) {
                                            val row=models.getJSONArray(camera);require(row.length()==11)
                                            require(row.getDouble(0)==views[camera].width.toDouble() && row.getDouble(1)==views[camera].height.toDouble())
                                            val parameters=DoubleArray(9){row.getDouble(it+2)}
                                            require(parameters.all(Double::isFinite))
                                            LensProjection(parameters[0],parameters[1],parameters[2],parameters[3],parameters[4],parameters[5],parameters[6],parameters[7],parameters[8])
                                        }
                                    }
                                    selectedGeometry = joint
                                    jointAccepted = true
                                    output.put("bundleAdjustment", JSONObject()
                                        .put("solver", "coupled_huber_schur_lm")
                                        .put("anchorCamerasFixed", joint.getInt("anchorCamerasFixed"))
                                        .put("gaugeMode",joint.optString("gaugeMode","two_anchors"))
                                        .put("attemptedIterations", joint.getInt("iterations"))
                                        .put("acceptedSteps", joint.getInt("acceptedSteps"))
                                        .put("inputObservations", joint.getInt("inputObservations"))
                                        .put("usableObservations", joint.getInt("usableObservations"))
                                        .put("rejectedObservations", joint.getInt("rejectedObservations"))
                                        .put("robustCostBefore", joint.getDouble("robustCostBefore"))
                                        .put("robustCostAfter", joint.getDouble("robustCostAfter"))
                                        .put("medianBeforeNormalized", joint.getDouble("medianErrorBeforeNormalized"))
                                        .put("medianAfterNormalized", joint.getDouble("medianErrorAfterNormalized"))
                                        .put("intrinsicPolicy",joint.optString("intrinsicPolicy","fixed_legacy_normalized"))
                                        .put("acceptedIntrinsicGroups",joint.optInt("acceptedIntrinsicGroups",0))
                                        .put("loopProposalCount",joint.optInt("loopProposalCount",0))
                                        .put("verifiedLoopCount",joint.optInt("verifiedLoopCount",0))
                                        .put("acceptedLoopCorrections",joint.optInt("acceptedLoopCorrections",0)))
                                } else {
                                    output.put("bundleAdjustmentFailure", if (joint.has("error"))
                                        joint.getString("error") else "Joint BA quality gate rejected result")
                                }
                            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                output.put("bundleAdjustmentFailure", failure.message ?: "Joint BA failed")
                            }
                            progress(Progress("Joint camera and point bundle adjustment", 1, 1))
                        } else {
                            output.put("bundleAdjustmentFailure", "Joint BA requires at least 3 registered cameras and 24 sparse points")
                        }
                        val newPoints = selectedGeometry.getJSONArray("points")
                        val newCameras = selectedGeometry.getJSONArray("cameras")
                        check(newCameras.length() == cameraArray.length())
                        val retainedSupport = linkedMapOf<Int,Set<Int>>()
                        val evidence=selectedGeometry.getJSONArray("trackEvidence")
                        require(evidence.length()==newPoints.length()) { "Missing accepted sparse support" }
                        for(index in 0 until evidence.length()) {
                            val item=evidence.getJSONObject(index)
                            val trackValue=item.getDouble("track")
                            require(trackValue.isFinite() && trackValue%1.0==0.0 && trackValue>=0 && trackValue<nativeBatch.trackIds.size)
                            val support=item.getJSONArray("supportingCameras")
                            val cameras=List(support.length()) { i ->
                                val value=support.getDouble(i)
                                require(value.isFinite() && value%1.0==0.0 && value>=0 && value<views.size)
                                value.toInt()
                            }
                            require(cameras.distinct().size==cameras.size)
                            require(retainedSupport.put(trackValue.toInt(),cameras.toSet())==null)
                        }
                        val correctedPoses=List(newCameras.length()) { i ->
                            val camera=newCameras.getJSONObject(i)
                            DoubleArray(12) { k -> if(k<9)camera.getJSONArray("rotation").getDouble(k) else camera.getJSONArray("translation").getDouble(k-9) }
                        }
                        val acceptedTracks=(0 until newPoints.length()).map { i ->
                            val track=newPoints.getJSONArray(i).getDouble(8)
                            require(track.isFinite() && track%1.0==0.0 && track>=0 && track<nativeBatch.trackIds.size)
                            track.toInt()
                        }
                        require(acceptedTracks.distinct().size==acceptedTracks.size && acceptedTracks.toSet()==retainedSupport.keys)
                        val verifiedSelection=DenseViewSelection.select(DenseViewSelection.fromVerifiedNativeTracks(
                            nativeBatch.photoIds,correctedPoses,nativeBatch.trackIds,observations,retainedSupport,
                            nativeBatch.photoIds.map {qualityByPhoto.getValue(it)},List(views.size){true}))
                        // Validate every support/mapping field before replacing accepted anchor poses.
                        for (camera in 0 until cameraArray.length()) {
                            val original = cameraArray.getJSONObject(camera)
                            val corrected = newCameras.getJSONObject(camera)
                            original.put("rotation", corrected.getJSONArray("rotation"))
                                .put("translation", corrected.getJSONArray("translation"))
                                .put("gaugeFixed", corrected.optBoolean("fixed",camera < 2))
                            selectedGeometry.optJSONArray("cameraIntrinsics")?.let { models ->
                                original.put("refinedIntrinsics",models.getJSONArray(camera))
                                    .put("intrinsicPolicy",selectedGeometry.getString("intrinsicPolicy"))
                            }
                        }
                        denseSelection=verifiedSelection
                        acceptedSparseSupport=retainedSupport.mapValues { (_,cameras) -> cameras.map {nativeBatch.photoIds[it]}.toSet() }
                        sparseThresholdNormalized=threshold
                        output.put("sparseSourceTrackIds",JSONArray(nativeBatch.trackIds))
                            .put("sparseTrackEvidence",evidence)
                        val parallax = (0 until newPoints.length()).map { newPoints.getJSONArray(it).getDouble(7) }.sorted()
                        output.put("schema", if (jointAccepted) 6 else 3).put("points", newPoints)
                            .put("pointCount", newPoints.length())
                            .put("medianParallaxDegrees", parallax[parallax.size / 2])
                            .put("medianErrorNormalized", selectedGeometry.getDouble("medianErrorAfterNormalized"))
                            .put("multiViewStats", JSONObject()
                                .put("optimization", "gauge_fixed_alternating_sparse")
                                .put("initialTracks", refined.getInt("inputTracks"))
                                .put("rejectedTracks", refined.getInt("rejectedTracks"))
                                .put("iterations", refined.getInt("iterations"))
                                .put("medianBeforeNormalized", refined.getDouble("medianErrorBeforeNormalized"))
                                .put("medianAfterNormalized", refined.getDouble("medianErrorAfterNormalized")))
                        geometryWarning = if (jointAccepted) "Coupled robust sparse bundle adjustment optimized the accepted camera/point window with the reported native gauge. Still no dense surface, texture or measured scale." else "M03 sparse multi-view geometry retained; joint optimization unavailable or rejected. Still no dense surface, texture or measured scale."
                    } else {
                        geometryWarning = if (refined.has("error"))
                            "M03 multi-view refinement rejected: ${refined.getString("error")}. Retained M02 anchor geometry."
                        else "M03 multi-view quality gate rejected geometry; retained the M02 anchor point cloud."
                    }
                }
                progress(Progress("Triangulating and refining sparse geometry", 1, 1))
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                geometryWarning = "Multi-view geometry unavailable: ${failure.message ?: "invalid observations"}. Retained the M02 anchor point cloud."
            }
            pipelineTrace.completed(PipelineStage.SPARSE, "$artifactKey.json#points")
            // M09: retain all accepted geometry if appearance reconstruction is unavailable.
            // Native CPU depth remains a fallback when conservative triangle meshing fails.
            try {
                coroutineContext.ensureActive()
                progress(Progress("Estimating stereo depth, meshing and projecting photographs", 0, 1))
                val typedSurface = DensePreview.reconstructTyped(
                    repository, projectId, artifactKey, photos, cameraArray,
                    output.getJSONArray("points"), assumedFocal35, activeProfile, nativeToken,
                    pipelineSnapshot, denseSelection, executionProfile, acceptedSparseSupport, sparseThresholdNormalized,
                ) { stage, done, total -> progress(Progress(stage, done, total)) }
                typedSurface.outcomes.forEach(pipelineTrace::record)
                val surface = typedSurface.payload
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
            output.put("cameras",cameraArray).put("registeredCameras",cameraArray.length()).put("cameraRegistrationFailures",cameraFailures)
            output.put("artifactKey", artifactKey).put("settingsFingerprint", fingerprint).put("revision", revision).put("photosExamined", photos.size).put("pairsExamined", candidates.size)
                .put("engineVersion", "m20-agent31-60-v2").put("calibrationProfileApplied", activeProfile != null).put("failedPairs", failures)
                .put("warning", geometryWarning)
                .put("pipeline", pipelineJson(pipelineSnapshot, capacityProfile, pipelineTrace))
            // Always preserve measured evidence and actionable failure reasons in
            // the same revision-bound JSON that the library reopens.
            output.put("scanDiagnostics",ScanQualityReport.build(output,photos.size))
            coroutineContext.ensureActive()
            check(repository.dao.project(projectId)?.revision == revision) { "Photos changed; discard stale reconstruction." }
            repository.writeAtomic(File(directory, "$artifactKey.json"), output.toString())
            progress(Progress("Saving diagnostic exports", 0, 2))
            val points = output.getJSONArray("points")
            val packed = DoubleArray(points.length() * 6) { i -> points.getJSONArray(i / 6).getDouble(i % 6) }
            for ((index, extension) in listOf("glb", "ply").withIndex()) {
                coroutineContext.ensureActive()
                val target = File(directory, "$artifactKey.$extension")
                val temp = File(directory, "${target.name}.partial")
                try {
                    NativeCore.exportPoints(packed, temp.path, extension == "glb")
                    FileOutputStream(temp, true).use { it.fd.sync() }
                    check(temp.renameTo(target)) { "Could not publish $extension export." }
                } finally { temp.delete() }
                progress(Progress("Saving diagnostic exports", index + 1, 2))
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
                    try {
                        NativeCore.exportPointsTyped(densePacked,temporary.path,extension=="dense.glb",true)
                        FileOutputStream(temporary,true).use { it.fd.sync() }
                        check(temporary.renameTo(target)) { "Could not publish dense point-cloud export" }
                    } finally { temporary.delete() }
                }
                progress(Progress("Saving dense point-cloud exports",2,2))
            }
            coroutineContext.ensureActive()
            publicationComplete=true
            output
        } finally {
            if(!publicationComplete) {
                // Only this fresh attempt's exact reserved names can be removed.
                for(extension in artifactExtensions) {
                    File(directory,"$artifactKey.$extension.partial").delete()
                    File(directory,"$artifactKey.$extension").delete()
                }
            }
        }
    }

    private suspend fun <T> nativeCall(token:Long, progress:suspend(Progress)->Unit, block:()->T):T =
        withNativeStageProgress(token,{ stage,done,total -> progress(Progress(stage,done,total)) },block)

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

    private suspend fun loadFrame(photo: ScanPhoto, cache: File): Frame {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancelled = { job?.isActive == false }
        coroutineContext.ensureActive()
        val settings = extractor.settings.identity(decoderIdentity)
        val identity = FeatureCacheIdentity(photo.sha256, decoderIdentity, settings, org.opencv.core.Core.VERSION)
        val store = FeatureCache(cache, 64L * 1024 * 1024)
        val cached = store.load(identity)
        if (cached is FeatureCacheRead.Hit) {
            val record = cached.record
            var descriptors: Mat? = null
            try {
                val owned = if (record.count == 0) Mat() else Mat(record.count, 128, CvType.CV_32F)
                descriptors = owned
                if (record.count != 0) owned.put(0, 0, record.descriptors())
                coroutineContext.ensureActive()
                val features = FeatureFrame(photo.id, photo.sha256, decoderIdentity, settings,
                    record.width, record.height, record.coordinates(), record.colors(), owned,
                    lens(photo, record.width, record.height))
                val frame = Frame(features)
                descriptors = null
                return frame
            } finally { descriptors?.release() }
        }
        val features = extractor.extract(photo.id, photo.sha256, repository.photoFile(photo),
            decoderIdentity, { width, height -> lens(photo, width, height) }, cancelled)
        try {
            val values = FloatArray(features.count * 128)
            features.withDescriptors { if (values.isNotEmpty()) it.get(0, 0, values) }
            // Cache failure is a miss/recompute decision, not a reconstruction failure.
            store.store(CachedFeatures(identity, features.width, features.height,
                features.coordinates(), features.colors(), values))
            coroutineContext.ensureActive()
            return Frame(features)
        } catch (failure: Throwable) { features.close(); throw failure }
    }

    private suspend fun matches(a: Frame, b: Frame): PairMatches {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val canonical = a.features.photoId < b.features.photoId
        val first = if (canonical) a.features else b.features
        val second = if (canonical) b.features else a.features
        val tentative = matcher.match(first, second,
            PairCandidate(first.photoId, second.photoId, 0.0, "coordinator-request-not-verified-overlap")) {
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
