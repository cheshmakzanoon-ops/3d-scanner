package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.*

/** No coordinates, photo identifiers, or arbitrary native text cross this boundary.
 * Call only for rejected pairs, after native cancellation/resource handling. A low-match
 * pair records null, so mixed evidence cannot be presented as a specific geometric cause.
 * This summary neither accepts geometry nor changes candidate selection or scoring.
 */
internal class AnchorRejectionSummary {
    private var attempted = false
    private var uniformlyWeakOrAmbiguous = true

    fun record(nativeCode: String?) {
        attempted = true
        uniformlyWeakOrAmbiguous = uniformlyWeakOrAmbiguous && when (nativeCode) {
            "WEAK_BASELINE", "PLANAR_AMBIGUITY", "ROTATION_ONLY" -> true
            else -> false
        }
    }

    fun failure(): ScanFailure = if (attempted && uniformlyWeakOrAmbiguous) {
        ScanFailure(ScanFailureCode.CAMERA_REGISTRATION_FAILED, ScanStage.MATCHING,
            StageOutcome.FAILED, RecoveryAction.RECAPTURE_SIDEWAYS,
            "scan.failure.weak_or_ambiguous_geometry")
    } else {
        ScanFailure(ScanFailureCode.INSUFFICIENT_OVERLAP, ScanStage.MATCHING,
            StageOutcome.FAILED, RecoveryAction.RECAPTURE_OVERLAP,
            "scan.failure.insufficient_overlap")
    }
}
