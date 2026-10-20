package com.realityforge.scanner.engine

import android.graphics.Color
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** M08: calibrated joint SfM, bounded CPU photometric depth and conservative open surface meshing. */
class SparsePipeline(private val repository: ProjectRepository) {
    data class Progress(val stage: String, val completed: Int, val total: Int)
    private data class PairMatches(val rows: DoubleArray, val firstKeys: IntArray, val secondKeys: IntArray) {
        val size: Int get() = firstKeys.size
        fun destinationBySource(): Map<Int, Int> = firstKeys.indices.associate { firstKeys[it] to secondKeys[it] }
    }
    private data class Frame(
        val width: Int, val height: Int, val xy: FloatArray, val colors: IntArray,
        val descriptors: Mat, val projection: LensProjection,
    ) : Closeable { override fun close() { descriptors.release() } }
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
        val photos = repository.dao.photos(projectId).take(12)
        require(photos.size >= 2) { "Import at least two overlapping photographs." }
        val directory = repository.directory(projectId)
        val revision = project.revision
        activeProfile = LensCalibration.read(repository, projectId)
        fallbackFocal35 = assumedFocal35
        val calibrationFile = LensCalibration.file(repository, projectId)
        val calibrationFingerprint = if (calibrationFile.isFile) calibrationFile.readText() else "pinhole-no-profile"
        val fingerprintInput = "m16-registration-frontier-1|${DenseWorkload.tier(Runtime.getRuntime().maxMemory())}|$calibrationFingerprint|opencv-4.12.0|sift-3500|ratio-.67|edge-1600|$assumedFocal35|" + photos.joinToString("|") { it.sha256 }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintInput.toByteArray()).joinToString("") { "%02x".format(it) }
        val artifactKey = "sparse-m16-r$revision-${fingerprint.take(16)}"
        val cache = File(directory, "features").apply { mkdirs() }
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
        for ((index, photo) in photos.withIndex()) {
            coroutineContext.ensureActive()
            progress(Progress("Detecting SIFT features", index, photos.size))
            loadFrame(photo, cache).close()
        }
        // Compare local neighbors for the initial pair; extra cameras are registered against its tracked points.
        val candidates = buildList {
            for (gap in 1..3) for (i in 0 until photos.size - gap) add(i to i + gap)
        }.take(24)
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
                    val result = JSONObject(NativeCore.reconstruct(rows.rows, 1.5 / min(fA, fB)))
                    coroutineContext.ensureActive()
                    if (!result.has("error")) {
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
                        val pose = JSONObject(NativeCore.registerCamera(packed.toDoubleArray(),2.0 / fC))
                        coroutineContext.ensureActive()
                        if (pose.has("error")) cameraFailures.put(JSONObject().put("photoId",third.id).put("reason",pose.getString("error")))
                        else cameraArray.put(pose.put("photoId",third.id).put("kind","registered"))
                    }
                }
            }
        } }
        progress(Progress("Registering additional camera poses", outstanding.size, outstanding.size))
        // Extend from newly triangulated landmarks, not just the original anchor intersection.
        // Failure leaves every accepted M02 pose and original photograph intact.
        if (cameraArray.length() < photos.size) {
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
                val growth=IncrementalRegistration.grow(templates,initial,::featurePairs,
                    NativeFrontierGeometry::triangulate,NativeFrontierGeometry::register) { stage,done,total ->
                    progress(Progress(stage,done,total))
                }
                coroutineContext.ensureActive()
                for (camera in growth.cameras.drop(initial.size)) {
                    cameraArray.put(JSONObject().put("photoId",photos[camera.photo].id)
                        .put("rotation",JSONArray(camera.view.rotation.toList()))
                        .put("translation",JSONArray(camera.view.translation.toList()))
                        .put("kind","incremental"))
                }
                val attempts=JSONArray()
                for(attempt in growth.attempts) attempts.put(JSONObject()
                    .put("photoId",photos[attempt.photo].id).put("registeredBasis",attempt.registeredBasis)
                    .put("candidateTracks",attempt.tracks).put("accepted",attempt.accepted).put("reason",attempt.reason))
                output.put("incrementalRegistration",JSONObject().put("initialCameraCount",initial.size)
                    .put("addedCameraCount",growth.cameras.size-initial.size).put("attempts",attempts)
                    .put("remainingPhotoIds",JSONArray(growth.unregistered.map { photos[it].id })))
                // Superseded anchor-only failures must not be shown for newly aligned photographs.
                val remaining=JSONArray()
                for(photo in growth.unregistered) remaining.put(JSONObject().put("photoId",photos[photo].id)
                    .put("reason",growth.attempts.lastOrNull { it.photo==photo }?.reason ?:
                        "No connected, triangulated landmark support"))
                cameraFailures=remaining
            } catch(cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch(failure: Exception) {
                output.put("incrementalRegistrationFailure",failure.message ?: "Registration expansion unavailable")
            }
        }
        // M03: Build a shared observation graph across *registered* views, including points not seen
        // by both anchors. The native optimizer fixes the first two cameras to preserve gauge.
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
            val observations = graph.packedRows()
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
                val refined = JSONObject(NativeCore.reconstructMultiview(poses, observations, threshold, 12))
                coroutineContext.ensureActive()
                if (!refined.has("error") &&
                    refined.optInt("pointCount", 0) >= (output.getInt("pointCount") * .85).toInt() &&
                    refined.getDouble("medianErrorAfterNormalized") < threshold * 2) {
                    // Joint BA starts ONLY from geometrically accepted M03 sparse tracks.
                    // It uses the same observation graph, holds both anchor cameras fixed,
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
                            val joint = JSONObject(NativeCore.bundleAdjust(baCameras, baPoints, observations, threshold, 15))
                            coroutineContext.ensureActive()
                            val acceptable = !joint.has("error") &&
                                joint.optInt("pointCount") >= (seeds.length() * 0.90).toInt() &&
                                joint.optDouble("robustCostAfter", Double.POSITIVE_INFINITY) <=
                                    joint.optDouble("robustCostBefore", 0.0) + 1e-12 &&
                                joint.optDouble("medianErrorAfterNormalized", Double.POSITIVE_INFINITY) <= threshold * 2 &&
                                joint.optDouble("medianErrorAfterNormalized", Double.POSITIVE_INFINITY) <=
                                    refined.getDouble("medianErrorAfterNormalized") * 1.2 + 1e-8
                            if (acceptable) {
                                selectedGeometry = joint
                                jointAccepted = true
                                output.put("bundleAdjustment", JSONObject()
                                    .put("solver", "coupled_huber_schur_lm")
                                    .put("anchorCamerasFixed", 2)
                                    .put("attemptedIterations", joint.getInt("iterations"))
                                    .put("acceptedSteps", joint.getInt("acceptedSteps"))
                                    .put("inputObservations", joint.getInt("inputObservations"))
                                    .put("usableObservations", joint.getInt("usableObservations"))
                                    .put("rejectedObservations", joint.getInt("rejectedObservations"))
                                    .put("robustCostBefore", joint.getDouble("robustCostBefore"))
                                    .put("robustCostAfter", joint.getDouble("robustCostAfter"))
                                    .put("medianBeforeNormalized", joint.getDouble("medianErrorBeforeNormalized"))
                                    .put("medianAfterNormalized", joint.getDouble("medianErrorAfterNormalized")))
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
                    for (camera in 0 until cameraArray.length()) {
                        val original = cameraArray.getJSONObject(camera)
                        val corrected = newCameras.getJSONObject(camera)
                        original.put("rotation", corrected.getJSONArray("rotation"))
                            .put("translation", corrected.getJSONArray("translation"))
                            .put("gaugeFixed", camera < 2)
                    }
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
                    geometryWarning = if (jointAccepted) "M06 coupled robust sparse bundle adjustment optimized all registered camera poses (except both fixed anchors) and existing sparse points. Still no dense surface, texture or measured scale." else "M03 sparse multi-view geometry retained; joint optimization unavailable or rejected. Still no dense surface, texture or measured scale."
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
        // M09: retain all accepted geometry if appearance reconstruction is unavailable.
        // Native CPU depth remains a fallback when conservative triangle meshing fails.
        try {
            coroutineContext.ensureActive()
            progress(Progress("Estimating stereo depth, meshing and projecting photographs", 0, 1))
            val surface=DensePreview.reconstruct(repository,projectId,artifactKey,photos,cameraArray,
                output.getJSONArray("points"),assumedFocal35,activeProfile,nativeToken) { stage, done, total -> progress(Progress(stage,done,total)) }
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
            output.put("denseStatus","unavailable")
                .put("denseFailure",failure.message ?: "Unreliable dense depth")
                .put("meshStatus","unavailable")
                .put("meshFailure","Depth prerequisite failed; no supported triangles")
                .put("textureStatus","unavailable")
                .put("textureFailure","Depth or mesh prerequisite unavailable")
        }
        output.put("cameras",cameraArray).put("registeredCameras",cameraArray.length()).put("cameraRegistrationFailures",cameraFailures)
        output.put("artifactKey", artifactKey).put("settingsFingerprint", fingerprint).put("revision", revision).put("photosExamined", photos.size).put("pairsExamined", candidates.size)
            .put("engineVersion", "m16-registration-frontier-1").put("calibrationProfileApplied", activeProfile != null).put("failedPairs", failures)
            .put("warning", geometryWarning)
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
                    NativeCore.exportPoints(densePacked,temporary.path,extension=="dense.glb")
                    FileOutputStream(temporary,true).use { it.fd.sync() }
                    check(temporary.renameTo(target)) { "Could not publish dense point-cloud export" }
                } finally { temporary.delete() }
            }
            progress(Progress("Saving dense point-cloud exports",2,2))
        }
        output
    }

    private fun loadFrame(photo: ScanPhoto, cache: File): Frame {
        val file = File(cache, "${photo.sha256}-sift1600-v1.bin")
        if (file.isFile) runCatching { readFrame(file, photo) }.getOrNull()?.let { return it }
        val bitmap = ImageLoader.upright(repository.photoFile(photo))
        val rgba = Mat(); val gray = Mat(); val keys = MatOfKeyPoint(); val desc = Mat(); val mask = Mat()
        val sift = SIFT.create(3500)
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            sift.detectAndCompute(gray, mask, keys, desc)
            val keypoints = keys.toArray()
            val xy = FloatArray(keypoints.size * 2)
            val colors = IntArray(keypoints.size)
            for ((i, key) in keypoints.withIndex()) {
                xy[2 * i] = key.pt.x.toFloat(); xy[2 * i + 1] = key.pt.y.toFloat()
                colors[i] = bitmap.getPixel(key.pt.x.toInt().coerceIn(0, bitmap.width - 1), key.pt.y.toInt().coerceIn(0, bitmap.height - 1))
            }
            val frame = Frame(bitmap.width, bitmap.height, xy, colors, desc, lens(photo, bitmap.width, bitmap.height))
            writeFrame(file, frame)
            return frame
        } catch (exception: Throwable) { desc.release(); throw exception }
        finally { sift.clear(); rgba.release(); gray.release(); keys.release(); mask.release(); bitmap.recycle() }
    }
    private fun writeFrame(file: File, frame: Frame) {
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { stream ->
                val out = DataOutputStream(stream.buffered())
                out.writeInt(0x52464631); out.writeInt(frame.width); out.writeInt(frame.height); out.writeInt(frame.colors.size)
                for (v in frame.xy) out.writeFloat(v)
                for (v in frame.colors) out.writeInt(v)
                if (frame.colors.isNotEmpty()) {
                    val values = FloatArray(frame.colors.size * 128)
                    frame.descriptors.get(0, 0, values)
                    for (v in values) out.writeFloat(v)
                }
                out.flush(); stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot save feature cache." }
        } finally { temporary.delete() }
    }
    private fun readFrame(file: File, photo: ScanPhoto): Frame {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            check(input.readInt() == 0x52464631)
            val width = input.readInt(); val height = input.readInt(); val count = input.readInt()
            check(width in 3..1600 && height in 3..1600 && count in 0..4000)
            check(file.length() == 16L + count * (8L + 4L + 128L * 4L))
            val xy = FloatArray(count * 2) { input.readFloat() }
            val colors = IntArray(count) { input.readInt() }
            val values = FloatArray(count * 128) { input.readFloat() }
            check(xy.all { it.isFinite() } && values.all { it.isFinite() })
            val desc = if (count == 0) Mat() else Mat(count, 128, CvType.CV_32F).apply { put(0, 0, values) }
            return Frame(width, height, xy, colors, desc, lens(photo, width, height))
        }
    }
    private fun matches(a: Frame, b: Frame): PairMatches {
        if (a.colors.size < 2 || b.colors.size < 2) return PairMatches(doubleArrayOf(), intArrayOf(), intArrayOf())
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val forward = ArrayList<MatOfDMatch>(); val backward = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, forward, 2)
            matcher.knnMatch(b.descriptors, a.descriptors, backward, 2)
            val reverse = HashMap<Int, Int>()
            for (row in backward) { val m = row.toArray(); if (m.size >= 2 && m[0].distance < .67 * m[1].distance) reverse[m[0].queryIdx] = m[0].trainIdx }
            val output = ArrayList<Double>()
            val firstKeys = ArrayList<Int>(); val secondKeys = ArrayList<Int>()
            for (row in forward) {
                val m = row.toArray()
                if (m.size < 2 || m[0].distance >= .67 * m[1].distance) continue
                val i = m[0].queryIdx; val j = m[0].trainIdx
                if (reverse[j] != i) continue
                val uvA = runCatching { a.projection.normalized(a.xy[2*i].toDouble(), a.xy[2*i+1].toDouble()) }.getOrNull() ?: continue
                val uvB = runCatching { b.projection.normalized(b.xy[2*j].toDouble(), b.xy[2*j+1].toDouble()) }.getOrNull() ?: continue
                val c = a.colors[i]
                firstKeys.add(i); secondKeys.add(j)
                output.add(uvA.first); output.add(uvA.second)
                output.add(uvB.first); output.add(uvB.second)
                output.add(Color.red(c).toDouble()); output.add(Color.green(c).toDouble()); output.add(Color.blue(c).toDouble())
            }
            return PairMatches(output.toDoubleArray(), firstKeys.toIntArray(), secondKeys.toIntArray())
        } finally { forward.forEach { it.release() }; backward.forEach { it.release() }; matcher.clear() }
    }
}
