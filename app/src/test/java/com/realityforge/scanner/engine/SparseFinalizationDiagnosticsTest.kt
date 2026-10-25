package com.realityforge.scanner.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SparseFinalizationDiagnosticsTest {
    @Test fun twoRegisteredViewsRejectBeforeNativeWithExplicitAdmissionReason() {
        val f=FinalSparseFixture(2)
        val failure=assertThrows(IllegalArgumentException::class.java) {
            FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
                triangulate={error("Triangulation must not run")},bundle={error("Bundle must not run")})
        }
        assertEquals("Global sparse refinement not attempted: requires at least 3 registered cameras; retained 2-camera anchor geometry",failure.message)
    }

    private fun refinementAction(output: JSONObject): String {
        val failures=ScanQualityReport.build(output,3).getJSONArray("stageFailures")
        return (0 until failures.length()).map { failures.getJSONObject(it) }
            .single { it.getString("code")=="SPARSE_REFINEMENT_UNAVAILABLE" }.getString("actionCode")
    }
    @Test fun admissionRetainsLegacyFallbackAndBothConsumersUseOverlapRecovery() {
        val output=JSONObject()
        SparseFinalizationDiagnostics.record(output,SparseFinalizationDiagnostics.TwoCameraAdmission())
        assertEquals("verified_anchor_fallback",output.getString("finalSparseOutcome"))
        assertEquals(output.getString("finalSparseFailure"),output.getString("bundleAdjustmentFailure"))
        assertEquals("NOT_ATTEMPTED",output.getString("finalSparseExecutionStatus"))
        assertEquals("INSUFFICIENT_REGISTERED_CAMERAS",output.getString("finalSparseFailureCode"))
        assertEquals("RECAPTURE_OVERLAP",SparseFinalizationDiagnostics.recovery(output).wireName)
        assertEquals("RECAPTURE_OVERLAP",refinementAction(output))
    }
    @Test fun genuineBundleExceptionAndLegacyUnknownFailureAreNotAdmissionFailures() {
        val f=FinalSparseFixture(3);val expected=IllegalStateException("Actual bundle exception")
        val failure=assertThrows(IllegalStateException::class.java) {
            FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
                triangulate={ f.candidates(it) },bundle={throw expected})
        }
        assertSame(expected,failure)
        val output=JSONObject()
        SparseFinalizationDiagnostics.record(output,SparseFinalizationDiagnostics.TwoCameraAdmission())
        SparseFinalizationDiagnostics.record(output,failure)
        assertEquals(expected.message,output.getString("bundleAdjustmentFailure"))
        assertFalse(output.has("finalSparseExecutionStatus"));assertFalse(output.has("finalSparseFailureCode"))
        for (candidate in listOf(output,JSONObject().put("bundleAdjustmentFailure","Failed requirement."),
            JSONObject().put("bundleAdjustmentFailure","unknown").put("finalSparseFailureCode","UNKNOWN"))) {
            assertEquals("RECALIBRATE",SparseFinalizationDiagnostics.recovery(candidate).wireName)
            assertEquals("RECALIBRATE",refinementAction(candidate))
        }
    }
    @Test fun invalidMinimumIsStillRejectedRatherThanRelabeledAsAdmission() {
        val f=FinalSparseFixture(2)
        val failure=assertThrows(IllegalArgumentException::class.java) {
            FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,-1,
                triangulate={error("Unexpected native")},bundle={error("Unexpected bundle")})
        }
        assertFalse(failure is SparseFinalizationDiagnostics.TwoCameraAdmission)
    }
    @Test fun exceptionTextCannotSpoofTypedAdmission() {
        val output=JSONObject()
        SparseFinalizationDiagnostics.record(output,
            IllegalArgumentException(SparseFinalizationDiagnostics.TwoCameraAdmission().message))
        assertFalse(output.has("finalSparseFailureCode"))
        assertFalse(output.has("finalSparseExecutionStatus"))
        assertEquals("RECALIBRATE",refinementAction(output))
    }

}
