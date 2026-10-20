package com.realityforge.scanner.agents.a011

import com.realityforge.scanner.capture.metadata.CaptureMetadata
import com.realityforge.scanner.capture.metadata.CaptureSource
import com.realityforge.scanner.data.importing.CaptureSettingsFields
import com.realityforge.scanner.data.importing.ImportSource
import org.junit.Assert.*
import org.junit.Test

class CaptureSettingsFieldsTest {
    @Test fun onlyBoundedActualCameraNumbersAndAcknowledgmentsAreAccepted() {
        val fields = mapOf("sensorTimestampNanos" to "99999", "exposureTimeNanos" to "1234",
            "sensitivityIso" to "120", "aperture" to "1.8", "aeLocked" to "true",
            "awbLocked" to "false", "confirmationSource" to "CAMERA2_CAPTURE_RESULT")
        CaptureSettingsFields.validate(fields)
        val source = ImportSource("content://owned-capture", CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE), fields)
        assertEquals(fields, source.captureSettings)
        assertThrows(IllegalArgumentException::class.java) { ImportSource("content://provider", captureSettings = fields) }
        listOf(mapOf("filename" to "../../../original"), mapOf("aperture" to "NaN"),
            mapOf("aeLocked" to "requested"), mapOf("sensorTimestampNanos" to "-1"),
            mapOf("confirmationSource" to "CAMERAX_CONTROL_ACKNOWLEDGEMENT")).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) { CaptureSettingsFields.validate(malformed) }
        }
    }
}
