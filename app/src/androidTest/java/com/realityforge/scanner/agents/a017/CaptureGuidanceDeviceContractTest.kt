package com.realityforge.scanner.agents.a017

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.capture.guidance.CaptureGuidanceEngine
import com.realityforge.scanner.capture.guidance.CaptureGuidancePolicy
import com.realityforge.scanner.capture.guidance.CaptureSessionIdentity
import com.realityforge.scanner.capture.guidance.EvidenceProvenance
import com.realityforge.scanner.capture.guidance.GeometricModel
import com.realityforge.scanner.capture.guidance.GuidanceFrameIdentity
import com.realityforge.scanner.capture.guidance.GuidanceIngestResult
import com.realityforge.scanner.capture.guidance.GuidanceOutcome
import com.realityforge.scanner.capture.guidance.GuidanceRejectionReason
import com.realityforge.scanner.capture.guidance.GuidanceWindowLimits
import com.realityforge.scanner.capture.guidance.ObjectCoverageEvidence
import com.realityforge.scanner.capture.guidance.PairwiseGuidanceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureGuidanceDeviceContractTest {
    @Test
    fun realTimeStateMachineKeepsCoverageUnknownAndRejectsStaleSession() {
        val engine = CaptureGuidanceEngine(
            CaptureGuidancePolicy(
                limits = GuidanceWindowLimits(minimumPublishIntervalNanos = 0L),
            ),
        )
        engine.beginSession(SESSION)
        val published = engine.recordGeometry(evidence(SESSION)) as GuidanceIngestResult.Published

        assertEquals(GuidanceOutcome.USEFUL_DIVERSITY, published.observation.outcome)
        assertEquals(
            ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED,
            published.observation.objectCoverage,
        )

        engine.beginSession(OTHER_SESSION)
        val rejected = engine.recordGeometry(evidence(SESSION)) as GuidanceIngestResult.Rejected
        assertEquals(GuidanceRejectionReason.STALE_SESSION, rejected.reason)
        assertTrue(engine.currentObservation()?.evidenceIds?.isEmpty() == true)
    }

    private fun evidence(session: CaptureSessionIdentity) = PairwiseGuidanceEvidence(
        evidenceId = "device-contract-pair",
        provenance = EvidenceProvenance.REPLAY_FIXTURE,
        reference = GuidanceFrameIdentity(session, "reference", 1_000_000_000L, 1),
        candidate = GuidanceFrameIdentity(session, "candidate", 1_300_000_000L, 2),
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
        val SESSION = CaptureSessionIdentity("project-a", 1L, "session-a")
        val OTHER_SESSION = CaptureSessionIdentity("project-b", 1L, "session-b")
    }
}
