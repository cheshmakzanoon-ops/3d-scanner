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

/** M02: two-view anchor + consistent additional camera poses, never a dense mesh or BA. */
class SparsePipeline(private val repository: ProjectRepository) {
    data class Progress(val stage: String, val completed: Int, val total: Int)
    private data class PairMatches(val rows: DoubleArray, val firstKeys: IntArray, val secondKeys: IntArray) {
        val size: Int get() = firstKeys.size
        fun destinationBySource(): Map<Int, Int> = firstKeys.indices.associate { firstKeys[it] to secondKeys[it] }
    }
    private data class Frame(
        val width: Int, val height: Int, val xy: FloatArray, val colors: IntArray,
        val descriptors: Mat,
    ) : Closeable { override fun close() { descriptors.release() } }

    suspend fun run(projectId: String, assumedFocal35: Double, progress: suspend (Progress) -> Unit): JSONObject = withContext(Dispatchers.Default) {
        require(assumedFocal35 in 10.0..200.0) { "Use a plausible 35 mm-equivalent focal length." }
        check(OpenCVLoader.initLocal()) { "OpenCV could not load on this device." }
        val project = requireNotNull(repository.dao.project(projectId))
        val photos = repository.dao.photos(projectId).take(12)
        require(photos.size >= 2) { "Import at least two overlapping photographs." }
        val directory = repository.directory(projectId)
        val revision = project.revision
        val fingerprintInput = "m02-pnp-1|opencv-4.12.0|sift-3500|ratio-.75|edge-1600|$assumedFocal35|" + photos.joinToString("|") { it.sha256 }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintInput.toByteArray()).joinToString("") { "%02x".format(it) }
        val artifactKey = "sparse-m02-r$revision-${fingerprint.take(16)}"
        val cache = File(directory, "features").apply { mkdirs() }
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
                val fA = ImageLoader.focalPixels(a.width, a.height, first.focal35mm ?: assumedFocal35)
                val fB = ImageLoader.focalPixels(b.width, b.height, second.focal35mm ?: assumedFocal35)
                val rows = matches(a, b, fA, fB)
                if (rows.size >= 20) {
                    val result = JSONObject(NativeCore.reconstruct(rows.rows, 1.5 / min(fA, fB)))
                    coroutineContext.ensureActive()
                    if (!result.has("error")) {
                        val score = result.getInt("pointCount") * min(result.getDouble("medianParallaxDegrees"), 8.0) /
                            (1.0 + result.getDouble("medianErrorNormalized") * (fA + fB) / 2)
                        if (score > bestScore) {
                            result.put("firstPhotoId", first.id).put("secondPhotoId", second.id)
                            result.put("firstFocalPixels", fA).put("secondFocalPixels", fB)
                            result.put("calibration", "Estimated pinhole; no distortion calibration or bundle adjustment in M02")
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
        val cameraFailures = JSONArray()
        val first = photos[selected.first]; val second = photos[selected.second]
        val anchorPoints = output.getJSONArray("points")
        val outstanding = photos.indices.filter { it != selected.first && it != selected.second }
        // The features anchoring world XYZ are identified by matchIndex, not rounded coordinates.
        loadFrame(first, cache).use { a -> loadFrame(second, cache).use { b ->
            val fA = ImageLoader.focalPixels(a.width, a.height, first.focal35mm ?: assumedFocal35)
            val fB = ImageLoader.focalPixels(b.width, b.height, second.focal35mm ?: assumedFocal35)
            for ((step, candidateIndex) in outstanding.withIndex()) {
                coroutineContext.ensureActive()
                progress(Progress("Registering additional camera poses", step, outstanding.size))
                val third = photos[candidateIndex]
                loadFrame(third, cache).use { c ->
                    val fC = ImageLoader.focalPixels(c.width, c.height, third.focal35mm ?: assumedFocal35)
                    // Require a cycle-consistent third keypoint match to BOTH anchor views.
                    val ac = matches(a, c, fA, fC).destinationBySource()
                    val bc = matches(b, c, fB, fC).destinationBySource()
                    val packed = ArrayList<Double>()
                    for (i in 0 until anchorPoints.length()) {
                        val point = anchorPoints.getJSONArray(i)
                        val trackIndex = point.getInt(8)
                        if (trackIndex !in 0 until pairTracks.size) continue
                        val fromA = ac[pairTracks.firstKeys[trackIndex]] ?: continue
                        val fromB = bc[pairTracks.secondKeys[trackIndex]] ?: continue
                        if (fromA != fromB) continue
                        packed.add(point.getDouble(0));packed.add(point.getDouble(1));packed.add(point.getDouble(2))
                        packed.add((c.xy[2 * fromA] - (c.width - 1) / 2.0) / fC)
                        packed.add((c.xy[2 * fromA + 1] - (c.height - 1) / 2.0) / fC)
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
        output.put("cameras",cameraArray).put("registeredCameras",cameraArray.length()).put("cameraRegistrationFailures",cameraFailures)
        output.put("artifactKey", artifactKey).put("settingsFingerprint", fingerprint).put("revision", revision).put("photosExamined", photos.size).put("pairsExamined", candidates.size)
            .put("engineVersion", "m02-pnp-1").put("failedPairs", failures)
            .put("warning", "Additional cameras share the anchor world frame, but points remain TWO-VIEW only. No bundle adjustment, dense mesh, metric scale or texturing.")
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
        output
    }

    private fun loadFrame(photo: ScanPhoto, cache: File): Frame {
        val file = File(cache, "${photo.sha256}-sift1600-v1.bin")
        if (file.isFile) runCatching { readFrame(file) }.getOrNull()?.let { return it }
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
            val frame = Frame(bitmap.width, bitmap.height, xy, colors, desc)
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
    private fun readFrame(file: File): Frame {
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
            return Frame(width, height, xy, colors, desc)
        }
    }
    private fun matches(a: Frame, b: Frame, fA: Double, fB: Double): PairMatches {
        if (a.colors.size < 2 || b.colors.size < 2) return PairMatches(doubleArrayOf(), intArrayOf(), intArrayOf())
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val forward = ArrayList<MatOfDMatch>(); val backward = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, forward, 2)
            matcher.knnMatch(b.descriptors, a.descriptors, backward, 2)
            val reverse = HashMap<Int, Int>()
            for (row in backward) { val m = row.toArray(); if (m.size >= 2 && m[0].distance < .75 * m[1].distance) reverse[m[0].queryIdx] = m[0].trainIdx }
            val output = ArrayList<Double>()
            val firstKeys = ArrayList<Int>(); val secondKeys = ArrayList<Int>()
            for (row in forward) {
                val m = row.toArray()
                if (m.size < 2 || m[0].distance >= .75 * m[1].distance) continue
                val i = m[0].queryIdx; val j = m[0].trainIdx
                if (reverse[j] != i) continue
                val c = a.colors[i]
                firstKeys.add(i); secondKeys.add(j)
                output.add((a.xy[2 * i] - (a.width - 1) / 2.0) / fA)
                output.add((a.xy[2 * i + 1] - (a.height - 1) / 2.0) / fA)
                output.add((b.xy[2 * j] - (b.width - 1) / 2.0) / fB)
                output.add((b.xy[2 * j + 1] - (b.height - 1) / 2.0) / fB)
                output.add(Color.red(c).toDouble()); output.add(Color.green(c).toDouble()); output.add(Color.blue(c).toDouble())
            }
            return PairMatches(output.toDoubleArray(), firstKeys.toIntArray(), secondKeys.toIntArray())
        } finally { forward.forEach { it.release() }; backward.forEach { it.release() }; matcher.clear() }
    }
}
