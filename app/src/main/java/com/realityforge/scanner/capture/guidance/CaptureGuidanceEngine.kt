package com.realityforge.scanner.capture.guidance

import java.util.ArrayDeque

/**
 * Bounded, session-safe live guidance state machine.
 *
 * It stores only immutable evidence summaries. It deliberately never emits a whole-object coverage
 * percentage because pairwise overlap and local pose validation do not observe unseen surfaces.
 */
class CaptureGuidanceEngine(
    private val policy: CaptureGuidancePolicy = CaptureGuidancePolicy(),
) {
    private val geometry = ArrayDeque<PairwiseGuidanceEvidence>()
    private val quality = ArrayDeque<CaptureQualityObservation>()
    private val motion = ArrayDeque<CaptureMotionObservation>()

    private var activeSession: CaptureSessionIdentity? = null
    private var lastGeometryTimestamp = -1L
    private var lastGeometrySequence = -1L
    private var lastPublishedTimestamp = -1L
    private var updateSequence = 0L
    private var latestObservation: GuidanceObservation? = null

    @Synchronized
    fun beginSession(session: CaptureSessionIdentity) {
        activeSession = session
        geometry.clear()
        quality.clear()
        motion.clear()
        lastGeometryTimestamp = -1L
        lastGeometrySequence = -1L
        lastPublishedTimestamp = -1L
        updateSequence = 0L
        latestObservation = unknownObservation(session, observedAtNanos = 0L)
    }

    @Synchronized
    fun endSession(session: CaptureSessionIdentity): Boolean {
        if (activeSession != session) return false
        activeSession = null
        geometry.clear()
        quality.clear()
        motion.clear()
        lastGeometryTimestamp = -1L
        lastGeometrySequence = -1L
        lastPublishedTimestamp = -1L
        updateSequence = 0L
        latestObservation = null
        return true
    }

    @Synchronized
    fun currentObservation(): GuidanceObservation? = latestObservation

    @Synchronized
    fun recordQuality(observation: CaptureQualityObservation): GuidanceIngestResult {
        val rejection = validateSession(observation.session)
        if (rejection != null) return GuidanceIngestResult.Rejected(rejection)
        quality.addLast(observation)
        pruneSignals(observation.timestampNanos)
        return GuidanceIngestResult.Stored(
            latestObservation,
            GuidanceSuppressionReason.AWAITING_GEOMETRY,
        )
    }

    @Synchronized
    fun recordMotion(observation: CaptureMotionObservation): GuidanceIngestResult {
        val rejection = validateSession(observation.session)
        if (rejection != null) return GuidanceIngestResult.Rejected(rejection)
        motion.addLast(observation)
        pruneSignals(observation.timestampNanos)
        return GuidanceIngestResult.Stored(
            latestObservation,
            GuidanceSuppressionReason.AWAITING_GEOMETRY,
        )
    }

    @Synchronized
    fun recordGeometry(evidence: PairwiseGuidanceEvidence): GuidanceIngestResult {
        val rejection = validateSession(evidence.candidate.session)
        if (rejection != null) return GuidanceIngestResult.Rejected(rejection)
        if (evidence.candidate.timestampNanos < lastGeometryTimestamp ||
            evidence.candidate.sequenceNumber <= lastGeometrySequence) {
            return GuidanceIngestResult.Rejected(GuidanceRejectionReason.OUT_OF_ORDER_EVIDENCE)
        }

        lastGeometryTimestamp = evidence.candidate.timestampNanos
        lastGeometrySequence = evidence.candidate.sequenceNumber
        geometry.addLast(evidence)
        pruneSignals(evidence.candidate.timestampNanos)

        val shouldPublish = lastPublishedTimestamp < 0L ||
            evidence.candidate.timestampNanos - lastPublishedTimestamp >=
            policy.limits.minimumPublishIntervalNanos
        if (!shouldPublish) {
            return GuidanceIngestResult.Stored(
                latestObservation,
                GuidanceSuppressionReason.UPDATE_CADENCE,
            )
        }

        updateSequence += 1L
        lastPublishedTimestamp = evidence.candidate.timestampNanos
        val observation = assess(evidence, updateSequence)
        latestObservation = observation
        return GuidanceIngestResult.Published(observation)
    }

    /** Visible for deterministic tests and resource receipts. */
    @Synchronized
    fun retainedSummaryCounts(): Triple<Int, Int, Int> =
        Triple(geometry.size, quality.size, motion.size)

    private fun validateSession(session: CaptureSessionIdentity): GuidanceRejectionReason? {
        val current = activeSession ?: return GuidanceRejectionReason.NO_ACTIVE_SESSION
        return if (current == session) null else GuidanceRejectionReason.STALE_SESSION
    }

    private fun pruneSignals(nowNanos: Long) {
        val oldestAllowed = if (nowNanos <= policy.limits.maxEvidenceAgeNanos) {
            0L
        } else {
            nowNanos - policy.limits.maxEvidenceAgeNanos
        }
        while (geometry.isNotEmpty() &&
            geometry.first.candidate.timestampNanos < oldestAllowed) {
            geometry.removeFirst()
        }
        pruneSignalQueue(
            quality,
            oldestAllowed,
            policy.limits.maxQualityObservations,
        ) { it.timestampNanos }
        pruneSignalQueue(
            motion,
            oldestAllowed,
            policy.limits.maxMotionObservations,
        ) { it.timestampNanos }
        trimTo(geometry, policy.limits.maxGeometryObservations)
    }

    private fun <T> pruneSignalQueue(
        queue: ArrayDeque<T>,
        oldestAllowed: Long,
        maximumSize: Int,
        timestamp: (T) -> Long,
    ) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (timestamp(iterator.next()) < oldestAllowed) iterator.remove()
        }
        while (queue.size > maximumSize) {
            val oldest = queue.minByOrNull(timestamp) ?: break
            queue.removeFirstOccurrence(oldest)
        }
    }

    private fun assess(
        evidence: PairwiseGuidanceEvidence,
        sequence: Long,
    ): GuidanceObservation {
        val thresholds = policy.thresholds
        val timestamp = evidence.candidate.timestampNanos
        val associatedQuality = nearestQuality(timestamp)
        val associatedMotion = nearestMotion(timestamp)
        val reasons = mutableListOf<GuidanceReasonCode>()
        val evidenceIds = linkedSetOf<String>()
        geometry.forEach { evidenceIds += it.evidenceId }

        val imageOverlap = overlapState(evidence)
        val confidence = geometricConfidence(evidence)

        val nearQuality = associatedQuality?.takeIf {
            nearestTimestampDistance(it.timestampNanos, timestamp) <=
                policy.limits.signalAssociationToleranceNanos
        }
        val nearMotion = associatedMotion?.takeIf {
            nearestTimestampDistance(it.timestampNanos, timestamp) <=
                policy.limits.signalAssociationToleranceNanos
        }
        nearQuality?.let { evidenceIds += it.evidenceId }
        nearMotion?.let { evidenceIds += it.evidenceId }

        if (associatedQuality != null && nearQuality == null &&
            qualityBlocks(associatedQuality)) {
            reasons += GuidanceReasonCode.BLUR_RISK_AT_DIFFERENT_TIME
        }
        if (associatedMotion != null && nearMotion == null &&
            motionBlocks(associatedMotion)) {
            reasons += GuidanceReasonCode.MOTION_RISK_AT_DIFFERENT_TIME
        }

        val classification = when {
            evidence.rawMatchCount == 0 && evidence.geometricModel == GeometricModel.UNKNOWN -> {
                reasons += GuidanceReasonCode.NO_PAIR_EVIDENCE
                Classification(
                    GuidanceOutcome.UNKNOWN,
                    GuidanceActionCode.WAIT_FOR_MATCH_EVIDENCE,
                )
            }

            qualityBlocks(nearQuality) -> {
                reasons += GuidanceReasonCode.BLUR_OR_EXPOSURE_RISK
                Classification(
                    GuidanceOutcome.QUALITY_LIMITED,
                    GuidanceActionCode.HOLD_STEADY_AND_RETAKE,
                )
            }

            nearMotion?.state == CaptureMotionState.CAMERA_SHAKE &&
                confidenceAtLeast(nearMotion.confidence) -> {
                reasons += GuidanceReasonCode.CAMERA_SHAKE
                Classification(
                    GuidanceOutcome.QUALITY_LIMITED,
                    GuidanceActionCode.HOLD_STEADY_AND_RETAKE,
                )
            }

            movingSubject(evidence, nearMotion) -> {
                reasons += GuidanceReasonCode.SUBJECT_OR_SCENE_MOTION
                Classification(
                    GuidanceOutcome.MOVING_OBJECT,
                    GuidanceActionCode.KEEP_OBJECT_STILL_AND_RETAKE,
                )
            }

            evidence.rawMatchCount < thresholds.minimumRawMatches -> {
                reasons += GuidanceReasonCode.TOO_FEW_RAW_MATCHES
                Classification(
                    GuidanceOutcome.INSUFFICIENT_OVERLAP,
                    GuidanceActionCode.ADD_OVERLAPPING_VIEW,
                )
            }

            evidence.geometricInlierCount < thresholds.minimumGeometricInliers -> {
                reasons += GuidanceReasonCode.TOO_FEW_GEOMETRIC_INLIERS
                Classification(
                    GuidanceOutcome.INSUFFICIENT_OVERLAP,
                    GuidanceActionCode.ADD_OVERLAPPING_VIEW,
                )
            }

            evidence.matchedImageAreaFraction <
                thresholds.minimumMatchedImageAreaFraction -> {
                reasons += GuidanceReasonCode.MATCHES_CLUSTERED_IN_IMAGE
                Classification(
                    GuidanceOutcome.INSUFFICIENT_OVERLAP,
                    GuidanceActionCode.ADD_OVERLAPPING_VIEW,
                )
            }

            isPureRotation(evidence) -> {
                reasons += GuidanceReasonCode.NEAR_ZERO_TRANSLATION
                reasons += GuidanceReasonCode.LOW_PARALLAX
                Classification(
                    GuidanceOutcome.PURE_ROTATION,
                    GuidanceActionCode.MOVE_SIDEWAYS_AROUND_OBJECT,
                )
            }

            isRepeatedView(evidence) -> {
                reasons += GuidanceReasonCode.NEAR_ZERO_TRANSLATION
                reasons += GuidanceReasonCode.REPEATED_IMAGE_LOCATION
                Classification(
                    GuidanceOutcome.REPEATED_VIEW,
                    GuidanceActionCode.CHANGE_VIEWPOINT,
                )
            }

            isRepeatedImage(evidence) -> {
                // A nearly identical robust image mapping establishes repeated image content.
                // It does not establish zero 3D translation or a validated camera pose.
                reasons += GuidanceReasonCode.REPEATED_IMAGE_LOCATION
                Classification(GuidanceOutcome.REPEATED_VIEW, GuidanceActionCode.CHANGE_VIEWPOINT)
            }

            !evidence.poseValidated -> {
                reasons += GuidanceReasonCode.POSE_NOT_VALIDATED
                Classification(
                    GuidanceOutcome.UNKNOWN,
                    GuidanceActionCode.MOVE_SIDEWAYS_AROUND_OBJECT,
                )
            }

            (evidence.normalizedReprojectionError ?: Double.POSITIVE_INFINITY) >
                thresholds.maximumUsefulReprojectionError -> {
                reasons += GuidanceReasonCode.HIGH_REPROJECTION_ERROR
                Classification(
                    GuidanceOutcome.UNKNOWN,
                    GuidanceActionCode.HOLD_STEADY_AND_RETAKE,
                )
            }

            isUsefulDiversity(evidence) -> {
                reasons += GuidanceReasonCode.USEFUL_OVERLAP_AND_BASELINE
                Classification(
                    GuidanceOutcome.USEFUL_DIVERSITY,
                    GuidanceActionCode.CAPTURE_THIS_VIEW,
                )
            }

            else -> {
                when {
                    (evidence.inlierRatio ?: 0.0) < thresholds.usefulMinimumInlierRatio ->
                        reasons += GuidanceReasonCode.LOW_INLIER_RATIO
                    (evidence.translationOverSceneDepth ?: 0.0) <
                        thresholds.usefulMinimumTranslationOverDepth ->
                        reasons += GuidanceReasonCode.INSUFFICIENT_BASELINE
                    (evidence.medianParallaxDegrees ?: 0.0) <
                        thresholds.usefulMinimumParallaxDegrees ->
                        reasons += GuidanceReasonCode.LOW_PARALLAX
                    else -> reasons += GuidanceReasonCode.TOO_FEW_GEOMETRIC_INLIERS
                }
                Classification(
                    GuidanceOutcome.UNKNOWN,
                    GuidanceActionCode.MOVE_SIDEWAYS_AROUND_OBJECT,
                )
            }
        }

        if (reasons.isEmpty()) reasons += GuidanceReasonCode.NO_PAIR_EVIDENCE
        return GuidanceObservation(
            session = evidence.candidate.session,
            observedAtNanos = timestamp,
            updateSequence = sequence,
            outcome = classification.outcome,
            imageOverlap = imageOverlap,
            geometricConfidence = confidence,
            objectCoverage = ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED,
            localPairGeometryValidated = evidence.poseValidated,
            actionCode = classification.action,
            reasonCodes = reasons.distinct(),
            evidenceIds = evidenceIds.toList(),
            retainedGeometryObservations = geometry.size,
            retainedQualityObservations = quality.size,
            retainedMotionObservations = motion.size,
            estimatedRetainedSummaryBytes = estimatedRetainedSummaryBytes(),
        )
    }

    private fun overlapState(evidence: PairwiseGuidanceEvidence): ImageOverlapState {
        val thresholds = policy.thresholds
        if (evidence.rawMatchCount == 0) return ImageOverlapState.UNKNOWN
        if (evidence.rawMatchCount < thresholds.minimumRawMatches ||
            evidence.geometricInlierCount < thresholds.minimumGeometricInliers ||
            evidence.matchedImageAreaFraction < thresholds.minimumMatchedImageAreaFraction) {
            return ImageOverlapState.INSUFFICIENT
        }
        return if (isRepeatedView(evidence) || isPureRotation(evidence) || isRepeatedImage(evidence)) {
            ImageOverlapState.VERY_HIGH_REPEATED_VIEW
        } else {
            ImageOverlapState.USEFUL
        }
    }

    private fun geometricConfidence(evidence: PairwiseGuidanceEvidence): GeometricConfidence {
        val thresholds = policy.thresholds
        if (evidence.geometricInlierCount == 0) return GeometricConfidence.NONE
        if (!evidence.poseValidated ||
            evidence.geometricInlierCount < thresholds.minimumGeometricInliers) {
            return GeometricConfidence.LOW
        }
        if (evidence.geometricInlierCount >= thresholds.usefulGeometricInliers &&
            (evidence.inlierRatio ?: 0.0) >= thresholds.usefulMinimumInlierRatio &&
            evidence.matchedImageAreaFraction >= thresholds.usefulMatchedImageAreaFraction &&
            (evidence.normalizedReprojectionError ?: Double.POSITIVE_INFINITY) <=
            thresholds.maximumUsefulReprojectionError) {
            return GeometricConfidence.HIGH
        }
        return GeometricConfidence.MEDIUM
    }

    private fun qualityBlocks(observation: CaptureQualityObservation?): Boolean =
        observation != null && confidenceAtLeast(observation.confidence) &&
            (observation.state == CaptureQualityState.BLUR_RISK ||
                observation.state == CaptureQualityState.EXPOSURE_RISK)

    private fun motionBlocks(observation: CaptureMotionObservation): Boolean =
        confidenceAtLeast(observation.confidence) &&
            (observation.state == CaptureMotionState.CAMERA_SHAKE ||
                observation.state == CaptureMotionState.SUBJECT_MOTION)

    private fun movingSubject(
        evidence: PairwiseGuidanceEvidence,
        observation: CaptureMotionObservation?,
    ): Boolean {
        val signaled = observation?.state == CaptureMotionState.SUBJECT_MOTION &&
            confidenceAtLeast(observation.confidence)
        val geometric = (evidence.dynamicOutlierFraction ?: 0.0) >=
            policy.thresholds.movingObjectOutlierFraction
        return signaled || geometric
    }

    private fun isRepeatedView(evidence: PairwiseGuidanceEvidence): Boolean {
        val thresholds = policy.thresholds
        val translation = evidence.translationOverSceneDepth ?: return false
        val displacement = evidence.medianImageDisplacementFraction ?: return false
        val rotation = evidence.poseRotationDegrees ?: 0.0
        return evidence.poseValidated &&
            translation <= thresholds.nearZeroTranslationOverDepth &&
            displacement <= thresholds.repeatedViewMaximumDisplacementFraction &&
            rotation < thresholds.pureRotationMinimumDegrees
    }

    private fun isRepeatedImage(evidence: PairwiseGuidanceEvidence): Boolean {
        val thresholds = policy.thresholds
        return evidence.geometricModel == GeometricModel.HOMOGRAPHY_DOMINANT &&
            evidence.geometricInlierCount >= thresholds.usefulGeometricInliers &&
            evidence.matchedImageAreaFraction >= thresholds.usefulMatchedImageAreaFraction &&
            (evidence.inlierRatio ?: 0.0) >= 0.8 &&
            (evidence.medianImageDisplacementFraction ?: Double.POSITIVE_INFINITY) <=
                thresholds.repeatedViewMaximumDisplacementFraction
    }

    private fun isPureRotation(evidence: PairwiseGuidanceEvidence): Boolean {
        val thresholds = policy.thresholds
        val translation = evidence.translationOverSceneDepth ?: return false
        val rotation = evidence.poseRotationDegrees ?: return false
        val parallax = evidence.medianParallaxDegrees ?: return false
        return evidence.poseValidated &&
            translation <= thresholds.nearZeroTranslationOverDepth &&
            rotation >= thresholds.pureRotationMinimumDegrees &&
            parallax <= thresholds.pureRotationMaximumParallaxDegrees
    }

    private fun isUsefulDiversity(evidence: PairwiseGuidanceEvidence): Boolean {
        val thresholds = policy.thresholds
        return evidence.poseValidated &&
            evidence.geometricInlierCount >= thresholds.usefulGeometricInliers &&
            (evidence.inlierRatio ?: 0.0) >= thresholds.usefulMinimumInlierRatio &&
            evidence.matchedImageAreaFraction >= thresholds.usefulMatchedImageAreaFraction &&
            (evidence.translationOverSceneDepth ?: 0.0) >=
            thresholds.usefulMinimumTranslationOverDepth &&
            (evidence.medianParallaxDegrees ?: 0.0) >=
            thresholds.usefulMinimumParallaxDegrees &&
            (evidence.normalizedReprojectionError ?: Double.POSITIVE_INFINITY) <=
            thresholds.maximumUsefulReprojectionError
    }

    private fun confidenceAtLeast(confidence: Double?): Boolean =
        confidence != null && confidence >= policy.thresholds.signalConfidenceThreshold

    private fun nearestQuality(timestampNanos: Long): CaptureQualityObservation? =
        quality.minByOrNull { nearestTimestampDistance(it.timestampNanos, timestampNanos) }

    private fun nearestMotion(timestampNanos: Long): CaptureMotionObservation? =
        motion.minByOrNull { nearestTimestampDistance(it.timestampNanos, timestampNanos) }

    private fun estimatedRetainedSummaryBytes(): Int =
        geometry.size * GEOMETRY_SUMMARY_ESTIMATED_BYTES +
            quality.size * QUALITY_SUMMARY_ESTIMATED_BYTES +
            motion.size * MOTION_SUMMARY_ESTIMATED_BYTES

    private fun unknownObservation(
        session: CaptureSessionIdentity,
        observedAtNanos: Long,
    ): GuidanceObservation = GuidanceObservation(
        session = session,
        observedAtNanos = observedAtNanos,
        updateSequence = 0L,
        outcome = GuidanceOutcome.UNKNOWN,
        imageOverlap = ImageOverlapState.UNKNOWN,
        geometricConfidence = GeometricConfidence.NONE,
        objectCoverage = ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED,
        localPairGeometryValidated = false,
        actionCode = GuidanceActionCode.WAIT_FOR_MATCH_EVIDENCE,
        reasonCodes = listOf(GuidanceReasonCode.NO_PAIR_EVIDENCE),
        evidenceIds = emptyList(),
        retainedGeometryObservations = 0,
        retainedQualityObservations = 0,
        retainedMotionObservations = 0,
        estimatedRetainedSummaryBytes = 0,
    )

    private data class Classification(
        val outcome: GuidanceOutcome,
        val action: GuidanceActionCode,
    )

    private companion object {
        // Conservative JVM-object estimates used only for a declared bounded-memory receipt.
        const val GEOMETRY_SUMMARY_ESTIMATED_BYTES = 384
        const val QUALITY_SUMMARY_ESTIMATED_BYTES = 128
        const val MOTION_SUMMARY_ESTIMATED_BYTES = 144
    }
}

private fun <T> trimTo(queue: ArrayDeque<T>, maximum: Int) {
    while (queue.size > maximum) queue.removeFirst()
}
