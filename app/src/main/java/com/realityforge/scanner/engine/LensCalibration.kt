package com.realityforge.scanner.engine

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource
import com.realityforge.scanner.capture.calibration.CalibrationFailureCode
import com.realityforge.scanner.capture.calibration.CalibrationImageInput
import com.realityforge.scanner.capture.calibration.CalibrationInputIdentity
import com.realityforge.scanner.capture.calibration.CalibrationOpticalMetadata
import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.capture.calibration.CalibrationProfileRecord
import com.realityforge.scanner.capture.calibration.CalibrationProgressPhase
import com.realityforge.scanner.capture.calibration.CalibrationSessionFailure
import com.realityforge.scanner.capture.calibration.CalibrationSessionProgress
import com.realityforge.scanner.capture.calibration.CalibrationSessionResult
import com.realityforge.scanner.capture.calibration.CalibrationSetEvaluator
import com.realityforge.scanner.capture.calibration.CalibrationViewMeasurement
import com.realityforge.scanner.capture.calibration.CalibrationViewRejection
import com.realityforge.scanner.capture.calibration.CalibrationViewRejectionCode
import com.realityforge.scanner.capture.calibration.CalibrationCaptureMetadataAdapter
import com.realityforge.scanner.capture.metadata.ExifCaptureMetadataAdapter
import com.realityforge.scanner.data.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** No network. The user must capture a printed 9x6 INNER-CORNER checkerboard from many angles. */
object LensCalibration {
    const val BOARD_COLUMNS = CalibrationSetEvaluator.BOARD_COLUMNS
    const val BOARD_ROWS = CalibrationSetEvaluator.BOARD_ROWS
    const val SCHEMA_VERSION = CalibrationProfileCodec.SCHEMA_VERSION
    private const val MAX_IMAGE_BYTES = 42L * 1024 * 1024

    fun file(repository: ProjectRepository, projectId: String) =
        File(repository.directory(projectId), "camera-calibration-v1.json")

    fun read(repository: ProjectRepository, projectId: String): LensProfile? =
        readRecord(repository, projectId)?.profile

    fun readRecord(repository: ProjectRepository, projectId: String): CalibrationProfileRecord? {
        val source = file(repository, projectId)
        if (!source.exists()) return null
        require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Calibration profile cannot be a symbolic link" }
        require(source.length() in 30..CalibrationProfileCodec.MAX_PROFILE_BYTES.toLong()) {
            "Calibration profile is corrupt"
        }
        return CalibrationProfileCodec.decode(source.readText(Charsets.UTF_8))
    }

    fun json(record: CalibrationProfileRecord): String = CalibrationProfileCodec.encode(record)

    /** Compatibility adapter until the application owner persists calculateSession() evidence directly. */
    fun json(profile: LensProfile): String =
        CalibrationProfileCodec.encode(CalibrationProfileCodec.aggregateOnly(profile))

    /**
     * Existing aggregate API retained for current callers. It now uses the typed session evaluator and
     * deterministic per-view rejection internally, but only calculateSession() exposes the full evidence record.
     */
    suspend fun calculate(
        context: Context,
        uris: List<Uri>,
        progress: (Int, Int) -> Unit,
    ): LensProfile {
        val inputs = uris.mapIndexed { index, uri ->
            CalibrationImageInput(
                uri,
                CalibrationInputIdentity(
                    sourceId = "view-${(index + 1).toString().padStart(2, '0')}",
                    optical = CalibrationOpticalMetadata(
                        evidenceSource = CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED,
                    ),
                ),
            )
        }
        val total = inputs.size
        val result = calculateSession(context, inputs) { update ->
            if (update.phase == CalibrationProgressPhase.ACQUIRING_INPUTS ||
                update.phase == CalibrationProgressPhase.DETECTING_CHECKERBOARDS
            ) {
                progress(update.completed.coerceIn(0, total), total)
            }
        }
        progress(total, total)
        return when (result) {
            is CalibrationSessionResult.Success -> result.record.profile
            is CalibrationSessionResult.Rejected -> throw IllegalArgumentException(result.failure.message)
        }
    }

    /**
     * Reproducible calibration workflow with per-view evidence and immutable source identities.
     * All copied inputs, bitmaps and Mats are bounded and released on success, failure and cancellation.
     */
    suspend fun calculateSession(
        context: Context,
        inputs: List<CalibrationImageInput>,
        progress: (CalibrationSessionProgress) -> Unit = {},
    ): CalibrationSessionResult {
        if (inputs.size !in CalibrationSetEvaluator.MIN_IMAGES..CalibrationSetEvaluator.MAX_IMAGES) {
            return CalibrationSessionResult.Rejected(
                failure = CalibrationSessionFailure(
                    CalibrationFailureCode.INPUT_COUNT_OUT_OF_RANGE,
                    "Select ${CalibrationSetEvaluator.MIN_IMAGES}–${CalibrationSetEvaluator.MAX_IMAGES} different " +
                        "photos of a ${BOARD_COLUMNS}x${BOARD_ROWS}-inner-corner checkerboard.",
                ),
                views = emptyList(),
                coverage = emptyCoverage(),
            )
        }
        val openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (_: Exception) {
            false
        }
        if (!openCvReady) {
            return CalibrationSessionResult.Rejected(
                failure = CalibrationSessionFailure(
                    CalibrationFailureCode.OPENCV_UNAVAILABLE,
                    "OpenCV could not load on this device.",
                ),
                views = emptyList(),
                coverage = emptyCoverage(),
            )
        }

        val measurements = ArrayList<CalibrationViewMeasurement>(inputs.size)
        val prepared = linkedMapOf<Int, PreparedView>()
        val pattern = Size(BOARD_COLUMNS.toDouble(), BOARD_ROWS.toDouble())
        try {
            for ((index, input) in inputs.withIndex()) {
                coroutineContext.ensureActive()
                progress(
                    CalibrationSessionProgress(
                        CalibrationProgressPhase.ACQUIRING_INPUTS,
                        index,
                        inputs.size,
                        index,
                    ),
                )
                val temp = try {
                    File.createTempFile("rf-calib-", ".img", context.cacheDir)
                } catch (failure: Exception) {
                    measurements += rejectedMeasurement(
                        index,
                        input,
                        null,
                        CalibrationViewRejectionCode.SOURCE_UNAVAILABLE,
                        failure.message ?: "Private temporary storage is unavailable.",
                    )
                    continue
                }
                try {
                    val copied = try {
                        copyInput(context, input, temp)
                    } catch (failure: CalibrationInputFailure) {
                        measurements += rejectedMeasurement(index, input, null, failure.code, failure.message)
                        continue
                    }
                    coroutineContext.ensureActive()
                    progress(
                        CalibrationSessionProgress(
                            CalibrationProgressPhase.DETECTING_CHECKERBOARDS,
                            index,
                            inputs.size,
                            index,
                        ),
                    )
                    val observed = runCatching { CalibrationCaptureMetadataAdapter.optical(ExifCaptureMetadataAdapter.read(temp)) }
                        .getOrDefault(CalibrationOpticalMetadata())
                    val supplied = input.identity.optical
                    val optical = supplied.copy(cameraId = supplied.cameraId ?: observed.cameraId,
                        lensId = supplied.lensId ?: observed.lensId, zoomRatio = supplied.zoomRatio ?: observed.zoomRatio,
                        cropFingerprint = supplied.cropFingerprint ?: observed.cropFingerprint,
                        focal35mm = supplied.focal35mm ?: observed.focal35mm ?: ImageLoader.focal35(temp))
                    val bitmap = try {
                        ImageLoader.upright(temp, ImageLoader.ANALYSIS_EDGE)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        measurements += rejectedMeasurement(
                            index,
                            input,
                            copied.sha256,
                            CalibrationViewRejectionCode.DECODE_FAILED,
                            failure.message ?: "Calibration image could not be decoded.",
                            optical,
                        )
                        continue
                    }
                    var rgba: Mat? = null
                    var gray: Mat? = null
                    var corners: MatOfPoint2f? = null
                    try {
                        rgba = Mat()
                        gray = Mat()
                        corners = MatOfPoint2f()
                        Utils.bitmapToMat(bitmap, rgba)
                        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                        val found = Calib3d.findChessboardCorners(
                            gray,
                            pattern,
                            corners,
                            Calib3d.CALIB_CB_ADAPTIVE_THRESH or Calib3d.CALIB_CB_NORMALIZE_IMAGE,
                        )
                        if (!found) {
                            measurements += CalibrationViewMeasurement(
                                index = index,
                                sourceId = input.identity.sourceId,
                                sourceSha256 = copied.sha256,
                                uprightWidth = bitmap.width,
                                uprightHeight = bitmap.height,
                                optical = optical,
                                preflightRejection = CalibrationViewRejection(
                                    CalibrationViewRejectionCode.BOARD_NOT_FOUND,
                                    "All ${BOARD_COLUMNS}x${BOARD_ROWS} inner corners were not detected.",
                                ),
                            )
                            continue
                        }
                        Imgproc.cornerSubPix(
                            gray,
                            corners,
                            Size(7.0, 7.0),
                            Size(-1.0, -1.0),
                            TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 35, .015),
                        )
                        val xy = corners.toArray()
                        if (xy.size != CalibrationSetEvaluator.EXPECTED_CORNERS) {
                            measurements += CalibrationViewMeasurement(
                                index = index,
                                sourceId = input.identity.sourceId,
                                sourceSha256 = copied.sha256,
                                uprightWidth = bitmap.width,
                                uprightHeight = bitmap.height,
                                detectedCornerCount = xy.size,
                                optical = optical,
                                preflightRejection = CalibrationViewRejection(
                                    CalibrationViewRejectionCode.CORNER_COUNT_MISMATCH,
                                    "Detected ${xy.size} corners; expected ${CalibrationSetEvaluator.EXPECTED_CORNERS}.",
                                ),
                            )
                            continue
                        }
                        val left = xy.minOf { it.x }
                        val right = xy.maxOf { it.x }
                        val top = xy.minOf { it.y }
                        val bottom = xy.maxOf { it.y }
                        val area = ((right - left) * (bottom - top)) /
                            (bitmap.width.toDouble() * bitmap.height)
                        val measurement = CalibrationViewMeasurement(
                            index = index,
                            sourceId = input.identity.sourceId,
                            sourceSha256 = copied.sha256,
                            uprightWidth = bitmap.width,
                            uprightHeight = bitmap.height,
                            detectedCornerCount = xy.size,
                            boardCenterXFraction = (right + left) / 2.0 / bitmap.width,
                            boardCenterYFraction = (bottom + top) / 2.0 / bitmap.height,
                            boardAreaFraction = area,
                            optical = optical,
                        )
                        measurements += measurement
                        prepared[index] = PreparedView(measurement, MatOfPoint2f(*xy))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        prepared.remove(index)?.release()
                        measurements.removeAll { it.index == index }
                        measurements += rejectedMeasurement(
                            index,
                            input,
                            copied.sha256,
                            CalibrationViewRejectionCode.DECODE_FAILED,
                            failure.message ?: "Checkerboard analysis failed.",
                            optical,
                            bitmap.width,
                            bitmap.height,
                        )
                    } finally {
                        corners?.release()
                        gray?.release()
                        rgba?.release()
                        bitmap.recycle()
                    }
                } finally {
                    temp.delete()
                }
            }
            progress(
                CalibrationSessionProgress(
                    CalibrationProgressPhase.ACQUIRING_INPUTS,
                    inputs.size,
                    inputs.size,
                ),
            )
            progress(
                CalibrationSessionProgress(
                    CalibrationProgressPhase.DETECTING_CHECKERBOARDS,
                    inputs.size,
                    inputs.size,
                ),
            )
            coroutineContext.ensureActive()
            progress(CalibrationSessionProgress(CalibrationProgressPhase.EVALUATING_VIEW_SET, 0, 1))
            val evaluation = CalibrationSetEvaluator.evaluate(measurements)
            progress(CalibrationSessionProgress(CalibrationProgressPhase.EVALUATING_VIEW_SET, 1, 1))
            evaluation.failure?.let { failure ->
                return CalibrationSessionResult.Rejected(failure, evaluation.views, evaluation.coverage)
            }

            val accepted = evaluation.acceptedIndices.map { index ->
                prepared[index] ?: error("Accepted calibration view has no retained corner observations")
            }
            val width = requireNotNull(accepted.first().measurement.uprightWidth)
            val height = requireNotNull(accepted.first().measurement.uprightHeight)
            coroutineContext.ensureActive()
            progress(CalibrationSessionProgress(CalibrationProgressPhase.SOLVING_INTRINSICS, 0, 1))
            val solved = try {
                solve(width, height, accepted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        failure.message ?: "Calibration solver failed before producing a valid model.",
                    ),
                )
            }
            progress(CalibrationSessionProgress(CalibrationProgressPhase.SOLVING_INTRINSICS, 1, 1))
            if (solved is SolveResult.Failed) {
                return CalibrationSessionResult.Rejected(
                    solved.failure,
                    evaluation.views,
                    evaluation.coverage,
                )
            }
            solved as SolveResult.Success
            coroutineContext.ensureActive()
            progress(CalibrationSessionProgress(CalibrationProgressPhase.COMPUTING_RESIDUALS, 0, 1))
            val residualByIndex = evaluation.acceptedIndices.zip(solved.perViewRms).toMap()
            val evidence = evaluation.views.map { view ->
                if (view.accepted) view.copy(reprojectionRmsPixels = residualByIndex.getValue(view.index)) else view
            }
            progress(CalibrationSessionProgress(CalibrationProgressPhase.COMPUTING_RESIDUALS, 1, 1))
            coroutineContext.ensureActive()
            progress(CalibrationSessionProgress(CalibrationProgressPhase.FINALIZING_PROFILE, 0, 1))
            val record = try {
                CalibrationProfileCodec.fromSession(
                    profile = solved.profile,
                    coverage = evaluation.coverage,
                    views = evidence,
                ).also { CalibrationProfileCodec.encode(it) }
            } catch (failure: Exception) {
                return CalibrationSessionResult.Rejected(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.PROFILE_SERIALIZATION_REJECTED,
                        failure.message ?: "Calibration profile validation failed.",
                    ),
                    evidence,
                    evaluation.coverage,
                )
            }
            progress(CalibrationSessionProgress(CalibrationProgressPhase.FINALIZING_PROFILE, 1, 1))
            return CalibrationSessionResult.Success(record)
        } finally {
            prepared.values.forEach(PreparedView::release)
        }
    }

    private suspend fun copyInput(
        context: Context,
        input: CalibrationImageInput,
        destination: File,
    ): CopiedInput {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val stream = try {
            context.contentResolver.openInputStream(input.uri)
        } catch (failure: Exception) {
            throw CalibrationInputFailure(
                CalibrationViewRejectionCode.SOURCE_UNAVAILABLE,
                failure.message ?: "Calibration image cannot be opened.",
            )
        } ?: throw CalibrationInputFailure(
            CalibrationViewRejectionCode.SOURCE_UNAVAILABLE,
            "Calibration image cannot be opened.",
        )
        try {
            stream.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var zeroReads = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            if (++zeroReads >= 16) throw CalibrationInputFailure(
                                CalibrationViewRejectionCode.SOURCE_UNAVAILABLE, "Calibration input stream stopped making progress.")
                            continue
                        }
                        zeroReads = 0
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            throw CalibrationInputFailure(
                                CalibrationViewRejectionCode.SOURCE_TOO_LARGE,
                                "Calibration image exceeds the 42 MB safety limit.",
                            )
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: CalibrationInputFailure) {
            throw known
        } catch (failure: Exception) {
            throw CalibrationInputFailure(
                CalibrationViewRejectionCode.SOURCE_UNAVAILABLE,
                failure.message ?: "Calibration image could not be copied.",
            )
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = input.identity.expectedSha256
        if (expected != null && expected != sha256) {
            throw CalibrationInputFailure(
                CalibrationViewRejectionCode.SOURCE_HASH_MISMATCH,
                "Calibration image bytes do not match the bound source identity.",
            )
        }
        return CopiedInput(sha256)
    }

    private fun rejectedMeasurement(
        index: Int,
        input: CalibrationImageInput,
        sha256: String?,
        code: CalibrationViewRejectionCode,
        detail: String,
        optical: CalibrationOpticalMetadata = input.identity.optical,
        width: Int? = null,
        height: Int? = null,
    ) = CalibrationViewMeasurement(
        index = index,
        sourceId = input.identity.sourceId,
        sourceSha256 = sha256,
        uprightWidth = width,
        uprightHeight = height,
        optical = optical,
        preflightRejection = CalibrationViewRejection(code, detail.take(240).ifBlank { code.name }),
    )

    private fun solve(
        width: Int,
        height: Int,
        accepted: List<PreparedView>,
    ): SolveResult {
        val objectPoints = ArrayList<Mat>()
        val imagePoints = ArrayList<Mat>()
        val cameraMatrix = Mat()
        val distortion = Mat()
        val rvecs = ArrayList<Mat>()
        val tvecs = ArrayList<Mat>()
        try {
            accepted.forEach { prepared ->
                objectPoints += MatOfPoint3f(
                    *Array(CalibrationSetEvaluator.EXPECTED_CORNERS) { index ->
                        Point3(
                            (index % BOARD_COLUMNS).toDouble(),
                            (index / BOARD_COLUMNS).toDouble(),
                            0.0,
                        )
                    },
                )
                imagePoints += prepared.corners
            }
            val rms = try {
                Calib3d.calibrateCamera(
                    objectPoints,
                    imagePoints,
                    Size(width.toDouble(), height.toDouble()),
                    cameraMatrix,
                    distortion,
                    rvecs,
                    tvecs,
                )
            } catch (failure: Exception) {
                return SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        failure.message ?: "OpenCV calibration did not converge.",
                    ),
                )
            }
            if (!rms.isFinite()) {
                return SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        "Calibration returned a non-finite reprojection residual.",
                    ),
                )
            }
            val flat = DoubleArray((distortion.total() * distortion.channels()).toInt())
            if (flat.size < 5) {
                return SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        "Calibration did not return the five Brown-Conrady coefficients.",
                    ),
                )
            }
            distortion.get(0, 0, flat)
            val coefficients = flat.copyOf(5)
            val focalValues = accepted.mapNotNull { it.measurement.optical.focal35mm }.sorted()
            val focal35 = focalValues.takeIf { it.isNotEmpty() }?.get(focalValues.size / 2)
            val profile = try {
                LensProfile(
                    width = width,
                    height = height,
                    fx = cameraMatrix.get(0, 0)[0],
                    fy = cameraMatrix.get(1, 1)[0],
                    cx = cameraMatrix.get(0, 2)[0],
                    cy = cameraMatrix.get(1, 2)[0],
                    k1 = coefficients[0],
                    k2 = coefficients[1],
                    p1 = coefficients[2],
                    p2 = coefficients[3],
                    k3 = coefficients[4],
                    reprojectionRmsPixels = rms,
                    acceptedViews = accepted.size,
                    focal35mm = focal35,
                )
            } catch (failure: Exception) {
                return SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        failure.message ?: "Calibration parameters are numerically implausible.",
                    ),
                )
            }
            val perView = try {
                perViewResiduals(objectPoints, imagePoints, cameraMatrix, coefficients, rvecs, tvecs)
            } catch (failure: Exception) {
                return SolveResult.Failed(
                    CalibrationSessionFailure(
                        CalibrationFailureCode.NUMERIC_CONVERGENCE_FAILED,
                        failure.message ?: "Per-view reprojection evidence could not be computed.",
                    ),
                )
            }
            return SolveResult.Success(profile, perView)
        } finally {
            objectPoints.forEach(Mat::release)
            cameraMatrix.release()
            distortion.release()
            rvecs.forEach(Mat::release)
            tvecs.forEach(Mat::release)
        }
    }

    private fun perViewResiduals(
        objectPoints: List<Mat>,
        imagePoints: List<Mat>,
        cameraMatrix: Mat,
        coefficients: DoubleArray,
        rvecs: List<Mat>,
        tvecs: List<Mat>,
    ): List<Double> {
        require(objectPoints.size == imagePoints.size && objectPoints.size == rvecs.size && rvecs.size == tvecs.size)
        val distortion = MatOfDouble(*coefficients)
        try {
            return objectPoints.indices.map { index ->
                val projected = MatOfPoint2f()
                try {
                    Calib3d.projectPoints(
                        objectPoints[index] as MatOfPoint3f,
                        rvecs[index],
                        tvecs[index],
                        cameraMatrix,
                        distortion,
                        projected,
                    )
                    val expected = (imagePoints[index] as MatOfPoint2f).toArray()
                    val actual = projected.toArray()
                    require(expected.size == actual.size && expected.isNotEmpty()) {
                        "Projected checkerboard point count changed"
                    }
                    val sumSquared = expected.indices.sumOf { point ->
                        val dx = expected[point].x - actual[point].x
                        val dy = expected[point].y - actual[point].y
                        dx * dx + dy * dy
                    }
                    sqrt(sumSquared / expected.size)
                } finally {
                    projected.release()
                }
            }
        } finally {
            distortion.release()
        }
    }

    private fun emptyCoverage() = com.realityforge.scanner.capture.calibration.CalibrationCoverageEvidence(
        acceptedViews = 0,
        xRangeFraction = null,
        yRangeFraction = null,
        boardAreaRatio = null,
        metadataComplete = false,
    )

    private data class CopiedInput(val sha256: String)

    private class CalibrationInputFailure(
        val code: CalibrationViewRejectionCode,
        override val message: String,
    ) : Exception(message)

    private data class PreparedView(
        val measurement: CalibrationViewMeasurement,
        val corners: MatOfPoint2f,
    ) {
        fun release() = corners.release()
    }

    private sealed interface SolveResult {
        data class Success(val profile: LensProfile, val perViewRms: List<Double>) : SolveResult
        data class Failed(val failure: CalibrationSessionFailure) : SolveResult
    }
}
