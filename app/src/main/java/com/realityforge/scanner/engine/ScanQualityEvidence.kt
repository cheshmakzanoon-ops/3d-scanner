package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.CalibrationEvidence
import com.realityforge.scanner.engine.diagnostics.CalibrationKind
import com.realityforge.scanner.engine.diagnostics.EvidenceCondition
import com.realityforge.scanner.engine.diagnostics.RecoveryAction
import com.realityforge.scanner.engine.diagnostics.ScanEvidence
import com.realityforge.scanner.engine.diagnostics.ScanFailure
import com.realityforge.scanner.engine.diagnostics.ScanFailureCode
import com.realityforge.scanner.engine.diagnostics.ScaleKind
import com.realityforge.scanner.engine.diagnostics.TopologyEvidence
import com.realityforge.scanner.engine.diagnostics.TopologyKind
import com.realityforge.scanner.engine.diagnostics.UnitEvidence
import com.realityforge.scanner.engine.diagnostics.VisibilityEvidence

/**
 * Heuristics for evidence actually observed by the pipeline. These cannot measure missing
 * object sides, physical dimensions, or ground-truth accuracy. Kept pure so thresholds and
 * false positives can be regression-tested on JVM.
 */
internal data class ScanQualityInputs(
    val importedPhotos: Int,
    val registeredCameras: Int,
    val sparsePoints: Int,
    val medianParallaxDegrees: Double?,
    val medianReprojectionNormalized: Double?,
    val sampledDepthPixels: Int,
    val consistentDepthPixels: Int,
    val meshVertices: Int,
    val meshTriangles: Int,
    val photographicFaceFraction: Double?,
    val visibilityEvaluatedFaces: Int? = null,
    val visibilitySupportedFaces: Int? = null,
    val topologyKind: TopologyKind = TopologyKind.UNKNOWN,
    val topologyBoundaryEdges: Int? = null,
    val topologyNonManifoldEdges: Int? = null,
    val topologyConnectedComponents: Int? = null,
    val topologySelfIntersections: Int? = null,
    val topologyIncomplete: Boolean? = null,
    val scaleKind: ScaleKind = ScaleKind.UNKNOWN,
    val physicalUnit: String? = null,
    val scaleReferenceValue: Double? = null,
    val calibrationKind: CalibrationKind = CalibrationKind.UNKNOWN,
    val calibrationProfileApplied: Boolean? = null,
    val calibrationRmsPixels: Double? = null,
    val stageFailures: List<ScanFailure> = emptyList(),
)

internal data class ScanQualityFinding(
    val code: String,
    val action: String,
    val actionCode: RecoveryAction,
    val messageKey: String,
)

internal data class ScanQualitySummary(
    val status: String,
    val registeredFraction: Double,
    val reciprocalFraction: Double?,
    val photographicFaceFraction: Double?,
    val findings: List<ScanQualityFinding>,
    val evidence: ScanEvidence,
)

/** Canonical fallback display copy for the legacy M19 UI. Localization owners replace it. */
internal object ScanQualityRecommendationCatalog {
    private val values = listOf(
        ScanQualityFinding(
            "SURFACE_TOPOLOGY_DEFECTS",
            "Measured non-manifold edges or intersecting faces were detected. Inspect the observed mesh before downstream use; exporting does not repair topology.",
            RecoveryAction.INSPECT_OPEN_SURFACE,
            "scan.guidance.surface_topology_defects",
        ),
        ScanQualityFinding(
            "FEW_REGISTERED_CAMERAS",
            "Add overlapping photographs from distinct sideways viewpoints, then reconstruct again.",
            RecoveryAction.RECAPTURE_OVERLAP,
            "scan.guidance.few_registered_cameras",
        ),
        ScanQualityFinding(
            "PHOTOS_NOT_REGISTERED",
            "Ensure each new photograph overlaps at least two already-aligned views with visible texture.",
            RecoveryAction.RECAPTURE_OVERLAP,
            "scan.guidance.photos_not_registered",
        ),
        ScanQualityFinding(
            "LOW_PARALLAX",
            "Move the camera sideways around the object rather than turning it in place.",
            RecoveryAction.RECAPTURE_SIDEWAYS,
            "scan.guidance.low_parallax",
        ),
        ScanQualityFinding(
            "HIGH_REPROJECTION_ERROR",
            "Improve calibration, avoid motion blur, and retake views with stable lens zoom.",
            RecoveryAction.RECALIBRATE,
            "scan.guidance.high_reprojection_error",
        ),
        ScanQualityFinding(
            "DEPTH_UNAVAILABLE",
            "Use textured, matte rigid objects and sufficient calibrated overlapping viewpoints.",
            RecoveryAction.RECAPTURE_TEXTURE,
            "scan.guidance.depth_unavailable",
        ),
        ScanQualityFinding(
            "LOW_RECIPROCAL_DEPTH_SUPPORT",
            "Add textured overlapping photographs and avoid reflective, transparent or textureless surfaces.",
            RecoveryAction.RECAPTURE_TEXTURE,
            "scan.guidance.low_reciprocal_depth_support",
        ),
        ScanQualityFinding(
            "INSUFFICIENT_SURFACE_TRIANGLES",
            "Reconstruct more observed surfaces before relying on triangle-mesh exports.",
            RecoveryAction.RETRY_STAGE,
            "scan.guidance.insufficient_surface_triangles",
        ),
        ScanQualityFinding(
            "LIMITED_PHOTOGRAPHIC_FACE_COVERAGE",
            "Capture additional visible sides under steady light for more photo-supported faces.",
            RecoveryAction.RECAPTURE_TEXTURE,
            "scan.guidance.limited_photographic_face_coverage",
        ),
        ScanQualityFinding(
            "SPARSE_FALLBACK_RETAINED",
            "Sparse fallback geometry was retained; improve overlap or calibration before relying on dense output.",
            RecoveryAction.RECAPTURE_OVERLAP,
            "scan.guidance.sparse_fallback_retained",
        ),
        ScanQualityFinding(
            "TEXTURE_UNAVAILABLE",
            "No validated photographic texture was published; capture matte overlapping views and retry texturing.",
            RecoveryAction.RECAPTURE_TEXTURE,
            "scan.guidance.texture_unavailable",
        ),
        ScanQualityFinding(
            "SCALE_UNKNOWN",
            "Scale is arbitrary; add a validated known-distance reference before using physical dimensions.",
            RecoveryAction.SET_SCALE_REFERENCE,
            "scan.guidance.scale_unknown",
        ),
        ScanQualityFinding(
            "UNOBSERVED_SURFACES_UNKNOWN",
            "Inspect open boundaries and capture missing visible sides; unobserved surfaces were not reconstructed.",
            RecoveryAction.INSPECT_OPEN_SURFACE,
            "scan.guidance.unobserved_surfaces_unknown",
        ),
    ).associateBy { it.code }

    fun require(code: String): ScanQualityFinding =
        requireNotNull(values[code]) { "Unknown scan recommendation code: $code" }

    fun find(code: String): ScanQualityFinding? = values[code]
}

internal object ScanQualityEvidence {
    fun assess(data: ScanQualityInputs): ScanQualitySummary {
        require(data.importedPhotos in 1..10_000)
        require(data.registeredCameras in 0..data.importedPhotos)
        require(
            data.sparsePoints >= 0 && data.sampledDepthPixels >= 0 &&
                data.consistentDepthPixels in 0..data.sampledDepthPixels &&
                data.meshVertices >= 0 && data.meshTriangles >= 0,
        )
        require(
            data.medianParallaxDegrees == null ||
                (data.medianParallaxDegrees.isFinite() && data.medianParallaxDegrees >= 0),
        )
        require(
            data.medianReprojectionNormalized == null ||
                (data.medianReprojectionNormalized.isFinite() && data.medianReprojectionNormalized >= 0),
        )
        require(
            data.photographicFaceFraction == null ||
                (data.photographicFaceFraction.isFinite() && data.photographicFaceFraction in 0.0..1.0),
        )

        val visibility = VisibilityEvidence(data.visibilityEvaluatedFaces, data.visibilitySupportedFaces)
        val topology = TopologyEvidence(
            kind = data.topologyKind,
            boundaryEdges = data.topologyBoundaryEdges,
            nonManifoldEdges = data.topologyNonManifoldEdges,
            connectedComponents = data.topologyConnectedComponents,
            selfIntersections = data.topologySelfIntersections,
            topologyIncomplete = data.topologyIncomplete,
        )
        val units = UnitEvidence(data.scaleKind, data.physicalUnit, data.scaleReferenceValue)
        val calibration = CalibrationEvidence(
            kind = data.calibrationKind,
            profileApplied = data.calibrationProfileApplied,
            reprojectionRmsPixels = data.calibrationRmsPixels,
        )

        val registeredFraction = data.registeredCameras.toDouble() / data.importedPhotos
        val reciprocalFraction = if (data.sampledDepthPixels > 0) {
            data.consistentDepthPixels.toDouble() / data.sampledDepthPixels
        } else null
        val findings = ArrayList<ScanQualityFinding>()
        fun add(code: String) { findings += ScanQualityRecommendationCatalog.require(code) }

        if (data.meshTriangles > 0 && data.meshVertices > 0 &&
            (topology.nonManifoldEdges?.let { it > 0 } == true ||
                topology.selfIntersections?.let { it > 0 } == true)) add("SURFACE_TOPOLOGY_DEFECTS")
        if (data.registeredCameras < 3 && data.importedPhotos >= 3) add("FEW_REGISTERED_CAMERAS")
        if (registeredFraction < .75) add("PHOTOS_NOT_REGISTERED")
        if (data.medianParallaxDegrees != null && data.medianParallaxDegrees < 1.5) add("LOW_PARALLAX")
        if (data.medianReprojectionNormalized != null && data.medianReprojectionNormalized > .008) {
            add("HIGH_REPROJECTION_ERROR")
        }
        if (reciprocalFraction == null) add("DEPTH_UNAVAILABLE")
        else if (reciprocalFraction < .15) add("LOW_RECIPROCAL_DEPTH_SUPPORT")
        if (data.meshTriangles < 100 || data.meshVertices < 30) add("INSUFFICIENT_SURFACE_TRIANGLES")
        else if (data.photographicFaceFraction != null && data.photographicFaceFraction < .75) {
            add("LIMITED_PHOTOGRAPHIC_FACE_COVERAGE")
        }

        val status = when {
            data.meshTriangles < 100 || data.meshVertices < 30 -> "SPARSE_OR_DEPTH_ONLY"
            data.photographicFaceFraction != null && data.photographicFaceFraction >= .75 ->
                "PHOTO_TEXTURED_OPEN_SURFACE"
            else -> "PARTIAL_OPEN_MESH"
        }

        val conditions = linkedSetOf<EvidenceCondition>()
        if (data.stageFailures.any { it.code == ScanFailureCode.SPARSE_FALLBACK_RETAINED }) {
            conditions += EvidenceCondition.SPARSE_FALLBACK_RETAINED
        }
        if (
            data.meshTriangles >= 100 && data.meshVertices >= 30 &&
            (data.photographicFaceFraction == null ||
                data.stageFailures.any { it.code == ScanFailureCode.TEXTURE_UNAVAILABLE })
        ) {
            conditions += EvidenceCondition.TEXTURE_UNAVAILABLE
        }
        if (data.scaleKind == ScaleKind.UNKNOWN || data.scaleKind == ScaleKind.ARBITRARY) {
            conditions += EvidenceCondition.SCALE_UNKNOWN
        }
        // A reconstructed mesh, including a watertight one, is not evidence that every physical side was observed.
        conditions += EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN
        if (visibility.evaluatedFaces == null || visibility.supportedFaces == null) {
            conditions += EvidenceCondition.VISIBILITY_EVIDENCE_UNKNOWN
        }
        if (topology.kind == TopologyKind.UNKNOWN || topology.topologyIncomplete != false) conditions += EvidenceCondition.TOPOLOGY_EVIDENCE_UNKNOWN
        if (calibration.kind == CalibrationKind.UNKNOWN ||
            calibration.kind == CalibrationKind.FIXED_INTRINSICS_NO_PROFILE) {
            conditions += EvidenceCondition.CALIBRATION_ASSUMPTION_UNVERIFIED
        }

        return ScanQualitySummary(
            status = status,
            registeredFraction = registeredFraction,
            reciprocalFraction = reciprocalFraction,
            photographicFaceFraction = data.photographicFaceFraction,
            findings = findings,
            evidence = ScanEvidence(
                visibility = visibility,
                topology = topology,
                units = units,
                calibration = calibration,
                conditions = conditions,
                stageFailures = data.stageFailures.toList(),
            ),
        )
    }
}
