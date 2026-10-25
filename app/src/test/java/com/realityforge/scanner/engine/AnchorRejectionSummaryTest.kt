package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.diagnostics.*
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import com.realityforge.scanner.engine.nativebridge.NativeBoundaryFailure
import org.junit.Assert.*
import org.junit.Test

class AnchorRejectionSummaryTest {
    private fun failure(vararg codes: String?) = AnchorRejectionSummary().also { summary ->
        codes.forEach(summary::record)
    }.failure()

    @Test fun uniformlyWeakOrAmbiguousGeometryRetainsActionableReason() {
        for (codes in listOf(arrayOf("WEAK_BASELINE"), arrayOf("PLANAR_AMBIGUITY"),
            arrayOf("ROTATION_ONLY"), arrayOf("WEAK_BASELINE", "PLANAR_AMBIGUITY", "ROTATION_ONLY"))) {
            val result = failure(*codes)
            assertEquals(ScanFailureCode.CAMERA_REGISTRATION_FAILED, result.code)
            assertEquals(RecoveryAction.RECAPTURE_SIDEWAYS, result.action)
            assertEquals("scan.failure.weak_or_ambiguous_geometry", result.messageKey)
            assertEquals(ScanStage.MATCHING, result.stage)
            assertEquals(StageOutcome.FAILED, result.outcome)
            assertTrue(result.context.values.isEmpty())
        }
    }

    @Test fun mixedUnknownAndLowMatchEvidenceMustNotInventAGeometricCause() {
        val unknowns = arrayOf(null, "", "INSUFFICIENT_CONSENSUS", "COLLINEAR_FEATURES", "INVALID_INPUT",
            "NATIVE_FAILURE", "UNKNOWN_NATIVE_FAILURE", "NEW_CODE", "weak_baseline", "/private/photo.jpg")
        for (unknown in unknowns) for (codes in listOf(arrayOf(unknown), arrayOf("WEAK_BASELINE", unknown),
            arrayOf(unknown, "PLANAR_AMBIGUITY"))) {
            val result = failure(*codes)
            assertEquals(ScanFailureCode.INSUFFICIENT_OVERLAP, result.code)
            assertEquals(RecoveryAction.RECAPTURE_OVERLAP, result.action)
            assertEquals("scan.failure.insufficient_overlap", result.messageKey)
            assertTrue(result.context.values.isEmpty())
        }
    }

    @Test fun nativeCancellationAndResourceErrorsStillEscapeBeforeAggregation() {
        for ((code, expected) in listOf("CANCELLED" to NativeBoundaryFailure.CANCELLED,
            "RESOURCE_EXHAUSTED" to NativeBoundaryFailure.RESOURCE_EXHAUSTED)) {
            val summary = AnchorRejectionSummary()
            summary.record("WEAK_BASELINE")
            try {
                val result = NativeResultJson.decode("""{"error":"private message","errorCode":"$code"}""")
                summary.record(result.optString("errorCode"))
                fail("Native termination must escape before a no-anchor failure is chosen")
            } catch (failure: Exception) {
                assertEquals(expected, NativeBoundaryFailure.from(failure))
                assertFalse(failure.message.orEmpty().contains("private message"))
            }
        }
    }

    @Test fun nativeFaultsDoNotBecomeSpecificCaptureAdvice() {
        for (code in listOf("NATIVE_FAILURE", "UNKNOWN_NATIVE_FAILURE", "INVALID_INPUT")) {
            val result = NativeResultJson.decode("""{"error":"file:/private/photo.jpg","errorCode":"$code"}""")
            val mapped = failure("WEAK_BASELINE", result.optString("errorCode"))
            assertEquals(ScanFailureCode.INSUFFICIENT_OVERLAP, mapped.code)
            assertTrue(mapped.context.values.isEmpty())
            assertFalse(mapped.messageKey.contains("private"))
        }
    }

    @Test fun noAttemptIsNotEvidenceOfWeakGeometry() {
        assertEquals(ScanFailureCode.INSUFFICIENT_OVERLAP, failure().code)
    }
}
