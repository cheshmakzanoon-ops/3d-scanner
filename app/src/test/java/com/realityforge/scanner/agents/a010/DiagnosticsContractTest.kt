package com.realityforge.scanner.agents.a010

import com.realityforge.scanner.engine.ScanQualityEvidence
import com.realityforge.scanner.engine.ScanQualityInputs
import com.realityforge.scanner.engine.diagnostics.BoundedDiagnosticEventLog
import com.realityforge.scanner.engine.diagnostics.DiagnosticContext
import com.realityforge.scanner.engine.diagnostics.DiagnosticContextKey
import com.realityforge.scanner.engine.diagnostics.DiagnosticEvent
import com.realityforge.scanner.engine.diagnostics.DiagnosticScope
import com.realityforge.scanner.engine.diagnostics.DiagnosticValue
import com.realityforge.scanner.engine.diagnostics.EvidenceCondition
import com.realityforge.scanner.engine.diagnostics.ProgressUnit
import com.realityforge.scanner.engine.diagnostics.RecoveryAction
import com.realityforge.scanner.engine.diagnostics.ScanFailure
import com.realityforge.scanner.engine.diagnostics.ScanFailureCode
import com.realityforge.scanner.engine.diagnostics.ScanStage
import com.realityforge.scanner.engine.diagnostics.StageOutcome
import com.realityforge.scanner.engine.diagnostics.StageProgress
import com.realityforge.scanner.engine.diagnostics.StageProgressLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsContractTest {
    @Test fun progressIsMonotonicOnlyInsideAnExactJobAttemptAndStage() {
        val first = DiagnosticScope("job-17", 1, 8)
        val ledger = StageProgressLedger(first, maxEvents = 4)
        ledger.record(StageProgress(first, ScanStage.MATCHING, 2, 10, ProgressUnit.PAIRS, 1))
        ledger.record(StageProgress(first, ScanStage.MATCHING, 6, 10, ProgressUnit.PAIRS, 2))
        ledger.record(StageProgress(first, ScanStage.DENSE_DEPTH, 0, null, ProgressUnit.PIXELS, 1))

        assertThrows(IllegalArgumentException::class.java) {
            ledger.record(StageProgress(first, ScanStage.MATCHING, 5, 10, ProgressUnit.PAIRS, 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.record(StageProgress(
                DiagnosticScope("another-job", 1, 8),
                ScanStage.MATCHING,
                7,
                10,
                ProgressUnit.PAIRS,
                3,
            ))
        }

        val retry = DiagnosticScope("job-17", 2, 8)
        ledger.activate(retry)
        ledger.record(StageProgress(retry, ScanStage.MATCHING, 0, 10, ProgressUnit.PAIRS, 0))
        assertEquals(0, ledger.latest(ScanStage.MATCHING)!!.completed)
        assertEquals(4, ledger.snapshot().size)
    }

    @Test fun changingAKnownTotalOrUnitIsRejected() {
        val scope = DiagnosticScope("job-3", 1, 11)
        val ledger = StageProgressLedger(scope)
        ledger.record(StageProgress(scope, ScanStage.SURFACE, 1, 4, ProgressUnit.STEPS, 1))
        assertThrows(IllegalArgumentException::class.java) {
            ledger.record(StageProgress(scope, ScanStage.SURFACE, 2, 5, ProgressUnit.STEPS, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.record(StageProgress(scope, ScanStage.SURFACE, 2, 4, ProgressUnit.TRIANGLES, 2))
        }
    }

    @Test fun eventRetentionIsBoundedAndKeepsTheNewestEvidence() {
        val scope = DiagnosticScope("job-events", 1, 2)
        val log = BoundedDiagnosticEventLog(maxEvents = 3)
        repeat(7) { index ->
            log.append(DiagnosticEvent(
                scope,
                ScanStage.EXPORT,
                StageOutcome.PARTIAL,
                index.toLong(),
            ))
        }
        assertEquals(listOf(4L, 5L, 6L), log.snapshot().map { it.sequence })
    }

    @Test fun diagnosticContextCannotCarryPathsUrisOrEmbeddedPhotos() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticContext.of(
                DiagnosticContextKey.ENGINE_VERSION to DiagnosticValue.Text("file:/storage/private/photo.jpg"),
            )
        }
        val safe = DiagnosticContext.of(
            DiagnosticContextKey.REGISTERED_CAMERA_COUNT to DiagnosticValue.Count(7),
            DiagnosticContextKey.ENGINE_VERSION to DiagnosticValue.Text("m20-engine-2"),
        )
        assertEquals(2, safe.values.size)
    }

    @Test fun sparseFallbackMissingTextureUnknownScaleAndUnobservedSurfaceStayDistinct() {
        val failures = listOf(
            ScanFailure(
                ScanFailureCode.SPARSE_FALLBACK_RETAINED,
                ScanStage.SPARSE_GEOMETRY,
                StageOutcome.PARTIAL,
                RecoveryAction.RECAPTURE_OVERLAP,
                "scan.failure.sparse_fallback_retained",
            ),
            ScanFailure(
                ScanFailureCode.TEXTURE_UNAVAILABLE,
                ScanStage.TEXTURE,
                StageOutcome.FAILED,
                RecoveryAction.RECAPTURE_TEXTURE,
                "scan.failure.texture_unavailable",
            ),
        )
        val summary = ScanQualityEvidence.assess(ScanQualityInputs(
            importedPhotos = 12,
            registeredCameras = 10,
            sparsePoints = 1800,
            medianParallaxDegrees = 3.2,
            medianReprojectionNormalized = .003,
            sampledDepthPixels = 20_000,
            consistentDepthPixels = 7_000,
            meshVertices = 3_000,
            meshTriangles = 5_000,
            photographicFaceFraction = null,
            stageFailures = failures,
        ))
        assertEquals("PARTIAL_OPEN_MESH", summary.status)
        assertTrue(EvidenceCondition.SPARSE_FALLBACK_RETAINED in summary.evidence.conditions)
        assertTrue(EvidenceCondition.TEXTURE_UNAVAILABLE in summary.evidence.conditions)
        assertTrue(EvidenceCondition.SCALE_UNKNOWN in summary.evidence.conditions)
        assertTrue(EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN in summary.evidence.conditions)
    }
}
