package com.realityforge.scanner.engine.diagnostics

internal enum class ScaleKind(val wireName: String) {
    UNKNOWN("UNKNOWN"),
    ARBITRARY("ARBITRARY"),
    METRIC_REFERENCE("METRIC_REFERENCE"),
    SENSOR_METRIC("SENSOR_METRIC");

    companion object {
        fun fromWire(value: String): ScaleKind? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class CalibrationKind(val wireName: String) {
    UNKNOWN("UNKNOWN"),
    FIXED_INTRINSICS_NO_PROFILE("FIXED_INTRINSICS_NO_PROFILE"),
    PROFILE_APPLIED("PROFILE_APPLIED"),
    REFINED_INTRINSICS("REFINED_INTRINSICS");

    companion object {
        fun fromWire(value: String): CalibrationKind? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class TopologyKind(val wireName: String) {
    UNKNOWN("UNKNOWN"),
    OBSERVED_OPEN_SURFACE("OBSERVED_OPEN_SURFACE"),
    VERIFIED_WATERTIGHT("VERIFIED_WATERTIGHT"),
    INVALID("INVALID");

    companion object {
        fun fromWire(value: String): TopologyKind? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class EvidenceCondition(val wireName: String) {
    SPARSE_FALLBACK_RETAINED("SPARSE_FALLBACK_RETAINED"),
    TEXTURE_UNAVAILABLE("TEXTURE_UNAVAILABLE"),
    SCALE_UNKNOWN("SCALE_UNKNOWN"),
    UNOBSERVED_SURFACES_UNKNOWN("UNOBSERVED_SURFACES_UNKNOWN"),
    VISIBILITY_EVIDENCE_UNKNOWN("VISIBILITY_EVIDENCE_UNKNOWN"),
    TOPOLOGY_EVIDENCE_UNKNOWN("TOPOLOGY_EVIDENCE_UNKNOWN"),
    CALIBRATION_ASSUMPTION_UNVERIFIED("CALIBRATION_ASSUMPTION_UNVERIFIED");

    companion object {
        fun fromWire(value: String): EvidenceCondition? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class VisibilityEvidence(
    val evaluatedFaces: Int?,
    val supportedFaces: Int?,
) {
    init {
        evaluatedFaces?.let { require(it >= 0) }
        supportedFaces?.let { require(it >= 0) }
        if (evaluatedFaces != null && supportedFaces != null) {
            require(supportedFaces <= evaluatedFaces) { "Supported faces cannot exceed evaluated faces." }
        }
    }

    fun supportedFractionOrNull(): Double? =
        if (evaluatedFaces != null && supportedFaces != null && evaluatedFaces > 0) {
            supportedFaces.toDouble() / evaluatedFaces
        } else null
}

internal data class TopologyEvidence(
    val kind: TopologyKind = TopologyKind.UNKNOWN,
    val boundaryEdges: Int? = null,
    val nonManifoldEdges: Int? = null,
    val connectedComponents: Int? = null,
    val selfIntersections: Int? = null,
) {
    init {
        listOf(boundaryEdges, nonManifoldEdges, connectedComponents, selfIntersections)
            .filterNotNull().forEach { require(it >= 0) }
        if (kind == TopologyKind.VERIFIED_WATERTIGHT) {
            require(boundaryEdges == 0 && nonManifoldEdges == 0) {
                "Watertight evidence requires measured zero boundary and nonmanifold edges."
            }
        }
    }
}

internal data class UnitEvidence(
    val scaleKind: ScaleKind = ScaleKind.UNKNOWN,
    val physicalUnit: String? = null,
    val referenceValue: Double? = null,
) {
    init {
        physicalUnit?.let {
            require(it in setOf("MILLIMETER", "CENTIMETER", "METER")) { "Unsupported physical unit." }
        }
        referenceValue?.let { require(it.isFinite() && it > 0) }
        if (scaleKind == ScaleKind.UNKNOWN || scaleKind == ScaleKind.ARBITRARY) {
            require(physicalUnit == null && referenceValue == null) {
                "Unknown or arbitrary scale cannot claim physical units."
            }
        } else {
            require(physicalUnit != null && referenceValue != null) {
                "Metric scale evidence requires both a physical unit and reference value."
            }
        }
    }
}

internal data class CalibrationEvidence(
    val kind: CalibrationKind = CalibrationKind.UNKNOWN,
    val profileApplied: Boolean? = null,
    val reprojectionRmsPixels: Double? = null,
) {
    init {
        reprojectionRmsPixels?.let { require(it.isFinite() && it >= 0) }
        if (kind == CalibrationKind.PROFILE_APPLIED) require(profileApplied == true)
    }
}

internal data class ScanEvidence(
    val visibility: VisibilityEvidence,
    val topology: TopologyEvidence,
    val units: UnitEvidence,
    val calibration: CalibrationEvidence,
    val conditions: Set<EvidenceCondition>,
    val stageFailures: List<ScanFailure>,
) {
    init { require(stageFailures.size <= MAX_DIAGNOSTIC_FAILURES) }
}
