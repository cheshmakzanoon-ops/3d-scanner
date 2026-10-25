package com.realityforge.scanner.capture.metadata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ExifFocalPlaneEstimateTest {
    private fun estimate(f: Double? = 50.0, x: Double? = 2540.0, y: Double? = 2540.0,
        unit: Int? = 2, ew: Int? = 3600, eh: Int? = 2400, w: Int = 3600, h: Int = 2400,
        orientation: Int? = null, zoom: Double? = null) =
        ExifFocalPlaneEstimate.equivalent35mm(f, x, y, unit, ew, eh, w, h, orientation, zoom)

    @Test fun inchAndCentimeterUnitsAgreeWithDiagonalProjection() {
        assertEquals(50.0, estimate()!!, 1e-10)
        assertEquals(50.0, estimate(x = 1000.0, y = 1000.0, unit = 3)!!, 1e-10)
        val focalPixels = estimate()!! * hypot(3600.0, 2400.0) / hypot(36.0, 24.0)
        assertEquals(50.0 * 2540.0 / 25.4, focalPixels, 1e-9)
    }
    @Test fun allEightOrientationsPreserveDiagonalAndEncodedAxes() {
        for (orientation in 1..8) assertEquals(50.0, estimate(orientation = orientation)!!, 1e-10)
        assertEquals(50.0, estimate(ew=2400, eh=3600, w=2400, h=3600)!!, 1e-10)
        assertNull(estimate(ew=2400, eh=3600)) // Do not rotate EXIF dimensions twice.
        for (orientation in listOf(0, 9, -1)) assertNull(estimate(orientation=orientation))
    }
    @Test fun missingUnknownOrUnsupportedUnitsRemainUnknown() {
        for (unit in listOf(null, 0, 1, 4, 5, -1)) assertNull(estimate(unit=unit))
    }
    @Test fun dimensionsMustMatchActualEncodedImage() {
        assertNull(estimate(ew=null)); assertNull(estimate(eh=null))
        assertNull(estimate(ew=1800, eh=1200)); assertNull(estimate(w=0)); assertNull(estimate(h=-1))
        assertNull(estimate(ew=3601)); assertNull(estimate(eh=2401))
    }
    @Test fun invalidNumericAndExtremeValuesAreDeclined() {
        for (value in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(estimate(f=value)); assertNull(estimate(x=value)); assertNull(estimate(y=value))
        }
        assertNull(estimate(f=Double.MAX_VALUE)); assertNull(estimate(f=Double.MIN_VALUE))
        assertNull(estimate(x=Double.MIN_VALUE, y=Double.MIN_VALUE))
        assertNull(estimate(x=Double.MAX_VALUE, y=Double.MAX_VALUE))
        assertNull(estimate(f=9.0)); assertNull(estimate(f=601.0))
    }
    @Test fun anisotropyIsDeclinedNotSilentlyAveraged() {
        assertNull(estimate(y=2600.0)); assertNull(estimate(x=2600.0))
        assertNotNull(estimate(y=2540.0*(1+1e-8))) // Numeric rational rounding only.
        assertNull(estimate(y=2540.0*(1+1e-5)))
        // Real licensed Canon fixture: preserves unknown instead of claiming an exact86.5mm prior.
        assertNull(estimate(f=55.0,x=4720.441988950276,y=4786.55462184874,ew=4272,eh=2848,w=4272,h=2848))
    }
    @Test fun digitalCropIsNotGuessedOrDoubleApplied() {
        assertNotNull(estimate(zoom=1.0)); assertNotNull(estimate(zoom=0.0))
        for (zoom in listOf(-1.0, 0.5, 2.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(estimate(zoom=zoom))
    }
    @Test fun derivedProvenanceDoesNotClaimPhysicalSensorOrCalibration() {
        val field = FieldEvidence.derived(estimate()!!, setOf(EvidenceOrigin.EXIF, EvidenceOrigin.ENCODED_IMAGE_METADATA))
        assertEquals(EvidenceStatus.DERIVED, field.status)
        assertFalse(EvidenceOrigin.DERIVED_SENSOR_GEOMETRY in field.checkedOrigins)
        val metadata = CaptureMetadata.empty(CaptureSource.IMPORTED_EXIF).copy(focal35mmEquivalent = field)
        assertEquals(metadata, CaptureMetadataCodec.decode(CaptureMetadataCodec.encode(metadata)))
        assertEquals(EvidenceStatus.MISSING, metadata.sensorSizeMm.status)
    }
}
