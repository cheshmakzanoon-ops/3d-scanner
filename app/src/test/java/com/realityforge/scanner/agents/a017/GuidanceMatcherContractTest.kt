package com.realityforge.scanner.agents.a017

import com.realityforge.scanner.capture.guidance.CaptureGuidanceCoordinator
import com.realityforge.scanner.capture.guidance.CaptureGuidanceEngine
import com.realityforge.scanner.capture.guidance.CaptureGuidancePolicy
import com.realityforge.scanner.capture.guidance.CaptureSessionIdentity
import com.realityforge.scanner.capture.guidance.EvidenceProvenance
import com.realityforge.scanner.capture.guidance.GeometricModel
import com.realityforge.scanner.capture.guidance.GuidanceFrame
import com.realityforge.scanner.capture.guidance.GuidanceFrameIdentity
import com.realityforge.scanner.capture.guidance.GuidanceIngestResult
import com.realityforge.scanner.capture.guidance.GuidanceMatcher
import com.realityforge.scanner.capture.guidance.GuidanceOutcome
import com.realityforge.scanner.capture.guidance.GuidanceWindowLimits
import com.realityforge.scanner.capture.guidance.PairwiseGuidanceEvidence
import com.realityforge.scanner.capture.guidance.PipelineGuidanceFrameReference
import com.realityforge.scanner.capture.guidance.PipelineGuidanceMatcher
import com.realityforge.scanner.capture.guidance.PipelinePairEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceMatcherContractTest {
    @Test
    fun injectedMatcherFeedsOnlyBoundedEvidenceIntoCoordinator() {
        val matcher = GuidanceMatcher<String> { reference, candidate ->
            evidence(reference.identity, candidate.identity, EvidenceProvenance.REPLAY_FIXTURE)
        }
        val engine = CaptureGuidanceEngine(
            CaptureGuidancePolicy(
                limits = GuidanceWindowLimits(minimumPublishIntervalNanos = 0L),
            ),
        )
        val coordinator = CaptureGuidanceCoordinator(matcher, engine)
        coordinator.beginSession(SESSION)

        val result = coordinator.compare(
            GuidanceFrame(frame("reference", 1_000_000_000L, 1), "large-buffer-owned-by-caller"),
            GuidanceFrame(frame("candidate", 1_300_000_000L, 2), "large-buffer-owned-by-caller"),
        )

        assertEquals(
            GuidanceOutcome.USEFUL_DIVERSITY,
            (result as GuidanceIngestResult.Published).observation.outcome,
        )
        assertEquals(1, engine.retainedSummaryCounts().first)
    }

    @Test
    fun coordinatorRejectsMatcherOutputForDifferentFrames() {
        val wrong = CaptureSessionIdentity("other", 0L, "other-session")
        val matcher = GuidanceMatcher<String> { reference, candidate ->
            evidence(
                reference.identity.copy(session = wrong),
                candidate.identity.copy(session = wrong),
                EvidenceProvenance.REPLAY_FIXTURE,
            )
        }
        val coordinator = CaptureGuidanceCoordinator(matcher, CaptureGuidanceEngine())
        coordinator.beginSession(SESSION)

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.compare(
                GuidanceFrame(frame("reference", 1_000_000_000L, 1), "a"),
                GuidanceFrame(frame("candidate", 1_300_000_000L, 2), "b"),
            )
        }
    }

    @Test
    fun pipelineAdapterMarksEvidenceAsRealEngineEvidence() {
        val matcher = PipelineGuidanceMatcher { _, _ ->
            PipelinePairEvidence(
                evidenceId = "engine-pair",
                rawMatchCount = 100,
                reciprocalMatchCount = 80,
                geometricInlierCount = 60,
                occupiedImageCells = 12,
                totalImageCells = 16,
                medianImageDisplacementFraction = 0.12,
                geometricModel = GeometricModel.ESSENTIAL_POSE,
                poseValidated = true,
                poseRotationDegrees = 8.0,
                translationOverSceneDepth = 0.07,
                medianParallaxDegrees = 4.0,
                normalizedReprojectionError = 0.003,
                dynamicOutlierFraction = 0.05,
            )
        }
        val reference = GuidanceFrame(
            frame("reference", 1_000_000_000L, 1),
            PipelineGuidanceFrameReference("photo-1", "features-1", "calibration-a"),
        )
        val candidate = GuidanceFrame(
            frame("candidate", 1_300_000_000L, 2),
            PipelineGuidanceFrameReference("photo-2", "features-2", "calibration-a"),
        )

        val evidence = matcher.compare(reference, candidate)

        assertEquals(EvidenceProvenance.ENGINE_MATCHER, evidence.provenance)
        assertTrue(evidence.poseValidated)
        assertEquals("engine-pair", evidence.evidenceId)
    }

    @Test
    fun pipelineAdapterRefusesMixedCalibrationIdentity() {
        val matcher = PipelineGuidanceMatcher { _, _ ->
            throw AssertionError("provider must not be invoked")
        }
        val reference = GuidanceFrame(
            frame("reference", 1_000_000_000L, 1),
            PipelineGuidanceFrameReference("photo-1", "features-1", "calibration-a"),
        )
        val candidate = GuidanceFrame(
            frame("candidate", 1_300_000_000L, 2),
            PipelineGuidanceFrameReference("photo-2", "features-2", "calibration-b"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            matcher.compare(reference, candidate)
        }
    }

    private fun frame(id: String, timestamp: Long, sequence: Long) =
        GuidanceFrameIdentity(SESSION, id, timestamp, sequence)

    private fun evidence(
        reference: GuidanceFrameIdentity,
        candidate: GuidanceFrameIdentity,
        provenance: EvidenceProvenance,
    ) = PairwiseGuidanceEvidence(
        evidenceId = "injected-pair",
        provenance = provenance,
        reference = reference,
        candidate = candidate,
        rawMatchCount = 100,
        reciprocalMatchCount = 80,
        geometricInlierCount = 60,
        occupiedImageCells = 12,
        totalImageCells = 16,
        medianImageDisplacementFraction = 0.12,
        geometricModel = GeometricModel.ESSENTIAL_POSE,
        poseValidated = true,
        poseRotationDegrees = 8.0,
        translationOverSceneDepth = 0.07,
        medianParallaxDegrees = 4.0,
        normalizedReprojectionError = 0.003,
        dynamicOutlierFraction = 0.05,
    )

    private companion object {
        val SESSION = CaptureSessionIdentity("project", 4L, "session")
    }
}
