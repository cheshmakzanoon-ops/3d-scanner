package com.realityforge.scanner.agents.a010

import com.realityforge.scanner.engine.ScanQualityReport
import com.realityforge.scanner.engine.diagnostics.*
import com.realityforge.scanner.engine.pipeline.ProductionEngineCompatibility
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual report serialization/validation with labeled synthetic measurement DTOs, without mocks. */
class RuntimeDiagnosticReportTest {
    private val scope = DiagnosticScope("job-report-a010", 2, 7)

    @Test fun productionCompatibilityDomainsRoundTripWithoutWeakeningIdentityValidation() {
        val compatibility = ProductionEngineCompatibility.VALUE
        val report = ScanQualityReport.build(source().put("engineVersion", compatibility), 3,
            recorded().snapshot())
        val normalized = ScanQualityReport.importValidated(ScanQualityReport.exportValidated(report),
            DiagnosticReportExpectation(7, "artifact-report-a010", scope.jobId))
        assertEquals(compatibility, normalized.getJSONObject("identity").getString("engineVersion"))
        assertThrows(IllegalArgumentException::class.java) { DiagnosticScope("job|another-domain", 1, 7) }
    }

    @Test fun engineCompatibilityRejectsPathsUrisControlsAndEmptyDomains() {
        for (version in listOf("", "v1|", "|v1", "v1||v2", "v1|/data/private", "v1|C:\\private",
            "content:private", "https://example.test/v1", "file:private", "v1|v2\n", "v1| v2",
            "v".repeat(161))) {
            assertThrows("Unexpectedly accepted engine compatibility: $version", IllegalArgumentException::class.java) {
                ScanQualityReport.build(source().put("engineVersion", version), 3)
            }
        }
    }

    @Test fun actualRecordedProgressAndFailureContextsRoundTripWithExactIdentity() {
        val recorder = recorded()
        val report = ScanQualityReport.build(source(), 3, recorder.snapshot())
        val normalized = ScanQualityReport.importValidated(ScanQualityReport.exportValidated(report),
            DiagnosticReportExpectation(7, "artifact-report-a010", scope.jobId))
        val progress = normalized.getJSONArray("progressEvents")
        assertEquals(3, progress.length())
        assertEquals("PHOTOS", progress.getJSONObject(1).getString("unit"))
        assertEquals(3L, progress.getJSONObject(1).getLong("completed"))
        assertTrue(progress.getJSONObject(2).isNull("total"))
        assertEquals("DENSE_DEPTH", progress.getJSONObject(2).getString("stage"))
        for (index in 0 until progress.length()) {
            assertEquals(scope.jobId, progress.getJSONObject(index).getString("jobId"))
            assertEquals(scope.attempt, progress.getJSONObject(index).getInt("attempt"))
            assertEquals(scope.sourceRevision, progress.getJSONObject(index).getLong("sourceRevision"))
        }
        val events = normalized.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("SUCCEEDED", events.getJSONObject(0).getString("outcome"))
        assertEquals("IO_FAILURE", events.getJSONObject(1).getJSONObject("failure").getString("code"))
        assertFalse(normalized.toString().contains("private-photo"))
        assertFalse(normalized.toString().contains("/data/user"))
    }

    @Test fun attachingAnotherJobAttemptOrRevisionIsRejectedBeforeBuildingTheReport() {
        val snapshot = recorded().snapshot()
        listOf("jobId" to "other-job", "jobAttempt" to 3, "revision" to 8).forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) {
                ScanQualityReport.build(source().put(key, value), 3, snapshot)
            }
        }
        val unbound = source().apply { remove("jobId"); remove("jobAttempt") }
        assertThrows(IllegalArgumentException::class.java) { ScanQualityReport.build(unbound, 3, snapshot) }
    }

    @Test fun reorderedChangedCountersAndCrossJobProgressCannotBeImported() {
        val original = ScanQualityReport.build(source(), 3, recorded().snapshot())
        fun rejects(change: (JSONObject) -> Unit) {
            assertThrows(IllegalArgumentException::class.java) {
                ScanQualityReport.validateAndRedact(JSONObject(original.toString()).also(change))
            }
        }
        rejects { value ->
            val events = value.getJSONArray("progressEvents")
            value.put("progressEvents", JSONArray().put(events.getJSONObject(1)).put(events.getJSONObject(0)))
        }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("jobId", "other-job") }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("attempt", 3) }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("sourceRevision", 8) }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("total", 4) }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("unit", "ITEMS") }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("completed", "3") }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("completed", 2.5) }
        rejects { it.getJSONArray("progressEvents").getJSONObject(1).put("reusedCheckpoint", "true") }
        rejects { it.getJSONArray("events").getJSONObject(0).put("sequence", 0) }
        rejects { value -> value.put("progressEvents", JSONArray().apply {
            repeat(65) { put(original.getJSONArray("progressEvents").getJSONObject(0)) }
        }) }
    }

    @Test fun newProgressFieldsAreAllowlistedAndOlderSchemaTwoReportsRemainReadable() {
        val report = ScanQualityReport.build(source(), 3, recorded().snapshot())
        report.getJSONArray("progressEvents").getJSONObject(0).put("privatePath", "/data/user/private.jpg")
        val safe = ScanQualityReport.validateAndRedact(report)
        assertFalse(safe.getJSONArray("progressEvents").getJSONObject(0).has("privatePath"))
        val older = ScanQualityReport.build(source(), 3).apply { remove("progressEvents") }
        assertEquals(0, ScanQualityReport.validateAndRedact(older).getJSONArray("progressEvents").length())
    }

    @Test fun typedPerSceneFailureDetailsWinOverTheCompatibilityFallback() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        for (checkpoint in listOf("camera-a", "camera-b")) {
            val failure = ScanFailure(ScanFailureCode.TEXTURE_UNAVAILABLE, ScanStage.TEXTURE,
                StageOutcome.FAILED, RecoveryAction.RECAPTURE_TEXTURE, "scan.failure.visibility_rejected",
                DiagnosticContext.of(DiagnosticContextKey.CHECKPOINT_ID to DiagnosticValue.Text(checkpoint)))
            recorder.outcome(ScanStage.TEXTURE, failure.outcome, failure)
        }
        val report = ScanQualityReport.build(source().put("textureStatus", "unavailable"), 3, recorder.snapshot())
        val failures = report.getJSONArray("stageFailures")
        assertEquals(2, failures.length())
        assertEquals(setOf("camera-a", "camera-b"), (0 until failures.length()).map {
            failures.getJSONObject(it).getJSONObject("context").getString("checkpointId")
        }.toSet())
        assertEquals(2, ScanQualityReport.validateAndRedact(report).getJSONArray("stageFailures").length())
    }

    @Test fun resourcePauseKeepsUnknownProgressAndDoesNotClaimSuccessfulGeometry() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        recorder.progress(ScanStage.DENSE_DEPTH, 0, null, ProgressUnit.STEPS)
        val paused = OperationalFailureMapper.resourcePaused(ScanStage.DENSE_DEPTH)
        recorder.outcome(paused.stage, paused.outcome, paused)
        val report = ScanQualityReport.validateAndRedact(ScanQualityReport.build(source(), 3, recorder.snapshot()))
        assertEquals("SPARSE_OR_DEPTH_ONLY", report.getString("status"))
        val event = report.getJSONArray("events").getJSONObject(0)
        assertEquals("PAUSED", event.getString("outcome"))
        assertEquals("RESOURCE_PAUSED", event.getJSONObject("failure").getString("code"))
        assertEquals("RESUME_JOB", event.getJSONObject("failure").getString("actionCode"))
        assertTrue(report.getJSONArray("progressEvents").getJSONObject(0).isNull("total"))
    }

    private fun recorded(): RuntimeDiagnosticRecorder = RuntimeDiagnosticRecorder(scope).apply {
        progress(ScanStage.FEATURE_EXTRACTION, 0, 3, ProgressUnit.PHOTOS)
        progress(ScanStage.FEATURE_EXTRACTION, 3, 3, ProgressUnit.PHOTOS)
        outcome(ScanStage.FEATURE_EXTRACTION, StageOutcome.SUCCEEDED)
        progress(ScanStage.DENSE_DEPTH, 0, null, ProgressUnit.STEPS)
        recordFailure(ScanStage.EXPORT, IOException("/data/user/0/private-photo.jpg"))
    }

    private fun source(): JSONObject = JSONObject().put("registeredCameras", 2).put("pointCount", 30)
        .put("jobId", scope.jobId).put("jobAttempt", scope.attempt).put("revision", scope.sourceRevision)
        .put("artifactKey", "artifact-report-a010").put("engineVersion", "synthetic-diagnostic-fixture-v1")
        .put("settingsFingerprint", "a".repeat(64)).put("calibrationProfileApplied", false)
}
