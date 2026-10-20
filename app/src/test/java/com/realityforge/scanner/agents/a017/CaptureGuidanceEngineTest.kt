package com.realityforge.scanner.agents.a017

import com.realityforge.scanner.capture.guidance.CaptureGuidanceEngine
import com.realityforge.scanner.capture.guidance.CaptureGuidancePolicy
import com.realityforge.scanner.capture.guidance.CaptureMotionObservation
import com.realityforge.scanner.capture.guidance.CaptureMotionState
import com.realityforge.scanner.capture.guidance.CaptureQualityObservation
import com.realityforge.scanner.capture.guidance.CaptureQualityState
import com.realityforge.scanner.capture.guidance.CaptureSessionIdentity
import com.realityforge.scanner.capture.guidance.EvidenceProvenance
import com.realityforge.scanner.capture.guidance.GeometricConfidence
import com.realityforge.scanner.capture.guidance.GeometricModel
import com.realityforge.scanner.capture.guidance.GuidanceActionCode
import com.realityforge.scanner.capture.guidance.GuidanceFrameIdentity
import com.realityforge.scanner.capture.guidance.GuidanceIngestResult
import com.realityforge.scanner.capture.guidance.GuidanceOutcome
import com.realityforge.scanner.capture.guidance.GuidanceReasonCode
import com.realityforge.scanner.capture.guidance.GuidanceRejectionReason
import com.realityforge.scanner.capture.guidance.GuidanceSuppressionReason
import com.realityforge.scanner.capture.guidance.GuidanceWindowLimits
import com.realityforge.scanner.capture.guidance.ImageOverlapState
import com.realityforge.scanner.capture.guidance.ObjectCoverageEvidence
import com.realityforge.scanner.capture.guidance.PairwiseGuidanceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGuidanceEngineTest {
    @Test
    fun initialStateIsExplicitlyUnknown() {
        val engine = CaptureGuidanceEngine()
        engine.beginSession(SESSION)

        val observation = requireNotNull(engine.currentObservation())
        assertEquals(GuidanceOutcome.UNKNOWN, observation.outcome)
        assertEquals(ImageOverlapState.UNKNOWN, observation.imageOverlap)
        assertEquals(ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED, observation.objectCoverage)
        assertFalse(observation.localPairGeometryValidated)
        assertEquals(GuidanceActionCode.WAIT_FOR_MATCH_EVIDENCE, observation.actionCode)
    }

    @Test
    fun noPairEvidenceRemainsUnknownInsteadOfInventingCoverage() {
        val observation = publish(evidence(raw = 0, reciprocal = 0, inliers = 0, cells = 0,
            model = GeometricModel.UNKNOWN, poseValidated = false,
            displacement = null, rotation = null, translation = null, parallax = null,
            reprojection = null, dynamicOutliers = null))

        assertEquals(GuidanceOutcome.UNKNOWN, observation.outcome)
        assertEquals(GuidanceReasonCode.NO_PAIR_EVIDENCE, observation.reasonCodes.single())
        assertEquals(ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED, observation.objectCoverage)
    }

    @Test
    fun insufficientOverlapUsesObservedMatchCountsAndImageDistribution() {
        val observation = publish(evidence(raw = 80, reciprocal = 22, inliers = 9, cells = 2))

        assertEquals(GuidanceOutcome.INSUFFICIENT_OVERLAP, observation.outcome)
        assertEquals(ImageOverlapState.INSUFFICIENT, observation.imageOverlap)
        assertEquals(GuidanceActionCode.ADD_OVERLAPPING_VIEW, observation.actionCode)
        assertTrue(observation.reasonCodes.contains(GuidanceReasonCode.TOO_FEW_GEOMETRIC_INLIERS))
    }

    @Test
    fun repeatedViewIsDistinctFromUsefulDiversity() {
        val observation = publish(evidence(
            displacement = 0.012,
            rotation = 1.0,
            translation = 0.004,
            parallax = 0.25,
        ))

        assertEquals(GuidanceOutcome.REPEATED_VIEW, observation.outcome)
        assertEquals(ImageOverlapState.VERY_HIGH_REPEATED_VIEW, observation.imageOverlap)
        assertEquals(GuidanceActionCode.CHANGE_VIEWPOINT, observation.actionCode)
    }

    @Test
    fun pureRotationRequestsSidewaysMovement() {
        val observation = publish(evidence(
            displacement = 0.08,
            rotation = 12.0,
            translation = 0.006,
            parallax = 0.35,
        ))

        assertEquals(GuidanceOutcome.PURE_ROTATION, observation.outcome)
        assertEquals(GuidanceActionCode.MOVE_SIDEWAYS_AROUND_OBJECT, observation.actionCode)
        assertTrue(observation.reasonCodes.contains(GuidanceReasonCode.LOW_PARALLAX))
    }

    @Test
    fun validatedPoseWithWeakInlierRatioIsNotMisreportedAsUnvalidated() {
        val observation = publish(evidence(
            raw = 200,
            reciprocal = 100,
            inliers = 40,
            cells = 12,
            poseValidated = true,
            translation = 0.08,
            parallax = 4.0,
        ))

        assertEquals(GuidanceOutcome.UNKNOWN, observation.outcome)
        assertTrue(observation.reasonCodes.contains(GuidanceReasonCode.LOW_INLIER_RATIO))
        assertFalse(observation.reasonCodes.contains(GuidanceReasonCode.POSE_NOT_VALIDATED))
    }

    @Test
    fun movingObjectEvidenceOverridesApparentlyGoodOverlap() {
        val observation = publish(evidence(dynamicOutliers = 0.62))

        assertEquals(GuidanceOutcome.MOVING_OBJECT, observation.outcome)
        assertEquals(GuidanceActionCode.KEEP_OBJECT_STILL_AND_RETAKE, observation.actionCode)
        assertTrue(observation.reasonCodes.contains(GuidanceReasonCode.SUBJECT_OR_SCENE_MOTION))
    }

    @Test
    fun usefulDiversityKeepsWholeObjectCoverageUnknown() {
        val observation = publish(evidence())

        assertEquals(GuidanceOutcome.USEFUL_DIVERSITY, observation.outcome)
        assertEquals(ImageOverlapState.USEFUL, observation.imageOverlap)
        assertEquals(GeometricConfidence.HIGH, observation.geometricConfidence)
        assertEquals(ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED, observation.objectCoverage)
        assertTrue(observation.localPairGeometryValidated)
        assertEquals(GuidanceActionCode.CAPTURE_THIS_VIEW, observation.actionCode)
    }

    @Test
    fun staleSessionEvidenceCannotAffectNewCapture() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        assertTrue(engine.recordGeometry(evidence()) is GuidanceIngestResult.Published)
        engine.beginSession(OTHER_SESSION)

        val result = engine.recordGeometry(evidence())

        assertEquals(
            GuidanceRejectionReason.STALE_SESSION,
            (result as GuidanceIngestResult.Rejected).reason,
        )
        assertEquals(GuidanceOutcome.UNKNOWN, engine.currentObservation()?.outcome)
        assertEquals(OTHER_SESSION, engine.currentObservation()?.session)
    }

    @Test
    fun outOfOrderEvidenceIsRejectedRatherThanRewindingAdvice() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordGeometry(
            evidence(
                referenceTime = 1_500_000_000L,
                candidateTime = 2_000_000_000L,
                evidenceId = "new",
                referenceSequence = 3L,
                candidateSequence = 4L,
            ),
        )

        val result = engine.recordGeometry(
            evidence(
                referenceTime = 900_000_000L,
                candidateTime = 1_000_000_000L,
                evidenceId = "late",
                referenceSequence = 1L,
                candidateSequence = 2L,
            ),
        )

        assertEquals(
            GuidanceRejectionReason.OUT_OF_ORDER_EVIDENCE,
            (result as GuidanceIngestResult.Rejected).reason,
        )
        assertTrue(engine.currentObservation()?.evidenceIds?.contains("new") == true)
        assertFalse(engine.currentObservation()?.evidenceIds?.contains("late") == true)
    }

    @Test
    fun qualitySignalMustMatchSessionAndTimestampBeforeItCanBlockGuidance() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordQuality(
            CaptureQualityObservation(
                evidenceId = "old-blur",
                session = SESSION,
                timestampNanos = 100_000_000L,
                state = CaptureQualityState.BLUR_RISK,
                confidence = 0.95,
            ),
        )

        val result = engine.recordGeometry(evidence(candidateTime = 2_000_000_000L))
        val observation = (result as GuidanceIngestResult.Published).observation

        assertEquals(GuidanceOutcome.USEFUL_DIVERSITY, observation.outcome)
        assertTrue(observation.reasonCodes.contains(GuidanceReasonCode.BLUR_RISK_AT_DIFFERENT_TIME))
        assertFalse(observation.evidenceIds.contains("old-blur"))
    }

    @Test
    fun distantAcceptableSignalsDoNotCreateRiskReasons() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordQuality(
            CaptureQualityObservation(
                evidenceId = "old-acceptable",
                session = SESSION,
                timestampNanos = 100_000_000L,
                state = CaptureQualityState.ACCEPTABLE,
                confidence = 0.95,
            ),
        )
        engine.recordMotion(
            CaptureMotionObservation(
                evidenceId = "old-stable",
                session = SESSION,
                timestampNanos = 100_000_000L,
                state = CaptureMotionState.STABLE,
                confidence = 0.95,
                angularVelocityDegreesPerSecond = 0.1,
            ),
        )

        val observation = (engine.recordGeometry(
            evidence(candidateTime = 2_000_000_000L),
        ) as GuidanceIngestResult.Published).observation

        assertFalse(observation.reasonCodes.contains(GuidanceReasonCode.BLUR_RISK_AT_DIFFERENT_TIME))
        assertFalse(observation.reasonCodes.contains(GuidanceReasonCode.MOTION_RISK_AT_DIFFERENT_TIME))
    }

    @Test
    fun timeAlignedBlurSignalProducesQualityLimitedAdvice() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordQuality(
            CaptureQualityObservation(
                evidenceId = "blur-now",
                session = SESSION,
                timestampNanos = 1_950_000_000L,
                state = CaptureQualityState.BLUR_RISK,
                confidence = 0.9,
            ),
        )

        val result = engine.recordGeometry(evidence(candidateTime = 2_000_000_000L))
        val observation = (result as GuidanceIngestResult.Published).observation

        assertEquals(GuidanceOutcome.QUALITY_LIMITED, observation.outcome)
        assertEquals(GuidanceActionCode.HOLD_STEADY_AND_RETAKE, observation.actionCode)
    }

    @Test
    fun timeAlignedSubjectMotionSignalProducesMovingObjectAdvice() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordMotion(
            CaptureMotionObservation(
                evidenceId = "subject-motion",
                session = SESSION,
                timestampNanos = 2_020_000_000L,
                state = CaptureMotionState.SUBJECT_MOTION,
                confidence = 0.85,
                angularVelocityDegreesPerSecond = 2.0,
            ),
        )

        val result = engine.recordGeometry(evidence(candidateTime = 2_000_000_000L))
        val observation = (result as GuidanceIngestResult.Published).observation

        assertEquals(GuidanceOutcome.MOVING_OBJECT, observation.outcome)
    }

    @Test
    fun retainedWindowAndEstimatedMemoryRemainBounded() {
        val policy = CaptureGuidancePolicy(
            limits = GuidanceWindowLimits(
                maxGeometryObservations = 3,
                maxQualityObservations = 2,
                maxMotionObservations = 2,
                maxEvidenceAgeNanos = 100_000_000_000L,
                minimumPublishIntervalNanos = 0L,
                signalAssociationToleranceNanos = 180_000_000L,
            ),
        )
        val engine = CaptureGuidanceEngine(policy)
        engine.beginSession(SESSION)
        repeat(10) { index ->
            val time = 1_000_000_000L + index * 1_000_000_000L
            engine.recordQuality(
                CaptureQualityObservation(
                    "q$index",
                    SESSION,
                    time,
                    CaptureQualityState.ACCEPTABLE,
                    0.9,
                ),
            )
            engine.recordMotion(
                CaptureMotionObservation(
                    "m$index",
                    SESSION,
                    time,
                    CaptureMotionState.STABLE,
                    0.9,
                    0.5,
                ),
            )
            engine.recordGeometry(evidence(
                referenceTime = time - 100_000_000L,
                candidateTime = time,
                evidenceId = "g$index",
                referenceSequence = index * 2L + 1L,
                candidateSequence = index * 2L + 2L,
            ))
        }

        assertEquals(Triple(3, 2, 2), engine.retainedSummaryCounts())
        val observation = requireNotNull(engine.currentObservation())
        assertEquals(3, observation.retainedGeometryObservations)
        assertEquals(2, observation.retainedQualityObservations)
        assertEquals(2, observation.retainedMotionObservations)
        assertTrue(observation.estimatedRetainedSummaryBytes <= 3 * 384 + 2 * 128 + 2 * 144)
    }

    @Test
    fun publishCadenceStoresIntermediateEvidenceWithoutFlicker() {
        val engine = CaptureGuidanceEngine(
            CaptureGuidancePolicy(
                limits = GuidanceWindowLimits(minimumPublishIntervalNanos = 500_000_000L),
            ),
        )
        engine.beginSession(SESSION)
        val first = engine.recordGeometry(
            evidence(
                referenceTime = 900_000_000L,
                candidateTime = 1_000_000_000L,
                evidenceId = "first",
                referenceSequence = 1L,
                candidateSequence = 2L,
            ),
        )
        val second = engine.recordGeometry(
            evidence(
                referenceTime = 1_100_000_000L,
                candidateTime = 1_200_000_000L,
                evidenceId = "second",
                referenceSequence = 3L,
                candidateSequence = 4L,
            ),
        )
        val third = engine.recordGeometry(
            evidence(
                referenceTime = 1_500_000_000L,
                candidateTime = 1_600_000_000L,
                evidenceId = "third",
                referenceSequence = 5L,
                candidateSequence = 6L,
            ),
        )

        assertTrue(first is GuidanceIngestResult.Published)
        assertEquals(
            GuidanceSuppressionReason.UPDATE_CADENCE,
            (second as GuidanceIngestResult.Stored).reason,
        )
        assertTrue(third is GuidanceIngestResult.Published)
        assertEquals(2L, engine.currentObservation()?.updateSequence)
        assertTrue(engine.currentObservation()?.evidenceIds?.contains("second") == true)
    }

    @Test
    fun deterministicReplayDistinguishesRequiredGuidanceOutcomes() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        val sequence = listOf(
            evidence(
                raw = 8,
                reciprocal = 5,
                inliers = 3,
                cells = 2,
                referenceTime = 900_000_000L,
                candidateTime = 1_000_000_000L,
                evidenceId = "low-overlap",
                referenceSequence = 1L,
                candidateSequence = 2L,
            ),
            evidence(
                displacement = 0.01,
                rotation = 1.0,
                translation = 0.003,
                parallax = 0.2,
                referenceTime = 1_100_000_000L,
                candidateTime = 1_200_000_000L,
                evidenceId = "repeated",
                referenceSequence = 3L,
                candidateSequence = 4L,
            ),
            evidence(
                displacement = 0.15,
                rotation = 12.0,
                translation = 0.003,
                parallax = 0.3,
                referenceTime = 1_300_000_000L,
                candidateTime = 1_400_000_000L,
                evidenceId = "pure-rotation",
                referenceSequence = 5L,
                candidateSequence = 6L,
            ),
            evidence(
                dynamicOutliers = 0.7,
                referenceTime = 1_500_000_000L,
                candidateTime = 1_600_000_000L,
                evidenceId = "moving-object",
                referenceSequence = 7L,
                candidateSequence = 8L,
            ),
            evidence(
                referenceTime = 1_700_000_000L,
                candidateTime = 2_000_000_000L,
                evidenceId = "useful",
                referenceSequence = 9L,
                candidateSequence = 10L,
            ),
        )

        val outcomes = sequence.map {
            (engine.recordGeometry(it) as GuidanceIngestResult.Published).observation.outcome
        }

        assertEquals(
            listOf(
                GuidanceOutcome.INSUFFICIENT_OVERLAP,
                GuidanceOutcome.REPEATED_VIEW,
                GuidanceOutcome.PURE_ROTATION,
                GuidanceOutcome.MOVING_OBJECT,
                GuidanceOutcome.USEFUL_DIVERSITY,
            ),
            outcomes,
        )
    }

    @Test
    fun nonIncreasingFrameSequenceIsRejected() {
        val engine = CaptureGuidanceEngine(policyWithNoCadence())
        engine.beginSession(SESSION)
        engine.recordGeometry(
            evidence(
                referenceTime = 900_000_000L,
                candidateTime = 1_000_000_000L,
                evidenceId = "first",
            ),
        )

        val result = engine.recordGeometry(
            evidence(
                referenceTime = 1_100_000_000L,
                candidateTime = 1_200_000_000L,
                evidenceId = "sequence-replay",
                referenceSequence = 0L,
                candidateSequence = 1L,
            ),
        )

        assertEquals(
            GuidanceRejectionReason.OUT_OF_ORDER_EVIDENCE,
            (result as GuidanceIngestResult.Rejected).reason,
        )
    }

    @Test
    fun malformedEvidenceIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            evidence(raw = 10, reciprocal = 11, inliers = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence(cells = 17)
        }
    }

    private fun publish(evidence: PairwiseGuidanceEvidence) =
        CaptureGuidanceEngine(policyWithNoCadence()).run {
            beginSession(evidence.candidate.session)
            (recordGeometry(evidence) as GuidanceIngestResult.Published).observation
        }

    private fun policyWithNoCadence() = CaptureGuidancePolicy(
        limits = GuidanceWindowLimits(minimumPublishIntervalNanos = 0L),
    )

    private fun evidence(
        raw: Int = 120,
        reciprocal: Int = 88,
        inliers: Int = 62,
        cells: Int = 11,
        model: GeometricModel = GeometricModel.ESSENTIAL_POSE,
        poseValidated: Boolean = true,
        displacement: Double? = 0.12,
        rotation: Double? = 7.0,
        translation: Double? = 0.06,
        parallax: Double? = 3.8,
        reprojection: Double? = 0.004,
        dynamicOutliers: Double? = 0.08,
        referenceTime: Long = 1_700_000_000L,
        candidateTime: Long = 2_000_000_000L,
        evidenceId: String = "pair-1",
        session: CaptureSessionIdentity = SESSION,
        referenceSequence: Long = 1L,
        candidateSequence: Long = 2L,
    ) = PairwiseGuidanceEvidence(
        evidenceId = evidenceId,
        provenance = EvidenceProvenance.REPLAY_FIXTURE,
        reference = GuidanceFrameIdentity(
            session,
            "reference-$evidenceId",
            referenceTime,
            referenceSequence,
        ),
        candidate = GuidanceFrameIdentity(
            session,
            "candidate-$evidenceId",
            candidateTime,
            candidateSequence,
        ),
        rawMatchCount = raw,
        reciprocalMatchCount = reciprocal,
        geometricInlierCount = inliers,
        occupiedImageCells = cells,
        totalImageCells = 16,
        medianImageDisplacementFraction = displacement,
        geometricModel = model,
        poseValidated = poseValidated,
        poseRotationDegrees = rotation,
        translationOverSceneDepth = translation,
        medianParallaxDegrees = parallax,
        normalizedReprojectionError = reprojection,
        dynamicOutlierFraction = dynamicOutliers,
    )

    private companion object {
        val SESSION = CaptureSessionIdentity("project-a", 7L, "session-a")
        val OTHER_SESSION = CaptureSessionIdentity("project-b", 3L, "session-b")
    }
}
