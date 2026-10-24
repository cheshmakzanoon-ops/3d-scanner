package com.realityforge.scanner.capture.guidance

import com.realityforge.scanner.capture.session.CapturedPhoto
import com.realityforge.scanner.capture.analysis.CaptureQualityEstimator
import com.realityforge.scanner.capture.analysis.LumaFrame
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.imaging.DecodeRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * Production, offline saved-image adapter. ORB matching reports measured image overlap; a
 * homography never implies 3D translation or object coverage. Native two-view reconstruction is
 * attempted only with reported focal metadata and without dominant planar/rotation evidence.
 */
object OpenCvSavedPhotoGuidance : SavedPhotoFeatureExtractor, SavedPhotoPairProvider {
    private const val ENGINE = "opencv-4.12-orb-1000-ratio075-grid4-homography3-native2view-pixel-centres-v2"

    override fun extract(photo: CapturedPhoto, maxEdge: Int, maxFeatures: Int): SavedPhotoGuidanceFeatures {
        require(maxEdge in 128..640 && maxFeatures in 1..1_000)
        check(OpenCVLoader.initLocal()) { "Image overlap analysis is unavailable on this device." }
        val hash = hashOriginal(photo.stagedFile)
        val focal = (photo.metadata.captureMetadata?.focal35mmEquivalent?.value ?: ImageLoader.focal35(photo.stagedFile))
            ?.takeIf { it.isFinite() && it in 10.0..600.0 }
        return ImageLoader.decodeBlocking(DecodeRequest(photo.stagedFile, maxEdge = maxEdge)).use { owned ->
            owned.withBitmap { bitmap ->
                val rgba = Mat()
                val gray = Mat()
                val keys = MatOfKeyPoint()
                val descriptors = Mat()
                val mask = Mat()
                val detector = ORB.create(maxFeatures)
                try {
                    Utils.bitmapToMat(bitmap, rgba)
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    detector.detectAndCompute(gray, mask, keys, descriptors)
                    val points = keys.toArray()
                    check(points.size <= maxFeatures)
                    val xy = FloatArray(points.size * 2)
                    val colors = IntArray(points.size)
                    points.forEachIndexed { index, key ->
                        xy[index * 2] = key.pt.x.toFloat()
                        xy[index * 2 + 1] = key.pt.y.toFloat()
                        colors[index] = bitmap.getPixel(key.pt.x.toInt().coerceIn(0, bitmap.width - 1),
                            key.pt.y.toInt().coerceIn(0, bitmap.height - 1))
                    }
                    val packed = ByteArray(points.size * 32)
                    if (points.isNotEmpty()) {
                        check(descriptors.rows() == points.size && descriptors.cols() == 32 && descriptors.type() == CvType.CV_8U)
                        check(descriptors.get(0, 0, packed) == packed.size)
                    }
                    val luma = ByteArray(bitmap.width * bitmap.height)
                    check(gray.get(0, 0, luma) == luma.size)
                    val quality = CaptureQualityEstimator.measure(LumaFrame(bitmap.width, bitmap.height,
                        luma, 0, 0))
                    SavedPhotoGuidanceFeatures(hash, bitmap.width, bitmap.height, xy, packed, colors, focal, quality)
                } finally {
                    detector.clear(); rgba.release(); gray.release(); keys.release(); descriptors.release(); mask.release()
                }
            }
        }
    }

    override fun compare(reference: SavedPhotoGuidanceFeatures, candidate: SavedPhotoGuidanceFeatures,
        allowPose: Boolean): PipelinePairEvidence {
        check(OpenCVLoader.initLocal()) { "Image overlap analysis is unavailable on this device." }
        val identity = listOf(ENGINE, reference.originalSha256, candidate.originalSha256,
            "${reference.width}x${reference.height}", "${candidate.width}x${candidate.height}",
            reference.focal35mm, candidate.focal35mm, allowPose).joinToString("|")
        val evidenceId = "saved-pair-${sha256(identity.toByteArray(Charsets.UTF_8))}"
        if (reference.colors.size < 2 || candidate.colors.size < 2) return emptyEvidence(evidenceId)
        val left = Mat(reference.colors.size, 32, CvType.CV_8U)
        val right = Mat(candidate.colors.size, 32, CvType.CV_8U)
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val forward = ArrayList<MatOfDMatch>()
        val backward = ArrayList<MatOfDMatch>()
        try {
            left.put(0, 0, reference.descriptors); right.put(0, 0, candidate.descriptors)
            matcher.knnMatch(left, right, forward, 2)
            matcher.knnMatch(right, left, backward, 2)
            val reverse = HashMap<Int, Int>()
            backward.forEach { row ->
                val matches = row.toArray()
                if (passesRatio(matches)) reverse[matches[0].queryIdx] = matches[0].trainIdx
            }
            var raw = 0
            val reciprocal = ArrayList<DMatch>()
            forward.forEach { row ->
                val matches = row.toArray()
                if (passesRatio(matches)) {
                    raw += 1
                    if (reverse[matches[0].trainIdx] == matches[0].queryIdx) reciprocal += matches[0]
                }
            }
            val planar = homographyInliers(reference, candidate, reciprocal)
            val dominantPlaneOrRotation = reciprocal.isNotEmpty() && planar.size.toDouble() / reciprocal.size >= 0.85
            val pose = if (allowPose && !dominantPlaneOrRotation)
                nativePose(reference, candidate, reciprocal) else null
            val inlierIndices = pose?.indices ?: planar
            val selected = inlierIndices.map { reciprocal[it] }
            val cells = selected.map { match ->
                val x = (candidate.xy[match.trainIdx * 2] * 4 / candidate.width).toInt().coerceIn(0, 3)
                val y = (candidate.xy[match.trainIdx * 2 + 1] * 4 / candidate.height).toInt().coerceIn(0, 3)
                y * 4 + x
            }.distinct().size
            val displacement = selected.map { match -> hypot(
                reference.xy[match.queryIdx * 2].toDouble() / reference.width - candidate.xy[match.trainIdx * 2].toDouble() / candidate.width,
                reference.xy[match.queryIdx * 2 + 1].toDouble() / reference.height - candidate.xy[match.trainIdx * 2 + 1].toDouble() / candidate.height) }
            return PipelinePairEvidence(evidenceId, raw, reciprocal.size, selected.size, cells, 16,
                median(displacement), when {
                    pose != null -> GeometricModel.ESSENTIAL_POSE
                    planar.isNotEmpty() -> GeometricModel.HOMOGRAPHY_DOMINANT
                    else -> GeometricModel.UNKNOWN
                }, pose != null, pose?.rotationDegrees, pose?.translationOverDepth, pose?.parallaxDegrees,
                pose?.reprojectionError,
                // Unexplained matches alone do not establish that a subject moved.
                dynamicOutlierFraction = null)
        } finally {
            forward.forEach { it.release() }; backward.forEach { it.release() }
            matcher.clear(); left.release(); right.release()
        }
    }

    private fun passesRatio(matches: Array<DMatch>) = matches.size >= 2 &&
        matches[0].distance < 0.75 * matches[1].distance

    private fun homographyInliers(a: SavedPhotoGuidanceFeatures, b: SavedPhotoGuidanceFeatures,
        matches: List<DMatch>): List<Int> {
        if (matches.size < 4) return emptyList()
        val left = MatOfPoint2f()
        val right = MatOfPoint2f()
        val mask = Mat()
        var homography: Mat? = null
        try {
            left.fromArray(*matches.map { Point(a.xy[it.queryIdx * 2].toDouble(), a.xy[it.queryIdx * 2 + 1].toDouble()) }.toTypedArray())
            right.fromArray(*matches.map { Point(b.xy[it.trainIdx * 2].toDouble(), b.xy[it.trainIdx * 2 + 1].toDouble()) }.toTypedArray())
            homography = Calib3d.findHomography(left, right, Calib3d.RANSAC, 3.0, mask, 500, 0.995)
            if (homography.empty() || mask.total() != matches.size.toLong()) return emptyList()
            val accepted = ByteArray(matches.size)
            check(mask.get(0, 0, accepted) == accepted.size)
            return accepted.indices.filter { accepted[it].toInt() != 0 }
        } finally { homography?.release(); left.release(); right.release(); mask.release() }
    }

    private data class Pose(val indices: List<Int>, val rotationDegrees: Double,
        val translationOverDepth: Double, val parallaxDegrees: Double, val reprojectionError: Double)

    private fun nativePose(a: SavedPhotoGuidanceFeatures, b: SavedPhotoGuidanceFeatures, matches: List<DMatch>): Pose? {
        if (matches.size < 24) return null
        val fA = a.focal35mm?.let { ImageLoader.focalPixels(a.width, a.height, it) } ?: return null
        val fB = b.focal35mm?.let { ImageLoader.focalPixels(b.width, b.height, it) } ?: return null
        val rows = packGuidancePoseRows(a.xy, b.xy, a.colors, matches,
            LensProjection.estimated(a.width, a.height, fA),
            LensProjection.estimated(b.width, b.height, fB))
        val result = JSONObject(NativeCore.reconstruct(rows, (1.5 / minOf(fA, fB)).coerceAtMost(0.05)))
        if (result.has("error")) return null
        val points = result.getJSONArray("points")
        if (points.length() !in 18..matches.size) return null
        val indices = (0 until points.length()).map { points.getJSONArray(it).getInt(8) }
        if (indices.distinct().size != indices.size || indices.any { it !in matches.indices }) return null
        val depths = (0 until points.length()).map { points.getJSONArray(it).getDouble(2) }
        if (depths.any { !it.isFinite() || it <= 0 }) return null
        val rotation = result.getJSONArray("rotation")
        val translation = result.getJSONArray("translation")
        if (rotation.length() != 9 || translation.length() != 3) return null
        val trace = rotation.getDouble(0) + rotation.getDouble(4) + rotation.getDouble(8)
        val degrees = Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        val length = sqrt((0..2).sumOf { val value = translation.getDouble(it); value * value })
        val parallax = result.getDouble("medianParallaxDegrees")
        val error = result.getDouble("medianErrorNormalized")
        val relativeTranslation = length / requireNotNull(median(depths))
        if (!degrees.isFinite() || !relativeTranslation.isFinite() || relativeTranslation < 0 ||
            !parallax.isFinite() || parallax !in 0.0..180.0 || !error.isFinite() || error < 0) return null
        return Pose(indices, degrees, relativeTranslation, parallax, error)
    }

    private fun emptyEvidence(id: String) = PipelinePairEvidence(id, 0, 0, 0, 0, 16, null,
        GeometricModel.UNKNOWN, false, null, null, null, null, null)

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
    }

    private fun hashOriginal(file: File): String {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val size = file.length()
        require(size in 1..150L * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                copied += count
                check(copied <= size)
                digest.update(buffer, 0, count)
            }
        }
        check(copied == size && file.length() == size) { "Saved original changed during overlap analysis." }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
