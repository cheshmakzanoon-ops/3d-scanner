package com.realityforge.scanner.engine.diagnostics

internal const val DIAGNOSTIC_SCHEMA_VERSION = 2
internal const val MAX_DIAGNOSTIC_EVENTS = 64
internal const val MAX_DIAGNOSTIC_FAILURES = 64
internal const val MAX_DIAGNOSTIC_CONTEXT_ENTRIES = 16

/** Stable machine codes. Display copy belongs to the presentation/localization layer. */
internal enum class ScanStage(val wireName: String) {
    IMPORT("IMPORT"),
    CALIBRATION("CALIBRATION"),
    FEATURE_EXTRACTION("FEATURE_EXTRACTION"),
    MATCHING("MATCHING"),
    CAMERA_REGISTRATION("CAMERA_REGISTRATION"),
    SPARSE_GEOMETRY("SPARSE_GEOMETRY"),
    BUNDLE_ADJUSTMENT("BUNDLE_ADJUSTMENT"),
    DENSE_DEPTH("DENSE_DEPTH"),
    SURFACE("SURFACE"),
    TEXTURE("TEXTURE"),
    EXPORT("EXPORT"),
    STORAGE("STORAGE"),
    REPORT("REPORT"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromWire(value: String): ScanStage =
            entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

internal enum class StageOutcome(val wireName: String) {
    SUCCEEDED("SUCCEEDED"),
    PARTIAL("PARTIAL"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    PAUSED("PAUSED"),
    NOT_RUN("NOT_RUN"),
    UNSUPPORTED("UNSUPPORTED"),
    BLOCKED_BY_DEPENDENCY("BLOCKED_BY_DEPENDENCY");

    companion object {
        fun fromWire(value: String): StageOutcome? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class RecoveryAction(val wireName: String) {
    NONE("NONE"),
    RETRY_STAGE("RETRY_STAGE"),
    RETRY_JOB("RETRY_JOB"),
    RESUME_JOB("RESUME_JOB"),
    RECAPTURE_OVERLAP("RECAPTURE_OVERLAP"),
    RECAPTURE_SIDEWAYS("RECAPTURE_SIDEWAYS"),
    RECAPTURE_TEXTURE("RECAPTURE_TEXTURE"),
    RECALIBRATE("RECALIBRATE"),
    FREE_STORAGE("FREE_STORAGE"),
    RESTORE_PERMISSION("RESTORE_PERMISSION"),
    REVIEW_INPUTS("REVIEW_INPUTS"),
    SET_SCALE_REFERENCE("SET_SCALE_REFERENCE"),
    INSPECT_OPEN_SURFACE("INSPECT_OPEN_SURFACE");

    companion object {
        fun fromWire(value: String): RecoveryAction? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class ProgressUnit(val wireName: String) {
    ITEMS("ITEMS"),
    PHOTOS("PHOTOS"),
    PAIRS("PAIRS"),
    CAMERAS("CAMERAS"),
    TRACKS("TRACKS"),
    PIXELS("PIXELS"),
    TRIANGLES("TRIANGLES"),
    BYTES("BYTES"),
    STEPS("STEPS");

    companion object {
        fun fromWire(value: String): ProgressUnit? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class ScanFailureCode(val wireName: String) {
    CANCELLED("CANCELLED"),
    RESOURCE_EXHAUSTED("RESOURCE_EXHAUSTED"),
    RESOURCE_PAUSED("RESOURCE_PAUSED"),
    NATIVE_LIBRARY_UNAVAILABLE("NATIVE_LIBRARY_UNAVAILABLE"),
    PROJECT_ACCESS_BLOCKED("PROJECT_ACCESS_BLOCKED"),
    STORAGE_MEASUREMENT_UNAVAILABLE("STORAGE_MEASUREMENT_UNAVAILABLE"),
    IO_FAILURE("IO_FAILURE"),
    STORAGE_FULL("STORAGE_FULL"),
    STORAGE_PERMISSION_REVOKED("STORAGE_PERMISSION_REVOKED"),
    INPUT_CORRUPT("INPUT_CORRUPT"),
    INPUT_UNSUPPORTED("INPUT_UNSUPPORTED"),
    INPUT_CHANGED("INPUT_CHANGED"),
    REVISION_MISMATCH("REVISION_MISMATCH"),
    CACHE_CORRUPT("CACHE_CORRUPT"),
    CALIBRATION_UNAVAILABLE("CALIBRATION_UNAVAILABLE"),
    INSUFFICIENT_OVERLAP("INSUFFICIENT_OVERLAP"),
    CAMERA_REGISTRATION_FAILED("CAMERA_REGISTRATION_FAILED"),
    SPARSE_FALLBACK_RETAINED("SPARSE_FALLBACK_RETAINED"),
    SPARSE_REFINEMENT_UNAVAILABLE("SPARSE_REFINEMENT_UNAVAILABLE"),
    DENSE_DEPTH_UNAVAILABLE("DENSE_DEPTH_UNAVAILABLE"),
    SURFACE_UNAVAILABLE("SURFACE_UNAVAILABLE"),
    TEXTURE_UNAVAILABLE("TEXTURE_UNAVAILABLE"),
    EXPORT_FAILED("EXPORT_FAILED"),
    INTERNAL_FAILURE("INTERNAL_FAILURE");

    companion object {
        fun fromWire(value: String): ScanFailureCode? = entries.firstOrNull { it.wireName == value }
    }
}

/** Only allowlisted scalar context can cross the diagnostics boundary. */
internal enum class DiagnosticContextKey(val wireName: String) {
    EXPECTED_REVISION("expectedRevision"),
    ACTUAL_REVISION("actualRevision"),
    IMPORTED_PHOTO_COUNT("importedPhotoCount"),
    REGISTERED_CAMERA_COUNT("registeredCameraCount"),
    FAILURE_COUNT("failureCount"),
    OBSERVATION_COUNT("observationCount"),
    REQUIRED_BYTES("requiredBytes"),
    AVAILABLE_BYTES("availableBytes"),
    REPRESENTATION_KIND("representationKind"),
    SOURCE_STATUS("sourceStatus"),
    ENGINE_VERSION("engineVersion"),
    CHECKPOINT_ID("checkpointId");

    companion object {
        fun fromWire(value: String): DiagnosticContextKey? = entries.firstOrNull { it.wireName == value }
    }
}

internal sealed class DiagnosticValue {
    data class Text(val value: String) : DiagnosticValue() {
        init {
            require(value.length <= 160) { "Diagnostic text is too long." }
            require(value.none { it.isISOControl() }) { "Diagnostic text contains control characters." }
            val lowered = value.lowercase()
            require(
                listOf("://", "content:", "file:", "data:image", "/", "\\").none {
                    lowered.contains(it)
                },
            ) { "Diagnostic text must not contain paths, URIs, or embedded image data." }
        }
    }

    data class Count(val value: Long) : DiagnosticValue() {
        init { require(value >= 0) { "Diagnostic counts must be nonnegative." } }
    }

    data class Decimal(val value: Double) : DiagnosticValue() {
        init { require(value.isFinite()) { "Diagnostic decimals must be finite." } }
    }

    data class Flag(val value: Boolean) : DiagnosticValue()
}

internal class DiagnosticContext private constructor(
    private val retained: Map<DiagnosticContextKey, DiagnosticValue>,
) {
    val values: Map<DiagnosticContextKey, DiagnosticValue> get() = retained

    companion object {
        val EMPTY = DiagnosticContext(emptyMap())

        fun of(vararg entries: Pair<DiagnosticContextKey, DiagnosticValue>): DiagnosticContext =
            from(entries.toMap())

        fun from(entries: Map<DiagnosticContextKey, DiagnosticValue>): DiagnosticContext {
            require(entries.size <= MAX_DIAGNOSTIC_CONTEXT_ENTRIES) {
                "Diagnostic context exceeds the bounded entry count."
            }
            return DiagnosticContext(java.util.Collections.unmodifiableMap(LinkedHashMap(entries)))
        }
    }
}

internal data class DiagnosticScope(
    val jobId: String,
    val attempt: Int,
    val sourceRevision: Long,
) {
    init {
        requireSafeIdentifier(jobId, "jobId")
        require(attempt >= 1) { "Attempt must be positive." }
        require(sourceRevision >= 0) { "Source revision must be nonnegative." }
    }
}

internal data class DiagnosticReportIdentity(
    val jobId: String?,
    val attempt: Int?,
    val sourceRevision: Long?,
    val artifactId: String?,
    val engineVersion: String?,
    val settingsFingerprint: String?,
    val source: String,
) {
    init {
        jobId?.let { requireSafeIdentifier(it, "jobId") }
        artifactId?.let { requireSafeIdentifier(it, "artifactId") }
        engineVersion?.let(::requireEngineVersion)
        settingsFingerprint?.let { requireSafeIdentifier(it, "settingsFingerprint") }
        require(source in setOf("EXPLICIT_JOB", "DERIVED_FROM_ARTIFACT", "UNBOUND")) {
            "Unknown diagnostic identity source."
        }
        require((attempt == null) == (jobId == null)) { "Attempt and job identity must be present together." }
        attempt?.let { require(it >= 1) { "Attempt must be positive." } }
        sourceRevision?.let { require(it >= 0) { "Source revision must be nonnegative." } }
    }
}

internal data class DiagnosticReportExpectation(
    val sourceRevision: Long? = null,
    val artifactId: String? = null,
    val jobId: String? = null,
) {
    init {
        sourceRevision?.let { require(it >= 0) }
        artifactId?.let { requireSafeIdentifier(it, "artifactId") }
        jobId?.let { requireSafeIdentifier(it, "jobId") }
    }
}

internal data class ScanFailure(
    val code: ScanFailureCode,
    val stage: ScanStage,
    val outcome: StageOutcome,
    val action: RecoveryAction,
    val messageKey: String,
    val context: DiagnosticContext = DiagnosticContext.EMPTY,
) {
    init {
        require(outcome != StageOutcome.SUCCEEDED) { "A failure cannot have a successful outcome." }
        require(messageKey.matches(Regex("[a-z][a-z0-9_.]{2,95}"))) {
            "Failure message keys must be stable localization keys."
        }
    }
}

internal fun requireSafeIdentifier(value: String, label: String) {
    require(value.length in 1..160) { "$label length is invalid." }
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:+@-]*"))) {
        "$label must be an opaque identifier, not a path or URI."
    }
}

/** Compatibility domains are versioned independently and joined by a literal pipe. */
private fun requireEngineVersion(value: String) {
    require(value.length in 1..160) { "engineVersion length is invalid." }
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+@-]*(\\|[A-Za-z0-9][A-Za-z0-9._+@-]*)*"))) {
        "engineVersion must contain bounded version identifiers, not a path or URI."
    }
}
