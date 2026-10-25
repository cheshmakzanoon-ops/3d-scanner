package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.RecoveryAction
import org.json.JSONObject

/** Admission is not solver execution. Legacy failure fields remain readable by old consumers. */
internal object SparseFinalizationDiagnostics {
    const val INSUFFICIENT_REGISTERED_CAMERAS = "INSUFFICIENT_REGISTERED_CAMERAS"
    class TwoCameraAdmission : IllegalArgumentException(
        "Global sparse refinement not attempted: requires at least 3 registered cameras; retained 2-camera anchor geometry",
    )

    fun record(output: JSONObject, failure: Exception) {
        output.put("finalSparseOutcome", "verified_anchor_fallback")
            .put("finalSparseFailure", failure.message ?: "Invalid complete sparse observations")
            .put("bundleAdjustmentFailure", failure.message ?: "Global sparse finalization failed")
        if (failure is TwoCameraAdmission) {
            output.put("finalSparseExecutionStatus", "NOT_ATTEMPTED")
                .put("finalSparseFailureCode", INSUFFICIENT_REGISTERED_CAMERAS)
        } else {
            // Never carry an earlier admission diagnosis onto an actual/unknown failure.
            output.remove("finalSparseExecutionStatus")
            output.remove("finalSparseFailureCode")
        }
    }

    fun recovery(output: JSONObject): RecoveryAction =
        if (output.optString("finalSparseFailureCode") == INSUFFICIENT_REGISTERED_CAMERAS)
            RecoveryAction.RECAPTURE_OVERLAP else RecoveryAction.RECALIBRATE
}
