package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.BoundedDiagnosticEventLog
import com.realityforge.scanner.engine.diagnostics.CalibrationEvidence
import com.realityforge.scanner.engine.diagnostics.CalibrationKind
import com.realityforge.scanner.engine.diagnostics.DIAGNOSTIC_SCHEMA_VERSION
import com.realityforge.scanner.engine.diagnostics.DiagnosticContext
import com.realityforge.scanner.engine.diagnostics.DiagnosticContextKey
import com.realityforge.scanner.engine.diagnostics.DiagnosticEvent
import com.realityforge.scanner.engine.diagnostics.DiagnosticReportExpectation
import com.realityforge.scanner.engine.diagnostics.DiagnosticReportIdentity
import com.realityforge.scanner.engine.diagnostics.DiagnosticScope
import com.realityforge.scanner.engine.diagnostics.DiagnosticValue
import com.realityforge.scanner.engine.diagnostics.EvidenceCondition
import com.realityforge.scanner.engine.diagnostics.MAX_DIAGNOSTIC_EVENTS
import com.realityforge.scanner.engine.diagnostics.MAX_DIAGNOSTIC_FAILURES
import com.realityforge.scanner.engine.diagnostics.ProgressUnit
import com.realityforge.scanner.engine.diagnostics.RecoveryAction
import com.realityforge.scanner.engine.diagnostics.RuntimeDiagnosticSnapshot
import com.realityforge.scanner.engine.diagnostics.ScaleKind
import com.realityforge.scanner.engine.diagnostics.ScanFailure
import com.realityforge.scanner.engine.diagnostics.ScanFailureCode
import com.realityforge.scanner.engine.diagnostics.ScanStage
import com.realityforge.scanner.engine.diagnostics.StageOutcome
import com.realityforge.scanner.engine.diagnostics.StageProgress
import com.realityforge.scanner.engine.diagnostics.StageProgressLedger
import com.realityforge.scanner.engine.diagnostics.TopologyEvidence
import com.realityforge.scanner.engine.diagnostics.TopologyKind
import com.realityforge.scanner.engine.diagnostics.UnitEvidence
import com.realityforge.scanner.engine.diagnostics.VisibilityEvidence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted objective evidence and explicitly bounded guidance for a scan.
 * These are NOT completeness percentages or an estimate of physical accuracy.
 */
internal object ScanQualityReport {
    private const val REPORT_TYPE = "realityforge.scan_diagnostics"
    private const val MAX_REPORT_BYTES = 256 * 1024
    private const val MEASUREMENT_BASIS =
        "Observed reconstruction evidence only: NOT 360-degree completeness, physical millimeters or scan-to-reference accuracy"
    private val STATUS_CODES = setOf(
        "SPARSE_OR_DEPTH_ONLY",
        "PARTIAL_OPEN_MESH",
        "PHOTO_TEXTURED_OPEN_SURFACE",
    )

    private fun finiteNonnegative(obj: JSONObject, key: String): Double? {
        val value = obj.optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it >= 0 }
    }

    private fun nonnegativeIntOrNull(obj: JSONObject?, key: String): Int? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        val value = obj.getInt(key)
        require(value >= 0) { "$key must be nonnegative." }
        return value
    }

    /** Topology counters are JSON integers, never coerced strings, fractions or wrapped longs. */
    private fun topologyCount(obj: JSONObject?, key: String): Int? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        val raw = obj.get(key)
        require(raw is Int || raw is Long) { "$key must be a JSON integer." }
        val value = (raw as Number).toLong()
        require(value in 0..Int.MAX_VALUE.toLong()) { "$key is outside the supported count range." }
        return value.toInt()
    }

    private fun topologyAlias(obj: JSONObject?, native: String, legacy: String): Int? {
        val current = topologyCount(obj, native)
        val old = topologyCount(obj, legacy)
        require(current == null || old == null || current == old) {
            "Conflicting topology aliases: $native and $legacy."
        }
        return current ?: old
    }

    private fun topologyIncomplete(obj: JSONObject?): Boolean? {
        if (obj == null || !obj.has("topologyIncomplete") || obj.isNull("topologyIncomplete")) return null
        val value = obj.get("topologyIncomplete")
        require(value is Boolean) { "topologyIncomplete must be a JSON boolean." }
        return value
    }

    private fun nullableString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getString(key)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    fun build(
        result: JSONObject,
        importedPhotos: Int,
        runtime: RuntimeDiagnosticSnapshot? = null,
    ): JSONObject {
        val registered = result.optInt("registeredCameras", 0)
        val sparse = result.optInt("pointCount", 0)
        val dense = result.optJSONObject("densePreview")
        val mesh = result.optJSONObject("meshPreview")
        val texture = result.optJSONObject("texturePreview")
        val compactVisibility = texture?.takeIf {
            it.optString("representation") == "compact_visible_photographic_atlas"
        }?.let(PhotographicCoverage::compactVisibility)
        val fraction = texture?.let {
            finiteNonnegative(it, "faceCoverage")?.takeIf { coverage -> coverage <= 1 }
        }
        val identity = identity(result)
        runtime?.let {
            require(identity.jobId == it.scope.jobId && identity.attempt == it.scope.attempt &&
                identity.sourceRevision == it.scope.sourceRevision) {
                "Runtime diagnostics do not belong to this exact artifact job attempt."
            }
        }
        val failures = runtimeFailures(runtime, legacyFailures(result))
        val boundaryEdges = topologyCount(mesh, "boundaryEdges")
        val nonManifoldEdges = topologyAlias(mesh, "nonmanifoldEdges", "nonManifoldEdges")
        val selfIntersections = topologyAlias(mesh, "intersections", "selfIntersections")
        val incomplete = topologyIncomplete(mesh)
        val topologyKind = when {
            mesh == null || mesh.optInt("triangleCount", 0) == 0 -> TopologyKind.UNKNOWN
            mesh.optBoolean("watertight", false) &&
                incomplete == false && boundaryEdges == 0 &&
                nonManifoldEdges == 0 && selfIntersections == 0 -> TopologyKind.VERIFIED_WATERTIGHT
            else -> TopologyKind.OBSERVED_OPEN_SURFACE
        }
        val scaleKind = scaleKind(result, dense, mesh)
        val calibrationProfile = if (result.has("calibrationProfileApplied")) {
            result.getBoolean("calibrationProfileApplied")
        } else null
        val measurements = ScanQualityInputs(
            importedPhotos = importedPhotos,
            registeredCameras = registered,
            sparsePoints = sparse,
            medianParallaxDegrees = finiteNonnegative(result, "medianParallaxDegrees"),
            medianReprojectionNormalized = finiteNonnegative(result, "medianErrorNormalized"),
            sampledDepthPixels = dense?.optInt("sampledPixels") ?: 0,
            consistentDepthPixels = dense?.optInt("consistentPixels") ?: 0,
            meshVertices = mesh?.optInt("vertexCount") ?: 0,
            meshTriangles = mesh?.optInt("triangleCount") ?: 0,
            photographicFaceFraction = fraction,
            visibilityEvaluatedFaces = compactVisibility?.evaluatedFaces
                ?: nonnegativeIntOrNull(texture, "inputTriangles"),
            visibilitySupportedFaces = compactVisibility?.supportedFaces
                ?: nonnegativeIntOrNull(texture, "triangleCount"),
            topologyKind = topologyKind,
            topologyBoundaryEdges = boundaryEdges,
            topologyNonManifoldEdges = nonManifoldEdges,
            topologyConnectedComponents = topologyCount(mesh, "connectedComponents"),
            topologySelfIntersections = selfIntersections,
            topologyIncomplete = incomplete,
            scaleKind = scaleKind,
            physicalUnit = nullableString(result, "physicalUnit"),
            scaleReferenceValue = finitePositiveOrNull(result, "scaleReferenceValue"),
            calibrationKind = when (calibrationProfile) {
                true -> CalibrationKind.PROFILE_APPLIED
                false -> CalibrationKind.FIXED_INTRINSICS_NO_PROFILE
                null -> CalibrationKind.UNKNOWN
            },
            calibrationProfileApplied = calibrationProfile,
            calibrationRmsPixels = finiteNonnegative(result, "calibrationRmsPixels"),
            stageFailures = failures,
        )
        val summary = ScanQualityEvidence.assess(measurements)
        val recommendations = recommendations(summary)
        val events = runtime?.events ?: terminalEvents(identity, failures)
        val progress = runtime?.progress.orEmpty()

        return JSONObject()
            .put("schema", DIAGNOSTIC_SCHEMA_VERSION)
            .put("reportType", REPORT_TYPE)
            .put("measurementBasis", MEASUREMENT_BASIS)
            .put("identity", identityToJson(identity))
            .put("status", summary.status)
            .put("importedPhotoCount", importedPhotos)
            .put("registeredCameraCount", registered)
            .put("registeredPhotoFraction", summary.registeredFraction)
            .put("sparsePointCount", sparse)
            .putNullable("medianParallaxDegrees", measurements.medianParallaxDegrees)
            .putNullable("medianReprojectionNormalized", measurements.medianReprojectionNormalized)
            .put("sampledDepthPixels", measurements.sampledDepthPixels)
            .put("reciprocalDepthPixels", measurements.consistentDepthPixels)
            .putNullable("reciprocalDepthFraction", summary.reciprocalFraction)
            .put("observedMeshTriangles", measurements.meshTriangles)
            .put("observedMeshVertices", measurements.meshVertices)
            .putNullable("photographicallyCoveredFaceFraction", summary.photographicFaceFraction)
            .put("evidence", evidenceToJson(summary.evidence.visibility, summary.evidence.topology,
                summary.evidence.units, summary.evidence.calibration, summary.evidence.conditions))
            .put("outcomes", outcomesToJson(summary.evidence.conditions, failures))
            .put("stageFailures", JSONArray().apply { failures.forEach { put(failureToJson(it)) } })
            .put("events", JSONArray().apply { events.forEach { put(eventToJson(it)) } })
            .put("progressEvents", JSONArray().apply { progress.forEach { put(progressToJson(it)) } })
            .put("recommendations", JSONArray().apply { recommendations.forEach { put(findingToJson(it)) } })
    }

    /** Serialize one measured stage event without inventing a global job percentage. */
    fun progressToJson(progress: StageProgress): JSONObject = JSONObject()
        .put("jobId", progress.scope.jobId)
        .put("attempt", progress.scope.attempt)
        .put("sourceRevision", progress.scope.sourceRevision)
        .put("stage", progress.stage.wireName)
        .put("completed", progress.completed)
        .putNullable("total", progress.total)
        .put("unit", progress.unit.wireName)
        .put("sequence", progress.sequence)
        .put("reusedCheckpoint", progress.reusedCheckpoint)

    fun failureToJson(failure: ScanFailure): JSONObject = JSONObject()
        .put("code", failure.code.wireName)
        .put("stage", failure.stage.wireName)
        .put("outcome", failure.outcome.wireName)
        .put("actionCode", failure.action.wireName)
        .put("messageKey", failure.messageKey)
        .put("context", contextToJson(failure.context))

    private fun runtimeFailures(
        runtime: RuntimeDiagnosticSnapshot?,
        compatibility: List<ScanFailure>,
    ): List<ScanFailure> {
        if (runtime == null) return compatibility
        // Preserve measured per-stage/per-scene contexts. Legacy adapter fallbacks fill only
        // absent codes; they cannot replace an owner's exact typed failure with generic copy.
        val measured = runtime.failures
        return (measured + compatibility.filter { old -> measured.none {
            it.stage == old.stage && it.code == old.code
        } }).take(MAX_DIAGNOSTIC_FAILURES)
    }

    /**
     * Validate a report before local import/export and rebuild it from an allowlist. Unknown
     * private fields are omitted, while schema/identity/counter corruption is rejected.
     */
    fun validateAndRedact(
        report: JSONObject,
        expectation: DiagnosticReportExpectation = DiagnosticReportExpectation(),
    ): JSONObject {
        require(report.toString().toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
            "Diagnostic report exceeds the bounded size."
        }
        val normalized = when (val schema = report.optInt("schema", -1)) {
            1 -> migrateSchemaOne(report)
            DIAGNOSTIC_SCHEMA_VERSION -> sanitizeSchemaTwo(report)
            else -> throw IllegalArgumentException("Unsupported diagnostic schema: $schema")
        }
        validateExpectation(normalized.getJSONObject("identity"), expectation)
        require(normalized.toString().toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES)
        return normalized
    }

    fun importValidated(
        encoded: String,
        expectation: DiagnosticReportExpectation = DiagnosticReportExpectation(),
    ): JSONObject {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
            "Diagnostic report exceeds the bounded size."
        }
        return validateAndRedact(JSONObject(encoded), expectation)
    }

    fun exportValidated(
        report: JSONObject,
        expectation: DiagnosticReportExpectation = DiagnosticReportExpectation(),
    ): String = validateAndRedact(report, expectation).toString()

    private fun identity(result: JSONObject): DiagnosticReportIdentity {
        val artifact = nullableString(result, "artifactKey")
        val explicitJob = nullableString(result, "jobId")
        val jobId = explicitJob
        val source = when {
            explicitJob != null -> "EXPLICIT_JOB"
            else -> "UNBOUND"
        }
        return DiagnosticReportIdentity(
            jobId = jobId,
            attempt = jobId?.let {
                require(result.has("jobAttempt") && !result.isNull("jobAttempt")) { "An explicit job requires its actual attempt." }
                result.getInt("jobAttempt").also { attempt -> require(attempt > 0) }
            },
            sourceRevision = if (result.has("revision") && !result.isNull("revision")) {
                result.getLong("revision").also { require(it >= 0) }
            } else null,
            artifactId = artifact,
            engineVersion = nullableString(result, "engineVersion"),
            settingsFingerprint = nullableString(result, "settingsFingerprint"),
            source = source,
        )
    }

    private fun scaleKind(result: JSONObject, dense: JSONObject?, mesh: JSONObject?): ScaleKind {
        val raw = listOf(result, mesh, dense).firstNotNullOfOrNull { obj ->
            obj?.takeIf { it.has("scale") && !it.isNull("scale") }?.getString("scale")
        }?.uppercase()
        return when (raw) {
            "ARBITRARY" -> ScaleKind.ARBITRARY
            "METRIC_REFERENCE" -> ScaleKind.METRIC_REFERENCE
            "SENSOR_METRIC" -> ScaleKind.SENSOR_METRIC
            else -> ScaleKind.UNKNOWN
        }
    }

    private fun finitePositiveOrNull(obj: JSONObject, key: String): Double? {
        val value = obj.optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it > 0 }
    }

    private fun legacyFailures(result: JSONObject): List<ScanFailure> {
        val failures = ArrayList<ScanFailure>()
        fun add(failure: ScanFailure) {
            if (failures.none { it.code == failure.code && it.stage == failure.stage }) failures += failure
        }
        val warning = result.optString("warning", "").lowercase()
        if (warning.contains("retained") || warning.contains("fallback")) {
            add(ScanFailure(
                ScanFailureCode.SPARSE_FALLBACK_RETAINED,
                ScanStage.SPARSE_GEOMETRY,
                StageOutcome.PARTIAL,
                RecoveryAction.RECAPTURE_OVERLAP,
                "scan.failure.sparse_fallback_retained",
            ))
        }
        if (result.has("bundleAdjustmentFailure")) {
            add(ScanFailure(
                ScanFailureCode.SPARSE_REFINEMENT_UNAVAILABLE,
                ScanStage.BUNDLE_ADJUSTMENT,
                StageOutcome.PARTIAL,
                SparseFinalizationDiagnostics.recovery(result),
                "scan.failure.sparse_refinement_unavailable",
            ))
        }
        val registrationFailures = result.optJSONArray("cameraRegistrationFailures")?.length() ?: 0
        if (registrationFailures > 0) {
            add(ScanFailure(
                ScanFailureCode.CAMERA_REGISTRATION_FAILED,
                ScanStage.CAMERA_REGISTRATION,
                StageOutcome.PARTIAL,
                RecoveryAction.RECAPTURE_OVERLAP,
                "scan.failure.camera_registration_failed",
                DiagnosticContext.of(
                    DiagnosticContextKey.FAILURE_COUNT to DiagnosticValue.Count(registrationFailures.toLong()),
                ),
            ))
        }
        if (result.optString("denseStatus") == "unavailable") {
            add(unavailableFailure(
                ScanFailureCode.DENSE_DEPTH_UNAVAILABLE,
                ScanStage.DENSE_DEPTH,
                RecoveryAction.RECAPTURE_TEXTURE,
                "scan.failure.dense_depth_unavailable",
            ))
        }
        if (result.optString("meshStatus") == "unavailable") {
            add(unavailableFailure(
                ScanFailureCode.SURFACE_UNAVAILABLE,
                ScanStage.SURFACE,
                RecoveryAction.RETRY_STAGE,
                "scan.failure.surface_unavailable",
            ))
        }
        if (result.optString("textureStatus") == "unavailable") {
            add(unavailableFailure(
                ScanFailureCode.TEXTURE_UNAVAILABLE,
                ScanStage.TEXTURE,
                RecoveryAction.RECAPTURE_TEXTURE,
                "scan.failure.texture_unavailable",
            ))
        }
        return failures.take(MAX_DIAGNOSTIC_FAILURES)
    }

    private fun unavailableFailure(
        code: ScanFailureCode,
        stage: ScanStage,
        action: RecoveryAction,
        messageKey: String,
    ) = ScanFailure(
        code,
        stage,
        StageOutcome.FAILED,
        action,
        messageKey,
        DiagnosticContext.of(
            DiagnosticContextKey.SOURCE_STATUS to DiagnosticValue.Text("unavailable"),
        ),
    )

    private fun recommendations(summary: ScanQualitySummary): List<ScanQualityFinding> {
        val ordered = LinkedHashMap<String, ScanQualityFinding>()
        val conditions = summary.evidence.conditions
        listOf(
            EvidenceCondition.SPARSE_FALLBACK_RETAINED,
            EvidenceCondition.TEXTURE_UNAVAILABLE,
        ).filter { it in conditions }.forEach { condition ->
            conditionFinding(condition)?.let { ordered[it.code] = it }
        }
        summary.findings.forEach { ordered[it.code] = it }
        listOf(
            EvidenceCondition.SCALE_UNKNOWN,
            EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN,
        ).filter { it in conditions }.forEach { condition ->
            conditionFinding(condition)?.let { ordered[it.code] = it }
        }
        return ordered.values.toList()
    }

    private fun conditionFinding(condition: EvidenceCondition): ScanQualityFinding? = when (condition) {
        EvidenceCondition.SPARSE_FALLBACK_RETAINED ->
            ScanQualityRecommendationCatalog.require("SPARSE_FALLBACK_RETAINED")
        EvidenceCondition.TEXTURE_UNAVAILABLE ->
            ScanQualityRecommendationCatalog.require("TEXTURE_UNAVAILABLE")
        EvidenceCondition.SCALE_UNKNOWN -> ScanQualityRecommendationCatalog.require("SCALE_UNKNOWN")
        EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN ->
            ScanQualityRecommendationCatalog.require("UNOBSERVED_SURFACES_UNKNOWN")
        else -> null
    }

    private fun findingToJson(finding: ScanQualityFinding): JSONObject = JSONObject()
        .put("code", finding.code)
        .put("actionCode", finding.actionCode.wireName)
        .put("messageKey", finding.messageKey)
        .put("action", finding.action)

    private fun identityToJson(identity: DiagnosticReportIdentity): JSONObject = JSONObject()
        .putNullable("jobId", identity.jobId)
        .putNullable("attempt", identity.attempt)
        .putNullable("sourceRevision", identity.sourceRevision)
        .putNullable("artifactId", identity.artifactId)
        .putNullable("engineVersion", identity.engineVersion)
        .putNullable("settingsFingerprint", identity.settingsFingerprint)
        .put("source", identity.source)

    private fun evidenceToJson(
        visibility: VisibilityEvidence,
        topology: TopologyEvidence,
        units: UnitEvidence,
        calibration: CalibrationEvidence,
        conditions: Set<EvidenceCondition>,
    ): JSONObject = JSONObject()
        .put("visibility", JSONObject()
            .putNullable("evaluatedFaces", visibility.evaluatedFaces)
            .putNullable("supportedFaces", visibility.supportedFaces)
            .putNullable("supportedFraction", visibility.supportedFractionOrNull()))
        .put("topology", JSONObject()
            .put("kind", topology.kind.wireName)
            .putNullable("boundaryEdges", topology.boundaryEdges)
            .putNullable("nonManifoldEdges", topology.nonManifoldEdges)
            .putNullable("connectedComponents", topology.connectedComponents)
            .putNullable("selfIntersections", topology.selfIntersections)
            .putNullable("topologyIncomplete", topology.topologyIncomplete))
        .put("units", JSONObject()
            .put("scaleKind", units.scaleKind.wireName)
            .putNullable("physicalUnit", units.physicalUnit)
            .putNullable("referenceValue", units.referenceValue))
        .put("calibration", JSONObject()
            .put("kind", calibration.kind.wireName)
            .putNullable("profileApplied", calibration.profileApplied)
            .putNullable("reprojectionRmsPixels", calibration.reprojectionRmsPixels))
        .put("conditions", JSONArray().apply {
            conditions.sortedBy { it.wireName }.forEach { put(it.wireName) }
        })

    private fun outcomesToJson(
        conditions: Set<EvidenceCondition>,
        failures: List<ScanFailure>,
    ): JSONArray = JSONArray().apply {
        conditions.sortedBy { it.wireName }.forEach { condition ->
            put(conditionToJson(condition, failures))
        }
    }

    private fun conditionToJson(
        condition: EvidenceCondition,
        failures: List<ScanFailure>,
    ): JSONObject {
        val matchingFailure = when (condition) {
            EvidenceCondition.SPARSE_FALLBACK_RETAINED ->
                failures.firstOrNull { it.code == ScanFailureCode.SPARSE_FALLBACK_RETAINED }
            EvidenceCondition.TEXTURE_UNAVAILABLE ->
                failures.firstOrNull { it.code == ScanFailureCode.TEXTURE_UNAVAILABLE }
            else -> null
        }
        val stage: ScanStage
        val outcome: StageOutcome
        val action: RecoveryAction
        val messageKey: String
        if (matchingFailure != null) {
            stage = matchingFailure.stage
            outcome = matchingFailure.outcome
            action = matchingFailure.action
            messageKey = matchingFailure.messageKey
        } else when (condition) {
            EvidenceCondition.SPARSE_FALLBACK_RETAINED -> {
                stage = ScanStage.SPARSE_GEOMETRY; outcome = StageOutcome.PARTIAL
                action = RecoveryAction.RECAPTURE_OVERLAP
                messageKey = "scan.condition.sparse_fallback_retained"
            }
            EvidenceCondition.TEXTURE_UNAVAILABLE -> {
                stage = ScanStage.TEXTURE; outcome = StageOutcome.NOT_RUN
                action = RecoveryAction.RECAPTURE_TEXTURE
                messageKey = "scan.condition.texture_unavailable"
            }
            EvidenceCondition.SCALE_UNKNOWN -> {
                stage = ScanStage.REPORT; outcome = StageOutcome.PARTIAL
                action = RecoveryAction.SET_SCALE_REFERENCE
                messageKey = "scan.condition.scale_unknown"
            }
            EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN -> {
                stage = ScanStage.SURFACE; outcome = StageOutcome.PARTIAL
                action = RecoveryAction.INSPECT_OPEN_SURFACE
                messageKey = "scan.condition.unobserved_surfaces_unknown"
            }
            EvidenceCondition.VISIBILITY_EVIDENCE_UNKNOWN -> {
                stage = ScanStage.TEXTURE; outcome = StageOutcome.NOT_RUN
                action = RecoveryAction.NONE
                messageKey = "scan.condition.visibility_evidence_unknown"
            }
            EvidenceCondition.TOPOLOGY_EVIDENCE_UNKNOWN -> {
                stage = ScanStage.SURFACE; outcome = StageOutcome.NOT_RUN
                action = RecoveryAction.NONE
                messageKey = "scan.condition.topology_evidence_unknown"
            }
            EvidenceCondition.CALIBRATION_ASSUMPTION_UNVERIFIED -> {
                stage = ScanStage.CALIBRATION; outcome = StageOutcome.PARTIAL
                action = RecoveryAction.RECALIBRATE
                messageKey = "scan.condition.calibration_assumption_unverified"
            }
        }
        return JSONObject()
            .put("code", condition.wireName)
            .put("stage", stage.wireName)
            .put("outcome", outcome.wireName)
            .put("actionCode", action.wireName)
            .put("messageKey", messageKey)
    }

    private fun terminalEvents(
        identity: DiagnosticReportIdentity,
        failures: List<ScanFailure>,
    ): List<DiagnosticEvent> {
        if (identity.jobId == null || identity.attempt == null || identity.sourceRevision == null) return emptyList()
        val scope = DiagnosticScope(identity.jobId, identity.attempt, identity.sourceRevision)
        val log = BoundedDiagnosticEventLog()
        failures.forEachIndexed { index, failure ->
            log.append(DiagnosticEvent(
                scope = scope,
                stage = failure.stage,
                outcome = failure.outcome,
                sequence = index.toLong(),
                failure = failure,
            ))
        }
        return log.snapshot()
    }

    private fun eventToJson(event: DiagnosticEvent): JSONObject = JSONObject()
        .put("jobId", event.scope.jobId)
        .put("attempt", event.scope.attempt)
        .put("sourceRevision", event.scope.sourceRevision)
        .put("stage", event.stage.wireName)
        .put("outcome", event.outcome.wireName)
        .put("sequence", event.sequence)
        .putNullable("progress", event.progress?.let { progressBodyToJson(it) })
        .putNullable("failure", event.failure?.let { failureToJson(it) })

    private fun progressBodyToJson(progress: StageProgress): JSONObject = JSONObject()
        .put("completed", progress.completed)
        .putNullable("total", progress.total)
        .put("unit", progress.unit.wireName)
        .put("sequence", progress.sequence)
        .put("reusedCheckpoint", progress.reusedCheckpoint)

    private fun contextToJson(context: DiagnosticContext): JSONObject = JSONObject().apply {
        context.values.toSortedMap(compareBy { it.wireName }).forEach { (key, value) ->
            put(key.wireName, when (value) {
                is DiagnosticValue.Text -> value.value
                is DiagnosticValue.Count -> value.value
                is DiagnosticValue.Decimal -> value.value
                is DiagnosticValue.Flag -> value.value
            })
        }
    }

    private fun migrateSchemaOne(legacy: JSONObject): JSONObject {
        val status = legacy.optString("status")
        require(status in STATUS_CODES) { "Unknown legacy diagnostic status." }
        val imported = requiredCount(legacy, "importedPhotoCount")
        val registered = requiredCount(legacy, "registeredCameraCount")
        require(registered <= imported)
        val meshTriangles = requiredCount(legacy, "observedMeshTriangles")
        val meshVertices = requiredCount(legacy, "observedMeshVertices")
        val textureFraction = optionalFraction(legacy, "photographicallyCoveredFaceFraction")
        val conditions = linkedSetOf(
            EvidenceCondition.SCALE_UNKNOWN,
            EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN,
            EvidenceCondition.VISIBILITY_EVIDENCE_UNKNOWN,
            EvidenceCondition.TOPOLOGY_EVIDENCE_UNKNOWN,
            EvidenceCondition.CALIBRATION_ASSUMPTION_UNVERIFIED,
        )
        if (meshTriangles >= 100 && meshVertices >= 30 && textureFraction == null) {
            conditions += EvidenceCondition.TEXTURE_UNAVAILABLE
        }
        val recommendations = readCanonicalRecommendations(legacy.optJSONArray("recommendations"))
            // Schema 1 has no topology counters; supplied copy cannot establish a measured defect.
            .filterNot { it.code == "SURFACE_TOPOLOGY_DEFECTS" }.toMutableList()
        listOf(EvidenceCondition.TEXTURE_UNAVAILABLE, EvidenceCondition.SCALE_UNKNOWN,
            EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN)
            .filter { it in conditions }
            .mapNotNull(::conditionFinding)
            .forEach { if (recommendations.none { current -> current.code == it.code }) recommendations += it }

        return JSONObject()
            .put("schema", DIAGNOSTIC_SCHEMA_VERSION)
            .put("reportType", REPORT_TYPE)
            .put("measurementBasis", MEASUREMENT_BASIS)
            .put("identity", identityToJson(DiagnosticReportIdentity(
                null, null, null, null, null, null, "UNBOUND",
            )))
            .put("status", status)
            .put("importedPhotoCount", imported)
            .put("registeredCameraCount", registered)
            .put("registeredPhotoFraction", requiredFraction(legacy, "registeredPhotoFraction"))
            .put("sparsePointCount", requiredCount(legacy, "sparsePointCount"))
            .putNullable("medianParallaxDegrees", optionalNonnegativeDouble(legacy, "medianParallaxDegrees"))
            .putNullable("medianReprojectionNormalized", optionalNonnegativeDouble(legacy, "medianReprojectionNormalized"))
            .put("sampledDepthPixels", requiredCount(legacy, "sampledDepthPixels"))
            .put("reciprocalDepthPixels", requiredCount(legacy, "reciprocalDepthPixels"))
            .putNullable("reciprocalDepthFraction", optionalFraction(legacy, "reciprocalDepthFraction"))
            .put("observedMeshTriangles", meshTriangles)
            .put("observedMeshVertices", meshVertices)
            .putNullable("photographicallyCoveredFaceFraction", textureFraction)
            .put("evidence", evidenceToJson(
                VisibilityEvidence(null, null),
                TopologyEvidence(
                    if (meshTriangles > 0) TopologyKind.OBSERVED_OPEN_SURFACE else TopologyKind.UNKNOWN,
                ),
                UnitEvidence(ScaleKind.UNKNOWN),
                CalibrationEvidence(CalibrationKind.UNKNOWN),
                conditions,
            ))
            .put("outcomes", outcomesToJson(conditions, emptyList()))
            .put("stageFailures", JSONArray())
            .put("events", JSONArray())
            .put("progressEvents", JSONArray())
            .put("recommendations", JSONArray().apply {
                recommendations.forEach { put(findingToJson(it)) }
            })
            .put("migratedFromSchema", 1)
    }

    private fun sanitizeSchemaTwo(input: JSONObject): JSONObject {
        require(input.optString("reportType") == REPORT_TYPE) { "Unexpected diagnostic report type." }
        val status = input.optString("status")
        require(status in STATUS_CODES) { "Unknown diagnostic status." }
        val imported = requiredCount(input, "importedPhotoCount")
        require(imported >= 1) { "A scan diagnostic report must describe at least one input photo." }
        val registered = requiredCount(input, "registeredCameraCount")
        require(registered <= imported)
        val registeredFraction = requiredFraction(input, "registeredPhotoFraction")
        require(kotlin.math.abs(registeredFraction - registered.toDouble() / imported) <= 1e-9) {
            "Registered fraction does not match its counts."
        }
        val sampled = requiredCount(input, "sampledDepthPixels")
        val reciprocal = requiredCount(input, "reciprocalDepthPixels")
        require(reciprocal <= sampled)
        val reciprocalFraction = optionalFraction(input, "reciprocalDepthFraction")
        val expectedReciprocal = sampled.takeIf { it > 0 }?.let { reciprocal.toDouble() / it }
        require(reciprocalFraction == null && expectedReciprocal == null ||
            reciprocalFraction != null && expectedReciprocal != null &&
            kotlin.math.abs(reciprocalFraction - expectedReciprocal) <= 1e-9) {
            "Reciprocal fraction does not match its counts."
        }
        val meshTriangles = requiredCount(input, "observedMeshTriangles")
        val meshVertices = requiredCount(input, "observedMeshVertices")
        val textureFraction = optionalFraction(input, "photographicallyCoveredFaceFraction")

        val identity = parseIdentity(input.getJSONObject("identity"))
        val evidenceObject = input.getJSONObject("evidence")
        val visibility = parseVisibility(evidenceObject.getJSONObject("visibility"))
        val topology = parseTopology(evidenceObject.getJSONObject("topology"))
        val units = parseUnits(evidenceObject.getJSONObject("units"))
        val calibration = parseCalibration(evidenceObject.getJSONObject("calibration"))
        val failures = parseFailures(input.optJSONArray("stageFailures"))
        val conditions = canonicalConditions(
            parseConditions(evidenceObject.getJSONArray("conditions")),
            visibility,
            topology,
            units,
            calibration,
            failures,
            meshTriangles,
            meshVertices,
            textureFraction,
        )
        val recommendations = canonicalRecommendations(
            readCanonicalRecommendations(input.optJSONArray("recommendations")),
            conditions,
            topology,
            meshTriangles > 0 && meshVertices > 0,
        )
        val events = parseEvents(input.optJSONArray("events"), identity)
        val progress = parseProgressEvents(input.optJSONArray("progressEvents"), identity)
        require(events.size + progress.size <= MAX_DIAGNOSTIC_EVENTS) { "Diagnostic history is oversized." }
        require((events.map { it.sequence } + progress.map { it.sequence }).distinct().size ==
            events.size + progress.size) { "Diagnostic history contains duplicate sequences." }

        return JSONObject()
            .put("schema", DIAGNOSTIC_SCHEMA_VERSION)
            .put("reportType", REPORT_TYPE)
            .put("measurementBasis", MEASUREMENT_BASIS)
            .put("identity", identityToJson(identity))
            .put("status", status)
            .put("importedPhotoCount", imported)
            .put("registeredCameraCount", registered)
            .put("registeredPhotoFraction", registeredFraction)
            .put("sparsePointCount", requiredCount(input, "sparsePointCount"))
            .putNullable("medianParallaxDegrees", optionalNonnegativeDouble(input, "medianParallaxDegrees"))
            .putNullable("medianReprojectionNormalized", optionalNonnegativeDouble(input, "medianReprojectionNormalized"))
            .put("sampledDepthPixels", sampled)
            .put("reciprocalDepthPixels", reciprocal)
            .putNullable("reciprocalDepthFraction", reciprocalFraction)
            .put("observedMeshTriangles", meshTriangles)
            .put("observedMeshVertices", meshVertices)
            .putNullable("photographicallyCoveredFaceFraction", textureFraction)
            .put("evidence", evidenceToJson(visibility, topology, units, calibration, conditions))
            .put("outcomes", outcomesToJson(conditions, failures))
            .put("stageFailures", JSONArray().apply { failures.forEach { put(failureToJson(it)) } })
            .put("events", JSONArray().apply { events.forEach { put(eventToJson(it)) } })
            .put("progressEvents", JSONArray().apply { progress.forEach { put(progressToJson(it)) } })
            .put("recommendations", JSONArray().apply {
                recommendations.forEach { put(findingToJson(it)) }
            })
    }

    private fun canonicalConditions(
        supplied: Set<EvidenceCondition>,
        visibility: VisibilityEvidence,
        topology: TopologyEvidence,
        units: UnitEvidence,
        calibration: CalibrationEvidence,
        failures: List<ScanFailure>,
        meshTriangles: Int,
        meshVertices: Int,
        textureFraction: Double?,
    ): Set<EvidenceCondition> {
        val result = supplied.toMutableSet()
        fun set(condition: EvidenceCondition, present: Boolean) {
            if (present) result += condition else result -= condition
        }
        set(EvidenceCondition.SPARSE_FALLBACK_RETAINED,
            failures.any { it.code == ScanFailureCode.SPARSE_FALLBACK_RETAINED })
        set(EvidenceCondition.TEXTURE_UNAVAILABLE,
            meshTriangles >= 100 && meshVertices >= 30 &&
                (textureFraction == null || failures.any { it.code == ScanFailureCode.TEXTURE_UNAVAILABLE }))
        set(EvidenceCondition.SCALE_UNKNOWN,
            units.scaleKind == ScaleKind.UNKNOWN || units.scaleKind == ScaleKind.ARBITRARY)
        result += EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN
        set(EvidenceCondition.VISIBILITY_EVIDENCE_UNKNOWN,
            visibility.evaluatedFaces == null || visibility.supportedFaces == null)
        set(EvidenceCondition.TOPOLOGY_EVIDENCE_UNKNOWN, topology.kind == TopologyKind.UNKNOWN || topology.topologyIncomplete != false)
        set(EvidenceCondition.CALIBRATION_ASSUMPTION_UNVERIFIED,
            calibration.kind == CalibrationKind.UNKNOWN ||
                calibration.kind == CalibrationKind.FIXED_INTRINSICS_NO_PROFILE)
        return result
    }

    private fun canonicalRecommendations(
        supplied: List<ScanQualityFinding>,
        conditions: Set<EvidenceCondition>,
        topology: TopologyEvidence,
        hasSurface: Boolean,
    ): List<ScanQualityFinding> {
        val result = LinkedHashMap<String, ScanQualityFinding>()
        // Re-derive this finding from measured evidence, including historical reports.
        // A stale supplied warning must not survive corrected or unavailable counters.
        if (hasSurface && (topology.nonManifoldEdges?.let { it > 0 } == true ||
            topology.selfIntersections?.let { it > 0 } == true)) {
            result["SURFACE_TOPOLOGY_DEFECTS"] = ScanQualityRecommendationCatalog.require("SURFACE_TOPOLOGY_DEFECTS")
        }
        supplied.filterNot { it.code == "SURFACE_TOPOLOGY_DEFECTS" }.forEach { result[it.code] = it }
        listOf(
            EvidenceCondition.SPARSE_FALLBACK_RETAINED,
            EvidenceCondition.TEXTURE_UNAVAILABLE,
            EvidenceCondition.SCALE_UNKNOWN,
            EvidenceCondition.UNOBSERVED_SURFACES_UNKNOWN,
        ).filter { it in conditions }.mapNotNull(::conditionFinding).forEach {
            result[it.code] = it
        }
        return result.values.toList()
    }

    private fun parseIdentity(obj: JSONObject): DiagnosticReportIdentity = DiagnosticReportIdentity(
        jobId = nullableString(obj, "jobId"),
        attempt = optionalInt(obj, "attempt"),
        sourceRevision = optionalLong(obj, "sourceRevision"),
        artifactId = nullableString(obj, "artifactId"),
        engineVersion = nullableString(obj, "engineVersion"),
        settingsFingerprint = nullableString(obj, "settingsFingerprint"),
        source = obj.getString("source"),
    )

    private fun parseVisibility(obj: JSONObject): VisibilityEvidence {
        val evidence = VisibilityEvidence(optionalInt(obj, "evaluatedFaces"), optionalInt(obj, "supportedFaces"))
        val reported = optionalFraction(obj, "supportedFraction")
        val measured = evidence.supportedFractionOrNull()
        require(reported == null || measured != null && kotlin.math.abs(reported - measured) <= 1e-9) {
            "Visibility fraction does not match its counts."
        }
        return evidence
    }

    private fun parseTopology(obj: JSONObject): TopologyEvidence {
        val reportedKind = TopologyKind.fromWire(obj.getString("kind"))
            ?: throw IllegalArgumentException("Unknown topology kind.")
        val incomplete = topologyIncomplete(obj)
        val boundaryEdges = topologyCount(obj, "boundaryEdges")
        val nonManifoldEdges = topologyCount(obj, "nonManifoldEdges")
        val selfIntersections = topologyCount(obj, "selfIntersections")
        if (reportedKind == TopologyKind.VERIFIED_WATERTIGHT) {
            require(boundaryEdges == 0 && nonManifoldEdges == 0 &&
                (selfIntersections == null || selfIntersections == 0)) {
                "Reported watertight evidence contradicts its defect counts."
            }
        }
        // Old reports lack completeness evidence. Keep them readable, not newly verified.
        val kind = if (reportedKind == TopologyKind.VERIFIED_WATERTIGHT && incomplete == null) {
            TopologyKind.OBSERVED_OPEN_SURFACE
        } else reportedKind
        return TopologyEvidence(
            kind = kind,
            boundaryEdges = boundaryEdges,
            nonManifoldEdges = nonManifoldEdges,
            connectedComponents = topologyCount(obj, "connectedComponents"),
            selfIntersections = selfIntersections,
            topologyIncomplete = incomplete,
        )
    }

    private fun parseUnits(obj: JSONObject): UnitEvidence = UnitEvidence(
        scaleKind = ScaleKind.fromWire(obj.getString("scaleKind"))
            ?: throw IllegalArgumentException("Unknown scale kind."),
        physicalUnit = nullableString(obj, "physicalUnit"),
        referenceValue = optionalPositiveDouble(obj, "referenceValue"),
    )

    private fun parseCalibration(obj: JSONObject): CalibrationEvidence = CalibrationEvidence(
        kind = CalibrationKind.fromWire(obj.getString("kind"))
            ?: throw IllegalArgumentException("Unknown calibration kind."),
        profileApplied = optionalBoolean(obj, "profileApplied"),
        reprojectionRmsPixels = optionalNonnegativeDouble(obj, "reprojectionRmsPixels"),
    )

    private fun parseConditions(array: JSONArray): Set<EvidenceCondition> {
        require(array.length() <= EvidenceCondition.entries.size)
        val result = linkedSetOf<EvidenceCondition>()
        for (index in 0 until array.length()) {
            val condition = EvidenceCondition.fromWire(array.getString(index))
                ?: throw IllegalArgumentException("Unknown evidence condition.")
            require(result.add(condition)) { "Duplicate evidence condition." }
        }
        return result
    }

    private fun parseFailures(array: JSONArray?): List<ScanFailure> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_DIAGNOSTIC_FAILURES)
        return (0 until array.length()).map { parseFailure(array.getJSONObject(it)) }
    }

    private fun parseFailure(obj: JSONObject): ScanFailure {
        val contextObject = obj.optJSONObject("context") ?: JSONObject()
        val contextValues = LinkedHashMap<DiagnosticContextKey, DiagnosticValue>()
        val keys = contextObject.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = DiagnosticContextKey.fromWire(rawKey) ?: continue // privacy redaction
            val value = runCatching { diagnosticValue(contextObject.get(rawKey)) }.getOrNull() ?: continue
            contextValues[key] = value
        }
        return ScanFailure(
            code = ScanFailureCode.fromWire(obj.getString("code"))
                ?: throw IllegalArgumentException("Unknown failure code."),
            stage = strictStage(obj.getString("stage")),
            outcome = StageOutcome.fromWire(obj.getString("outcome"))
                ?: throw IllegalArgumentException("Unknown failure outcome."),
            action = RecoveryAction.fromWire(obj.getString("actionCode"))
                ?: throw IllegalArgumentException("Unknown recovery action."),
            messageKey = obj.getString("messageKey"),
            context = DiagnosticContext.from(contextValues),
        )
    }

    private fun diagnosticValue(value: Any): DiagnosticValue = when (value) {
        is Boolean -> DiagnosticValue.Flag(value)
        is Byte, is Short, is Int, is Long -> DiagnosticValue.Count((value as Number).toLong())
        is Number -> DiagnosticValue.Decimal(value.toDouble())
        is String -> DiagnosticValue.Text(value)
        else -> throw IllegalArgumentException("Unsupported diagnostic context value.")
    }

    private fun parseProgressEvents(array: JSONArray?, identity: DiagnosticReportIdentity): List<StageProgress> {
        if (array == null || array.length() == 0) return emptyList()
        require(array.length() <= MAX_DIAGNOSTIC_EVENTS)
        val expected = boundScope(identity)
        val ledger = StageProgressLedger(expected)
        val progress = ArrayList<StageProgress>(array.length())
        var previous = -1L
        for (index in 0 until array.length()) {
            val obj = array.getJSONObject(index)
            val scope = progressScope(obj)
            require(scope == expected) { "Cross-job measured progress rejected." }
            val stage = strictStage(obj.getString("stage"))
            val measured = parseProgressBody(obj, scope, stage)
            require(measured.sequence > previous) { "Measured progress was reordered." }
            previous = measured.sequence
            ledger.record(measured)
            progress += measured
        }
        return progress
    }

    private fun boundScope(identity: DiagnosticReportIdentity): DiagnosticScope {
        require(identity.jobId != null && identity.attempt != null && identity.sourceRevision != null) {
            "Unbound reports cannot contain job events."
        }
        return DiagnosticScope(identity.jobId, identity.attempt, identity.sourceRevision)
    }

    private fun progressScope(obj: JSONObject): DiagnosticScope {
        val attempt = exactProgressCount(obj, "attempt")
        require(attempt in 1..Int.MAX_VALUE.toLong()) { "Invalid diagnostic attempt." }
        return DiagnosticScope(obj.getString("jobId"), attempt.toInt(), exactProgressCount(obj, "sourceRevision"))
    }

    private fun exactProgressCount(obj: JSONObject, key: String): Long {
        val raw = obj.get(key)
        require(raw is Int || raw is Long) { "Diagnostic $key must be an exact integer." }
        return (raw as Number).toLong().also { require(it >= 0) { "Negative diagnostic $key." } }
    }

    private fun parseProgressBody(obj: JSONObject, scope: DiagnosticScope, stage: ScanStage): StageProgress {
        val reuse = if (obj.has("reusedCheckpoint")) obj.get("reusedCheckpoint").also {
            require(it is Boolean) { "Checkpoint reuse must be an explicit boolean." }
        } as Boolean else false
        return StageProgress(scope, stage, exactProgressCount(obj, "completed"),
            if (!obj.has("total") || obj.isNull("total")) null else exactProgressCount(obj, "total"),
            ProgressUnit.fromWire(obj.getString("unit")) ?: throw IllegalArgumentException("Unknown progress unit."),
            exactProgressCount(obj, "sequence"), reuse)
    }

    private fun parseEvents(array: JSONArray?, identity: DiagnosticReportIdentity): List<DiagnosticEvent> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_DIAGNOSTIC_EVENTS)
        if (array.length() == 0) return emptyList()
        val expectedScope = boundScope(identity)
        val progressLedger = StageProgressLedger(expectedScope)
        val eventLog = BoundedDiagnosticEventLog()
        var previous = -1L
        for (index in 0 until array.length()) {
            val obj = array.getJSONObject(index)
            val scope = progressScope(obj)
            require(scope == expectedScope) { "Cross-job diagnostic event rejected." }
            val stage = strictStage(obj.getString("stage"))
            val outcome = StageOutcome.fromWire(obj.getString("outcome"))
                ?: throw IllegalArgumentException("Unknown event outcome.")
            val sequence = exactProgressCount(obj, "sequence")
            require(sequence > previous) { "Diagnostic events were reordered." }
            previous = sequence
            val progress = obj.optJSONObject("progress")?.let { body ->
                parseProgressBody(body, scope, stage).also(progressLedger::record)
            }
            val failure = obj.optJSONObject("failure")?.let(::parseFailure)
            eventLog.append(DiagnosticEvent(scope, stage, outcome, sequence, progress, failure))
        }
        return eventLog.snapshot()
    }

    private fun strictStage(value: String): ScanStage = ScanStage.entries.firstOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unknown scan stage.")

    private fun readCanonicalRecommendations(array: JSONArray?): List<ScanQualityFinding> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_DIAGNOSTIC_FAILURES)
        val result = LinkedHashMap<String, ScanQualityFinding>()
        for (index in 0 until array.length()) {
            val code = array.getJSONObject(index).getString("code")
            val canonical = ScanQualityRecommendationCatalog.find(code) ?: continue // redact unknown copy
            result[code] = canonical
        }
        return result.values.toList()
    }

    private fun validateExpectation(identity: JSONObject, expectation: DiagnosticReportExpectation) {
        expectation.sourceRevision?.let {
            require(!identity.isNull("sourceRevision") && identity.getLong("sourceRevision") == it) {
                "Diagnostic source revision mismatch."
            }
        }
        expectation.artifactId?.let {
            require(!identity.isNull("artifactId") && identity.getString("artifactId") == it) {
                "Diagnostic artifact identity mismatch."
            }
        }
        expectation.jobId?.let {
            require(!identity.isNull("jobId") && identity.getString("jobId") == it) {
                "Diagnostic job identity mismatch."
            }
        }
    }

    private fun requiredCount(obj: JSONObject, key: String): Int = obj.getInt(key).also {
        require(it >= 0) { "$key must be nonnegative." }
    }

    private fun requiredFraction(obj: JSONObject, key: String): Double = obj.getDouble(key).also {
        require(it.isFinite() && it in 0.0..1.0) { "$key must be a finite fraction." }
    }

    private fun optionalFraction(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else requiredFraction(obj, key)

    private fun optionalNonnegativeDouble(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getDouble(key).also {
            require(it.isFinite() && it >= 0) { "$key must be finite and nonnegative." }
        }

    private fun optionalPositiveDouble(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getDouble(key).also {
            require(it.isFinite() && it > 0) { "$key must be finite and positive." }
        }

    private fun optionalInt(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getInt(key).also {
            require(it >= 0) { "$key must be nonnegative." }
        }

    private fun optionalLong(obj: JSONObject, key: String): Long? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getLong(key).also {
            require(it >= 0) { "$key must be nonnegative." }
        }

    private fun optionalBoolean(obj: JSONObject, key: String): Boolean? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getBoolean(key)
}
