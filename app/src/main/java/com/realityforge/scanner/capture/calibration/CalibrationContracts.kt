package com.realityforge.scanner.capture.calibration

import com.realityforge.scanner.engine.LensProfile
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** Where calibration evidence was produced. This never upgrades synthetic/emulator evidence to physical. */
enum class CalibrationEvidenceSource(val wireName: String) {
    USER_SELECTED_UNVERIFIED("user_selected_unverified"),
    PHYSICAL_DEVICE("physical_device"),
    EMULATOR("emulator"),
    SYNTHETIC("synthetic"),
    LEGACY_UNVERIFIED("legacy_unverified");

    companion object {
        fun fromWireName(value: String): CalibrationEvidenceSource =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unknown calibration evidence source")
    }
}

/**
 * Optional optical identity supplied by the capture/metadata owners.
 * Null means unavailable evidence, never a wildcard that proves compatibility.
 */
data class CalibrationOpticalMetadata(
    val cameraId: String? = null,
    val lensId: String? = null,
    val zoomRatio: Double? = null,
    val cropFingerprint: String? = null,
    val orientation: String = EXIF_UPRIGHT,
    val focal35mm: Double? = null,
    val evidenceSource: CalibrationEvidenceSource = CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED,
) {
    init {
        cameraId?.let { validateIdentifier("cameraId", it) }
        lensId?.let { validateIdentifier("lensId", it) }
        cropFingerprint?.let {
            require(it.length in 8..128 && it.none(Char::isISOControl)) {
                "Invalid crop fingerprint"
            }
        }
        require(orientation.length in 1..40 && orientation.none(Char::isISOControl)) {
            "Invalid image orientation identity"
        }
        require(zoomRatio == null || (zoomRatio.isFinite() && zoomRatio in 0.1..100.0)) {
            "Invalid zoom ratio"
        }
        require(focal35mm == null || (focal35mm.isFinite() && focal35mm in 1.0..1000.0)) {
            "Invalid 35 mm-equivalent focal length"
        }
    }

    companion object {
        const val EXIF_UPRIGHT = "exif-upright"

        private fun validateIdentifier(name: String, value: String) {
            require(value.length in 1..128 && value.none(Char::isISOControl)) { "Invalid $name" }
        }
    }
}

/** Immutable user-selected source identity. Raw document-provider paths are deliberately not persisted. */
data class CalibrationInputIdentity(
    val sourceId: String,
    val expectedSha256: String? = null,
    val optical: CalibrationOpticalMetadata = CalibrationOpticalMetadata(),
) {
    init {
        require(sourceId.length in 1..80 && sourceId.none(Char::isISOControl)) {
            "Invalid calibration source identifier"
        }
        expectedSha256?.let(::requireSha256)
    }
}

enum class CalibrationViewRejectionCode {
    SOURCE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    SOURCE_HASH_MISMATCH,
    DECODE_FAILED,
    BOARD_NOT_FOUND,
    CORNER_COUNT_MISMATCH,
    BOARD_AREA_TOO_SMALL,
    BOARD_AREA_TOO_LARGE,
    UPRIGHT_DIMENSIONS_MISMATCH,
    CAMERA_ID_MISMATCH,
    LENS_ID_MISMATCH,
    ZOOM_RATIO_MISMATCH,
    CROP_IDENTITY_MISMATCH,
    ORIENTATION_MISMATCH,
    FOCAL_LENGTH_MISMATCH,
    MALFORMED_MEASUREMENT,
}

data class CalibrationViewRejection(
    val code: CalibrationViewRejectionCode,
    val detail: String,
) {
    init {
        require(detail.length in 1..240 && detail.none { it == '\u0000' }) {
            "Invalid calibration rejection detail"
        }
    }
}

/** Bounded measurement retained after a source bitmap and OpenCV Mats have been released. */
data class CalibrationViewMeasurement(
    val index: Int,
    val sourceId: String,
    val sourceSha256: String? = null,
    val uprightWidth: Int? = null,
    val uprightHeight: Int? = null,
    val detectedCornerCount: Int = 0,
    val boardCenterXFraction: Double? = null,
    val boardCenterYFraction: Double? = null,
    val boardAreaFraction: Double? = null,
    val optical: CalibrationOpticalMetadata = CalibrationOpticalMetadata(),
    val preflightRejection: CalibrationViewRejection? = null,
) {
    init {
        require(index in 0 until CalibrationSetEvaluator.MAX_IMAGES) { "Invalid calibration view index" }
        require(sourceId.length in 1..80 && sourceId.none(Char::isISOControl)) {
            "Invalid calibration source identifier"
        }
        sourceSha256?.let(::requireSha256)
        require((uprightWidth == null) == (uprightHeight == null)) {
            "Calibration dimensions must be both known or both unavailable"
        }
        if (uprightWidth != null && uprightHeight != null) {
            require(uprightWidth in 3..32768 && uprightHeight in 3..32768) {
                "Invalid calibration image dimensions"
            }
        }
        require(detectedCornerCount in 0..10_000) { "Invalid detected corner count" }
        boardCenterXFraction?.let { require(it.isFinite() && it in -1.0..2.0) }
        boardCenterYFraction?.let { require(it.isFinite() && it in -1.0..2.0) }
        boardAreaFraction?.let { require(it.isFinite() && it in 0.0..4.0) }
    }
}

/** Per-view evidence safe to persist after all image resources have been released. */
data class CalibrationViewEvidence(
    val index: Int,
    val sourceId: String,
    val sourceSha256: String?,
    val uprightWidth: Int?,
    val uprightHeight: Int?,
    val boardCenterXFraction: Double?,
    val boardCenterYFraction: Double?,
    val boardAreaFraction: Double?,
    val reprojectionRmsPixels: Double?,
    val optical: CalibrationOpticalMetadata,
    val rejection: CalibrationViewRejection?,
) {
    val accepted: Boolean get() = rejection == null

    init {
        require(index in 0 until CalibrationSetEvaluator.MAX_IMAGES)
        require(sourceId.length in 1..80)
        sourceSha256?.let(::requireSha256)
        reprojectionRmsPixels?.let {
            require(it.isFinite() && it >= 0.0) { "Invalid per-view reprojection residual" }
        }
    }
}

data class CalibrationCoverageEvidence(
    val acceptedViews: Int,
    val xRangeFraction: Double?,
    val yRangeFraction: Double?,
    val boardAreaRatio: Double?,
    val metadataComplete: Boolean,
) {
    init {
        require(acceptedViews in 0..CalibrationSetEvaluator.MAX_IMAGES)
        xRangeFraction?.let { require(it.isFinite() && it >= 0.0) }
        yRangeFraction?.let { require(it.isFinite() && it >= 0.0) }
        boardAreaRatio?.let { require(it.isFinite() && it >= 1.0) }
    }
}

enum class CalibrationFailureCode {
    INPUT_COUNT_OUT_OF_RANGE,
    OPENCV_UNAVAILABLE,
    INSUFFICIENT_ACCEPTED_VIEWS,
    LOW_VIEW_DIVERSITY,
    UNSUPPORTED_OPTICAL_MIX,
    NUMERIC_CONVERGENCE_FAILED,
    PROFILE_SERIALIZATION_REJECTED,
}

data class CalibrationSessionFailure(
    val code: CalibrationFailureCode,
    val message: String,
) {
    init {
        require(message.length in 1..500 && message.none { it == '\u0000' })
    }
}

data class CalibrationSetEvaluation(
    val views: List<CalibrationViewEvidence>,
    val acceptedIndices: List<Int>,
    val coverage: CalibrationCoverageEvidence,
    val failure: CalibrationSessionFailure?,
) {
    init {
        require(views.map { it.index }.distinct().size == views.size) { "Duplicate calibration view index" }
        require(acceptedIndices == acceptedIndices.distinct().sorted()) { "Accepted indices must be unique and sorted" }
        require(acceptedIndices == views.filter { it.accepted }.map { it.index }.sorted()) {
            "Accepted view evidence does not match accepted indices"
        }
        require(coverage.acceptedViews == acceptedIndices.size)
    }
}

enum class CalibrationProgressPhase {
    ACQUIRING_INPUTS,
    DETECTING_CHECKERBOARDS,
    EVALUATING_VIEW_SET,
    SOLVING_INTRINSICS,
    COMPUTING_RESIDUALS,
    FINALIZING_PROFILE,
}

data class CalibrationSessionProgress(
    val phase: CalibrationProgressPhase,
    val completed: Int,
    val total: Int,
    val currentViewIndex: Int? = null,
) {
    init {
        require(total >= 0 && completed in 0..total)
        currentViewIndex?.let { require(it in 0 until CalibrationSetEvaluator.MAX_IMAGES) }
    }
}

data class CalibrationProfileProvenance(
    val createdAtEpochMs: Long,
    val evidenceSource: CalibrationEvidenceSource,
    val physicalSameLensVerified: Boolean,
    val sourceSetSha256: String?,
    val boardColumns: Int,
    val boardRows: Int,
    val optical: CalibrationOpticalMetadata,
    val viewEvidenceComplete: Boolean,
) {
    init {
        require(createdAtEpochMs >= 0)
        sourceSetSha256?.let(::requireSha256)
        require(boardColumns in 2..100 && boardRows in 2..100)
        require(!physicalSameLensVerified || evidenceSource == CalibrationEvidenceSource.PHYSICAL_DEVICE) {
            "Only explicitly physical evidence can be marked physically verified"
        }
    }
}

data class CalibrationProfileRecord(
    val profile: LensProfile,
    val profileId: String,
    val provenance: CalibrationProfileProvenance,
    val coverage: CalibrationCoverageEvidence,
    val views: List<CalibrationViewEvidence>,
) {
    init {
        require(profileId.isEmpty() || PROFILE_ID.matches(profileId)) { "Invalid calibration profile identity" }
        require(views.size <= CalibrationSetEvaluator.MAX_IMAGES)
        if (provenance.viewEvidenceComplete) {
            require(views.size >= profile.acceptedViews)
            require(views.count { it.accepted } == profile.acceptedViews)
            require(coverage.acceptedViews == profile.acceptedViews)
        }
    }

    companion object {
        private val PROFILE_ID = Regex("cal-v2-[0-9a-f]{64}")
    }
}

sealed interface CalibrationSessionResult {
    data class Success(val record: CalibrationProfileRecord) : CalibrationSessionResult

    data class Rejected(
        val failure: CalibrationSessionFailure,
        val views: List<CalibrationViewEvidence>,
        val coverage: CalibrationCoverageEvidence,
    ) : CalibrationSessionResult
}

/** Pure deterministic selection and evidence policy; OpenCV image ownership stays in LensCalibration. */
object CalibrationSetEvaluator {
    const val MIN_IMAGES = 10
    const val MAX_IMAGES = 40
    const val BOARD_COLUMNS = 9
    const val BOARD_ROWS = 6
    const val EXPECTED_CORNERS = BOARD_COLUMNS * BOARD_ROWS
    const val MIN_BOARD_AREA = 0.018
    const val MAX_BOARD_AREA = 0.90
    const val MIN_X_RANGE = 0.12
    const val MIN_Y_RANGE = 0.10
    const val MIN_AREA_RATIO = 1.25
    const val MAX_FOCAL_RATIO = 1.08
    const val MAX_ZOOM_RATIO = 1.05

    fun evaluate(measurements: List<CalibrationViewMeasurement>): CalibrationSetEvaluation {
        require(measurements.size <= MAX_IMAGES) { "Too many calibration measurements" }
        require(measurements.map { it.index }.distinct().size == measurements.size) {
            "Duplicate calibration measurement index"
        }

        val sorted = measurements.sortedBy { it.index }
        val rejected = linkedMapOf<Int, CalibrationViewRejection>()
        val plausible = mutableListOf<CalibrationViewMeasurement>()

        for (view in sorted) {
            val reason = view.preflightRejection ?: basicMeasurementRejection(view)
            if (reason == null) plausible += view else rejected[view.index] = reason
        }

        val dominantDimensions = plausible
            .filter { it.uprightWidth != null && it.uprightHeight != null }
            .groupingBy { requireNotNull(it.uprightWidth) to requireNotNull(it.uprightHeight) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, Int>> { it.value }
                .thenBy { it.key.first }.thenBy { it.key.second })
            .firstOrNull()?.key

        if (dominantDimensions != null) {
            plausible.filter {
                (it.uprightWidth to it.uprightHeight) != dominantDimensions
            }.forEach {
                rejected.putIfAbsent(
                    it.index,
                    CalibrationViewRejection(
                        CalibrationViewRejectionCode.UPRIGHT_DIMENSIONS_MISMATCH,
                        "Upright dimensions ${it.uprightWidth}x${it.uprightHeight} differ from the dominant " +
                            "${dominantDimensions.first}x${dominantDimensions.second} crop/orientation.",
                    ),
                )
            }
        }

        rejectNonDominantIdentity(
            plausible,
            rejected,
            CalibrationViewRejectionCode.CAMERA_ID_MISMATCH,
            "camera identity",
        ) { it.optical.cameraId }
        rejectNonDominantIdentity(
            plausible,
            rejected,
            CalibrationViewRejectionCode.LENS_ID_MISMATCH,
            "lens identity",
        ) { it.optical.lensId }
        rejectNonDominantIdentity(
            plausible,
            rejected,
            CalibrationViewRejectionCode.CROP_IDENTITY_MISMATCH,
            "crop identity",
        ) { it.optical.cropFingerprint }
        rejectNonDominantIdentity(
            plausible,
            rejected,
            CalibrationViewRejectionCode.ORIENTATION_MISMATCH,
            "orientation identity",
        ) { it.optical.orientation }

        rejectOutsideNumericCluster(
            plausible,
            rejected,
            CalibrationViewRejectionCode.FOCAL_LENGTH_MISMATCH,
            "35 mm-equivalent focal length",
            MAX_FOCAL_RATIO,
        ) { it.optical.focal35mm }
        rejectOutsideNumericCluster(
            plausible,
            rejected,
            CalibrationViewRejectionCode.ZOOM_RATIO_MISMATCH,
            "zoom ratio",
            MAX_ZOOM_RATIO,
        ) { it.optical.zoomRatio }

        val accepted = plausible.filterNot { rejected.containsKey(it.index) }.sortedBy { it.index }
        val centersX = accepted.mapNotNull { it.boardCenterXFraction }
        val centersY = accepted.mapNotNull { it.boardCenterYFraction }
        val areas = accepted.mapNotNull { it.boardAreaFraction }
        val xRange = rangeOrNull(centersX)
        val yRange = rangeOrNull(centersY)
        val areaRatio = if (areas.isEmpty()) null else areas.maxOrNull()!! / max(areas.minOrNull()!!, 1e-12)
        val metadataComplete = accepted.isNotEmpty() && accepted.all { view ->
            view.sourceSha256 != null &&
                view.optical.cameraId != null &&
                view.optical.lensId != null &&
                view.optical.zoomRatio != null &&
                view.optical.cropFingerprint != null &&
                view.optical.focal35mm != null
        }
        val coverage = CalibrationCoverageEvidence(
            acceptedViews = accepted.size,
            xRangeFraction = xRange,
            yRangeFraction = yRange,
            boardAreaRatio = areaRatio,
            metadataComplete = metadataComplete,
        )

        val opticalRejects = rejected.values.any {
            it.code in setOf(
                CalibrationViewRejectionCode.CAMERA_ID_MISMATCH,
                CalibrationViewRejectionCode.LENS_ID_MISMATCH,
                CalibrationViewRejectionCode.ZOOM_RATIO_MISMATCH,
                CalibrationViewRejectionCode.CROP_IDENTITY_MISMATCH,
                CalibrationViewRejectionCode.ORIENTATION_MISMATCH,
                CalibrationViewRejectionCode.FOCAL_LENGTH_MISMATCH,
                CalibrationViewRejectionCode.UPRIGHT_DIMENSIONS_MISMATCH,
            )
        }
        val failure = when {
            accepted.size < MIN_IMAGES && opticalRejects -> CalibrationSessionFailure(
                CalibrationFailureCode.UNSUPPORTED_OPTICAL_MIX,
                "Only ${accepted.size} compatible checkerboard views remain. Use one camera, lens, zoom, crop, " +
                    "orientation and image aspect for at least $MIN_IMAGES views.",
            )
            accepted.size < MIN_IMAGES -> CalibrationSessionFailure(
                CalibrationFailureCode.INSUFFICIENT_ACCEPTED_VIEWS,
                "Only ${accepted.size} useful checkerboard views were accepted; at least $MIN_IMAGES must " +
                    "clearly show all ${BOARD_COLUMNS}x${BOARD_ROWS} inner corners.",
            )
            xRange == null || yRange == null || areaRatio == null ||
                xRange < MIN_X_RANGE || yRange < MIN_Y_RANGE || areaRatio < MIN_AREA_RATIO ->
                CalibrationSessionFailure(
                    CalibrationFailureCode.LOW_VIEW_DIVERSITY,
                    "Accepted checkerboards lack measured coverage diversity: xRange=${format(xRange)}, " +
                        "yRange=${format(yRange)}, areaRatio=${format(areaRatio)}. Move the board toward image " +
                        "edges and vary tilt and distance.",
                )
            else -> null
        }

        val evidence = sorted.map { measurement ->
            CalibrationViewEvidence(
                index = measurement.index,
                sourceId = measurement.sourceId,
                sourceSha256 = measurement.sourceSha256,
                uprightWidth = measurement.uprightWidth,
                uprightHeight = measurement.uprightHeight,
                boardCenterXFraction = measurement.boardCenterXFraction,
                boardCenterYFraction = measurement.boardCenterYFraction,
                boardAreaFraction = measurement.boardAreaFraction,
                reprojectionRmsPixels = null,
                optical = measurement.optical,
                rejection = rejected[measurement.index],
            )
        }
        return CalibrationSetEvaluation(
            views = evidence,
            acceptedIndices = accepted.map { it.index },
            coverage = coverage,
            failure = failure,
        )
    }

    private fun basicMeasurementRejection(view: CalibrationViewMeasurement): CalibrationViewRejection? {
        if (view.uprightWidth == null || view.uprightHeight == null) {
            return CalibrationViewRejection(
                CalibrationViewRejectionCode.MALFORMED_MEASUREMENT,
                "No decoded upright image dimensions are available.",
            )
        }
        if (view.detectedCornerCount != EXPECTED_CORNERS) {
            return CalibrationViewRejection(
                CalibrationViewRejectionCode.CORNER_COUNT_MISMATCH,
                "Detected ${view.detectedCornerCount} corners; expected $EXPECTED_CORNERS.",
            )
        }
        val centerX = view.boardCenterXFraction
        val centerY = view.boardCenterYFraction
        val area = view.boardAreaFraction
        if (centerX == null || centerY == null || area == null) {
            return CalibrationViewRejection(
                CalibrationViewRejectionCode.MALFORMED_MEASUREMENT,
                "Checkerboard center or area evidence is unavailable.",
            )
        }
        if (area < MIN_BOARD_AREA) {
            return CalibrationViewRejection(
                CalibrationViewRejectionCode.BOARD_AREA_TOO_SMALL,
                "Checkerboard covers ${format(area)} of the image; minimum is $MIN_BOARD_AREA.",
            )
        }
        if (area > MAX_BOARD_AREA) {
            return CalibrationViewRejection(
                CalibrationViewRejectionCode.BOARD_AREA_TOO_LARGE,
                "Checkerboard covers ${format(area)} of the image; maximum is $MAX_BOARD_AREA.",
            )
        }
        return null
    }

    private fun <T : Comparable<T>> rejectNonDominantIdentity(
        views: List<CalibrationViewMeasurement>,
        rejected: MutableMap<Int, CalibrationViewRejection>,
        code: CalibrationViewRejectionCode,
        label: String,
        selector: (CalibrationViewMeasurement) -> T?,
    ) {
        val candidates = views.filterNot { rejected.containsKey(it.index) }
        val counts = candidates.mapNotNull(selector).groupingBy { it }.eachCount()
        if (counts.size <= 1) return
        val dominant = counts.entries
            .sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }.thenBy { it.key })
            .first().key
        candidates.forEach { view ->
            val value = selector(view)
            if (value != null && value != dominant) {
                rejected[view.index] = CalibrationViewRejection(
                    code,
                    "View $label '$value' differs from the dominant calibration $label '$dominant'.",
                )
            }
        }
    }

    private fun rejectOutsideNumericCluster(
        views: List<CalibrationViewMeasurement>,
        rejected: MutableMap<Int, CalibrationViewRejection>,
        code: CalibrationViewRejectionCode,
        label: String,
        maxRatio: Double,
        selector: (CalibrationViewMeasurement) -> Double?,
    ) {
        val known = views.filterNot { rejected.containsKey(it.index) }
            .mapNotNull { view -> selector(view)?.let { value -> view to value } }
            .sortedWith(compareBy<Pair<CalibrationViewMeasurement, Double>> { it.second }.thenBy { it.first.index })
        if (known.size <= 1) return

        var bestStart = 0
        var bestEnd = 0
        var start = 0
        for (end in known.indices) {
            while (start < end && known[end].second / known[start].second > maxRatio) start++
            val candidateCount = end - start + 1
            val bestCount = bestEnd - bestStart + 1
            if (candidateCount > bestCount ||
                (candidateCount == bestCount && known[start].second < known[bestStart].second)
            ) {
                bestStart = start
                bestEnd = end
            }
        }
        if (bestEnd - bestStart + 1 == known.size) return
        val acceptedIndices = known.subList(bestStart, bestEnd + 1).map { it.first.index }.toSet()
        val low = known[bestStart].second
        val high = known[bestEnd].second
        known.forEach { (view, value) ->
            if (view.index !in acceptedIndices) {
                rejected[view.index] = CalibrationViewRejection(
                    code,
                    "View $label ${format(value)} is outside the dominant compatible range " +
                        "${format(low)}–${format(high)}.",
                )
            }
        }
    }

    private fun rangeOrNull(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.maxOrNull()!! - values.minOrNull()!!

    private fun format(value: Double?): String =
        if (value == null) "unknown" else "%.4f".format(java.util.Locale.ROOT, value)
}

enum class CalibrationCompatibilityStatus {
    VERIFIED_COMPATIBLE,
    COMPATIBLE_WITH_UNVERIFIED_METADATA,
    INCOMPATIBLE,
}

enum class CalibrationCompatibilityReason {
    INVALID_SCAN_DIMENSIONS,
    ASPECT_OR_CROP_MISMATCH,
    CAMERA_ID_MISMATCH,
    LENS_ID_MISMATCH,
    ZOOM_RATIO_MISMATCH,
    CROP_IDENTITY_MISMATCH,
    ORIENTATION_MISMATCH,
    FOCAL_LENGTH_MISMATCH,
    CAMERA_ID_UNAVAILABLE,
    LENS_ID_UNAVAILABLE,
    ZOOM_RATIO_UNAVAILABLE,
    CROP_IDENTITY_UNAVAILABLE,
    FOCAL_LENGTH_UNAVAILABLE,
    PROFILE_NOT_PHYSICALLY_VERIFIED,
}

data class CalibrationCaptureMetadata(
    val uprightWidth: Int,
    val uprightHeight: Int,
    val optical: CalibrationOpticalMetadata,
) {
    init {
        require(uprightWidth in 3..32768 && uprightHeight in 3..32768)
    }
}

data class CalibrationCompatibilityResult(
    val status: CalibrationCompatibilityStatus,
    val reasons: List<CalibrationCompatibilityReason>,
) {
    init {
        require(reasons.distinct().size == reasons.size)
        require(status != CalibrationCompatibilityStatus.VERIFIED_COMPATIBLE || reasons.isEmpty())
    }
}

/** Detailed compatibility result; missing metadata is explicit and cannot become a verified match. */
object CalibrationProfileCompatibility {
    private const val MAX_ASPECT_DELTA = 0.005
    private const val MAX_FOCAL_RATIO = 1.08
    private const val MAX_ZOOM_RATIO = 1.05

    fun evaluate(record: CalibrationProfileRecord, capture: CalibrationCaptureMetadata): CalibrationCompatibilityResult {
        val incompatible = mutableListOf<CalibrationCompatibilityReason>()
        val unverified = mutableListOf<CalibrationCompatibilityReason>()
        val profile = record.profile
        val calibratedOptical = record.provenance.optical

        val aspectDelta = abs(
            profile.width.toDouble() / profile.height -
                capture.uprightWidth.toDouble() / capture.uprightHeight,
        )
        if (!aspectDelta.isFinite()) incompatible += CalibrationCompatibilityReason.INVALID_SCAN_DIMENSIONS
        else if (aspectDelta >= MAX_ASPECT_DELTA) {
            incompatible += CalibrationCompatibilityReason.ASPECT_OR_CROP_MISMATCH
        }

        compareIdentity(
            calibratedOptical.cameraId,
            capture.optical.cameraId,
            CalibrationCompatibilityReason.CAMERA_ID_MISMATCH,
            CalibrationCompatibilityReason.CAMERA_ID_UNAVAILABLE,
            incompatible,
            unverified,
        )
        compareIdentity(
            calibratedOptical.lensId,
            capture.optical.lensId,
            CalibrationCompatibilityReason.LENS_ID_MISMATCH,
            CalibrationCompatibilityReason.LENS_ID_UNAVAILABLE,
            incompatible,
            unverified,
        )
        compareIdentity(
            calibratedOptical.cropFingerprint,
            capture.optical.cropFingerprint,
            CalibrationCompatibilityReason.CROP_IDENTITY_MISMATCH,
            CalibrationCompatibilityReason.CROP_IDENTITY_UNAVAILABLE,
            incompatible,
            unverified,
        )
        compareIdentity(
            calibratedOptical.orientation,
            capture.optical.orientation,
            CalibrationCompatibilityReason.ORIENTATION_MISMATCH,
            null,
            incompatible,
            unverified,
        )
        compareRatio(
            calibratedOptical.zoomRatio,
            capture.optical.zoomRatio,
            MAX_ZOOM_RATIO,
            CalibrationCompatibilityReason.ZOOM_RATIO_MISMATCH,
            CalibrationCompatibilityReason.ZOOM_RATIO_UNAVAILABLE,
            incompatible,
            unverified,
        )
        compareRatio(
            profile.focal35mm ?: calibratedOptical.focal35mm,
            capture.optical.focal35mm,
            MAX_FOCAL_RATIO,
            CalibrationCompatibilityReason.FOCAL_LENGTH_MISMATCH,
            CalibrationCompatibilityReason.FOCAL_LENGTH_UNAVAILABLE,
            incompatible,
            unverified,
        )

        if (!record.provenance.physicalSameLensVerified) {
            unverified += CalibrationCompatibilityReason.PROFILE_NOT_PHYSICALLY_VERIFIED
        }
        return when {
            incompatible.isNotEmpty() -> CalibrationCompatibilityResult(
                CalibrationCompatibilityStatus.INCOMPATIBLE,
                (incompatible + unverified).distinct(),
            )
            unverified.isNotEmpty() -> CalibrationCompatibilityResult(
                CalibrationCompatibilityStatus.COMPATIBLE_WITH_UNVERIFIED_METADATA,
                unverified.distinct(),
            )
            else -> CalibrationCompatibilityResult(CalibrationCompatibilityStatus.VERIFIED_COMPATIBLE, emptyList())
        }
    }

    private fun compareIdentity(
        calibrated: String?,
        capture: String?,
        mismatch: CalibrationCompatibilityReason,
        unavailable: CalibrationCompatibilityReason?,
        incompatible: MutableList<CalibrationCompatibilityReason>,
        unverified: MutableList<CalibrationCompatibilityReason>,
    ) {
        if (calibrated == null || capture == null) {
            if (unavailable != null) unverified += unavailable
        } else if (calibrated != capture) incompatible += mismatch
    }

    private fun compareRatio(
        calibrated: Double?,
        capture: Double?,
        maxRatio: Double,
        mismatch: CalibrationCompatibilityReason,
        unavailable: CalibrationCompatibilityReason,
        incompatible: MutableList<CalibrationCompatibilityReason>,
        unverified: MutableList<CalibrationCompatibilityReason>,
    ) {
        if (calibrated == null || capture == null) {
            unverified += unavailable
        } else if (max(calibrated, capture) / minOf(calibrated, capture) > maxRatio) {
            incompatible += mismatch
        }
    }
}

internal fun requireSha256(value: String) {
    require(SHA256.matches(value)) { "Expected a lowercase SHA-256 digest" }
}

internal fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private val SHA256 = Regex("[0-9a-f]{64}")
