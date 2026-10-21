package com.realityforge.scanner.capture.guidance

import kotlin.math.abs

/**
 * Immutable identity for one capture session. Every observation must carry this identity so a
 * delayed callback can never influence another project, revision, or camera session.
 */
data class CaptureSessionIdentity(
    val projectId: String,
    val revision: Long,
    val sessionToken: String,
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(revision >= 0L) { "revision must be non-negative" }
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
    }
}

/** Identity and monotonic timestamp for a preview frame or committed photograph. */
data class GuidanceFrameIdentity(
    val session: CaptureSessionIdentity,
    val frameId: String,
    val timestampNanos: Long,
    val sequenceNumber: Long,
) {
    init {
        require(frameId.isNotBlank()) { "frameId must not be blank" }
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative" }
        require(sequenceNumber >= 0L) { "sequenceNumber must be non-negative" }
    }
}

enum class EvidenceProvenance {
    /** Produced from the application's real feature matcher / pose validator. */
    ENGINE_MATCHER,

    /** Deterministic replay data used only by tests and benchmark tooling. */
    REPLAY_FIXTURE,
}

enum class GeometricModel {
    UNKNOWN,
    HOMOGRAPHY_DOMINANT,
    ESSENTIAL_POSE,
}

/**
 * Pairwise evidence contains only bounded summaries. No image pixels, descriptors, or feature
 * arrays are retained by the guidance window.
 */
data class PairwiseGuidanceEvidence(
    val evidenceId: String,
    val provenance: EvidenceProvenance,
    val reference: GuidanceFrameIdentity,
    val candidate: GuidanceFrameIdentity,
    val rawMatchCount: Int,
    val reciprocalMatchCount: Int,
    val geometricInlierCount: Int,
    val occupiedImageCells: Int,
    val totalImageCells: Int,
    val medianImageDisplacementFraction: Double?,
    val geometricModel: GeometricModel,
    val poseValidated: Boolean,
    val poseRotationDegrees: Double?,
    val translationOverSceneDepth: Double?,
    val medianParallaxDegrees: Double?,
    val normalizedReprojectionError: Double?,
    val dynamicOutlierFraction: Double?,
) {
    init {
        require(evidenceId.isNotBlank()) { "evidenceId must not be blank" }
        require(reference.session == candidate.session) { "pair evidence must use one session" }
        require(candidate.timestampNanos >= reference.timestampNanos) {
            "candidate timestamp must not precede reference timestamp"
        }
        require(candidate.sequenceNumber > reference.sequenceNumber) {
            "candidate sequence must follow reference sequence"
        }
        require(candidate.frameId != reference.frameId) {
            "pair evidence must compare distinct frames"
        }
        require(rawMatchCount >= 0) { "rawMatchCount must be non-negative" }
        require(reciprocalMatchCount in 0..rawMatchCount) {
            "reciprocalMatchCount must not exceed rawMatchCount"
        }
        require(geometricInlierCount in 0..reciprocalMatchCount) {
            "geometricInlierCount must not exceed reciprocalMatchCount"
        }
        require(totalImageCells > 0) { "totalImageCells must be positive" }
        require(occupiedImageCells in 0..totalImageCells) {
            "occupiedImageCells must fit totalImageCells"
        }
        requireFiniteRange(medianImageDisplacementFraction, 0.0, 2.0, "medianImageDisplacementFraction")
        requireFiniteRange(poseRotationDegrees, 0.0, 180.0, "poseRotationDegrees")
        requireFiniteMinimum(translationOverSceneDepth, 0.0, "translationOverSceneDepth")
        requireFiniteRange(medianParallaxDegrees, 0.0, 180.0, "medianParallaxDegrees")
        requireFiniteMinimum(normalizedReprojectionError, 0.0, "normalizedReprojectionError")
        requireFiniteRange(dynamicOutlierFraction, 0.0, 1.0, "dynamicOutlierFraction")
        require(!poseValidated || geometricModel == GeometricModel.ESSENTIAL_POSE) {
            "validated pose evidence must use ESSENTIAL_POSE"
        }
    }

    val reciprocalRatio: Double?
        get() = if (rawMatchCount == 0) null else reciprocalMatchCount.toDouble() / rawMatchCount

    val inlierRatio: Double?
        get() = if (reciprocalMatchCount == 0) null else geometricInlierCount.toDouble() / reciprocalMatchCount

    /** Fraction of the image grid containing geometric inliers; not object-surface coverage. */
    val matchedImageAreaFraction: Double
        get() = occupiedImageCells.toDouble() / totalImageCells
}

enum class CaptureQualityState {
    UNKNOWN,
    ACCEPTABLE,
    BLUR_RISK,
    EXPOSURE_RISK,
}

data class CaptureQualityObservation(
    val evidenceId: String,
    val session: CaptureSessionIdentity,
    val timestampNanos: Long,
    val state: CaptureQualityState,
    val confidence: Double?,
) {
    init {
        require(evidenceId.isNotBlank()) { "evidenceId must not be blank" }
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative" }
        requireFiniteRange(confidence, 0.0, 1.0, "confidence")
        require(state != CaptureQualityState.UNKNOWN || confidence == null) {
            "unknown quality must not carry fabricated confidence"
        }
    }
}

enum class CaptureMotionState {
    UNKNOWN,
    STABLE,
    CAMERA_SHAKE,
    SUBJECT_MOTION,
}

data class CaptureMotionObservation(
    val evidenceId: String,
    val session: CaptureSessionIdentity,
    val timestampNanos: Long,
    val state: CaptureMotionState,
    val confidence: Double?,
    val angularVelocityDegreesPerSecond: Double?,
) {
    init {
        require(evidenceId.isNotBlank()) { "evidenceId must not be blank" }
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative" }
        requireFiniteRange(confidence, 0.0, 1.0, "confidence")
        requireFiniteMinimum(
            angularVelocityDegreesPerSecond,
            0.0,
            "angularVelocityDegreesPerSecond",
        )
        require(state != CaptureMotionState.UNKNOWN || confidence == null) {
            "unknown motion must not carry fabricated confidence"
        }
    }
}

/** Resource limits supplied by the capture/resource owner. */
data class GuidanceWindowLimits(
    val maxGeometryObservations: Int = 12,
    val maxQualityObservations: Int = 12,
    val maxMotionObservations: Int = 12,
    val maxEvidenceAgeNanos: Long = 20_000_000_000L,
    val minimumPublishIntervalNanos: Long = 250_000_000L,
    val signalAssociationToleranceNanos: Long = 180_000_000L,
) {
    init {
        require(maxGeometryObservations in 1..64)
        require(maxQualityObservations in 1..64)
        require(maxMotionObservations in 1..64)
        require(maxEvidenceAgeNanos > 0L)
        require(minimumPublishIntervalNanos >= 0L)
        require(signalAssociationToleranceNanos >= 0L)
    }
}

/** Versioned, deterministic policy thresholds. They are image/geometry evidence thresholds. */
data class GuidanceThresholds(
    val minimumRawMatches: Int = 24,
    val minimumGeometricInliers: Int = 18,
    val minimumMatchedImageAreaFraction: Double = 0.25,
    val usefulGeometricInliers: Int = 36,
    val usefulMatchedImageAreaFraction: Double = 0.35,
    val usefulMinimumInlierRatio: Double = 0.45,
    val usefulMinimumParallaxDegrees: Double = 1.2,
    val usefulMinimumTranslationOverDepth: Double = 0.02,
    val nearZeroTranslationOverDepth: Double = 0.012,
    val repeatedViewMaximumDisplacementFraction: Double = 0.025,
    val pureRotationMinimumDegrees: Double = 4.0,
    val pureRotationMaximumParallaxDegrees: Double = 0.8,
    val maximumUsefulReprojectionError: Double = 0.012,
    val movingObjectOutlierFraction: Double = 0.35,
    val signalConfidenceThreshold: Double = 0.65,
) {
    init {
        require(minimumRawMatches > 0)
        require(minimumGeometricInliers > 0)
        require(usefulGeometricInliers >= minimumGeometricInliers)
        requireUnitInterval(minimumMatchedImageAreaFraction, "minimumMatchedImageAreaFraction")
        requireUnitInterval(usefulMatchedImageAreaFraction, "usefulMatchedImageAreaFraction")
        require(usefulMatchedImageAreaFraction >= minimumMatchedImageAreaFraction)
        requireUnitInterval(usefulMinimumInlierRatio, "usefulMinimumInlierRatio")
        require(usefulMinimumParallaxDegrees >= 0.0)
        require(usefulMinimumTranslationOverDepth >= 0.0)
        require(nearZeroTranslationOverDepth in 0.0..usefulMinimumTranslationOverDepth)
        require(repeatedViewMaximumDisplacementFraction >= 0.0)
        require(pureRotationMinimumDegrees >= 0.0)
        require(pureRotationMaximumParallaxDegrees >= 0.0)
        require(maximumUsefulReprojectionError >= 0.0)
        requireUnitInterval(movingObjectOutlierFraction, "movingObjectOutlierFraction")
        requireUnitInterval(signalConfidenceThreshold, "signalConfidenceThreshold")
    }
}

data class CaptureGuidancePolicy(
    val schemaVersion: Int = 1,
    val limits: GuidanceWindowLimits = GuidanceWindowLimits(),
    val thresholds: GuidanceThresholds = GuidanceThresholds(),
) {
    init {
        require(schemaVersion == 1) { "unsupported guidance policy schema $schemaVersion" }
    }
}

enum class ImageOverlapState {
    UNKNOWN,
    INSUFFICIENT,
    USEFUL,
    VERY_HIGH_REPEATED_VIEW,
}

enum class GeometricConfidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

/** Live pair evidence cannot establish the fraction of the whole object that has been observed. */
enum class ObjectCoverageEvidence {
    UNKNOWN_NOT_MEASURED,
}

enum class GuidanceOutcome {
    UNKNOWN,
    INSUFFICIENT_OVERLAP,
    REPEATED_VIEW,
    PURE_ROTATION,
    MOVING_OBJECT,
    QUALITY_LIMITED,
    USEFUL_DIVERSITY,
}

enum class GuidanceActionCode {
    WAIT_FOR_MATCH_EVIDENCE,
    ADD_OVERLAPPING_VIEW,
    HOLD_STEADY_AND_RETAKE,
    KEEP_OBJECT_STILL_AND_RETAKE,
    MOVE_SIDEWAYS_AROUND_OBJECT,
    CHANGE_VIEWPOINT,
    CAPTURE_THIS_VIEW,
}

enum class GuidanceReasonCode {
    NO_PAIR_EVIDENCE,
    TOO_FEW_RAW_MATCHES,
    TOO_FEW_GEOMETRIC_INLIERS,
    MATCHES_CLUSTERED_IN_IMAGE,
    BLUR_RISK_AT_DIFFERENT_TIME,
    MOTION_RISK_AT_DIFFERENT_TIME,
    BLUR_OR_EXPOSURE_RISK,
    CAMERA_SHAKE,
    SUBJECT_OR_SCENE_MOTION,
    POSE_NOT_VALIDATED,
    LOW_INLIER_RATIO,
    INSUFFICIENT_BASELINE,
    NEAR_ZERO_TRANSLATION,
    LOW_PARALLAX,
    REPEATED_IMAGE_LOCATION,
    HIGH_REPROJECTION_ERROR,
    USEFUL_OVERLAP_AND_BASELINE,
}

data class GuidanceObservation(
    val session: CaptureSessionIdentity,
    val observedAtNanos: Long,
    val updateSequence: Long,
    val outcome: GuidanceOutcome,
    val imageOverlap: ImageOverlapState,
    val geometricConfidence: GeometricConfidence,
    val objectCoverage: ObjectCoverageEvidence,
    val localPairGeometryValidated: Boolean,
    val actionCode: GuidanceActionCode,
    val reasonCodes: List<GuidanceReasonCode>,
    val evidenceIds: List<String>,
    val retainedGeometryObservations: Int,
    val retainedQualityObservations: Int,
    val retainedMotionObservations: Int,
    val estimatedRetainedSummaryBytes: Int,
) {
    init {
        require(observedAtNanos >= 0L)
        require(updateSequence >= 0L)
        require(reasonCodes.isNotEmpty())
        require(evidenceIds.distinct().size == evidenceIds.size)
        require(retainedGeometryObservations >= 0)
        require(retainedQualityObservations >= 0)
        require(retainedMotionObservations >= 0)
        require(estimatedRetainedSummaryBytes >= 0)
    }
}

enum class GuidanceRejectionReason {
    NO_ACTIVE_SESSION,
    STALE_SESSION,
    OUT_OF_ORDER_EVIDENCE,
}

enum class GuidanceSuppressionReason {
    AWAITING_GEOMETRY,
    UPDATE_CADENCE,
}

sealed interface GuidanceIngestResult {
    data class Published(val observation: GuidanceObservation) : GuidanceIngestResult
    data class Stored(
        val currentObservation: GuidanceObservation?,
        val reason: GuidanceSuppressionReason,
    ) : GuidanceIngestResult

    data class Rejected(val reason: GuidanceRejectionReason) : GuidanceIngestResult
}

internal fun nearestTimestampDistance(first: Long, second: Long): Long =
    if (first >= second) first - second else second - first

private fun requireUnitInterval(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) { "$name must be finite and in [0,1]" }
}

private fun requireFiniteRange(value: Double?, minimum: Double, maximum: Double, name: String) {
    require(value == null || (value.isFinite() && value in minimum..maximum)) {
        "$name must be null or finite and in [$minimum,$maximum]"
    }
}

private fun requireFiniteMinimum(value: Double?, minimum: Double, name: String) {
    require(value == null || (value.isFinite() && value >= minimum)) {
        "$name must be null or finite and >= $minimum"
    }
}
