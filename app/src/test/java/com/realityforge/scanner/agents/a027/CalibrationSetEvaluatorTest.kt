package com.realityforge.scanner.agents.a027

import com.realityforge.scanner.capture.calibration.CalibrationEvidenceSource
import com.realityforge.scanner.capture.calibration.CalibrationFailureCode
import com.realityforge.scanner.capture.calibration.CalibrationOpticalMetadata
import com.realityforge.scanner.capture.calibration.CalibrationSetEvaluator
import com.realityforge.scanner.capture.calibration.CalibrationViewMeasurement
import com.realityforge.scanner.capture.calibration.CalibrationViewRejection
import com.realityforge.scanner.capture.calibration.CalibrationViewRejectionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSetEvaluatorTest {
    @Test
    fun validSetRetainsMeasuredCoverageAndPhysicalMetadata() {
        val result = CalibrationSetEvaluator.evaluate((0 until 12).map(::validView))

        assertNull(result.failure)
        assertEquals((0 until 12).toList(), result.acceptedIndices)
        assertTrue(requireNotNull(result.coverage.xRangeFraction) >= CalibrationSetEvaluator.MIN_X_RANGE)
        assertTrue(requireNotNull(result.coverage.yRangeFraction) >= CalibrationSetEvaluator.MIN_Y_RANGE)
        assertTrue(requireNotNull(result.coverage.boardAreaRatio) >= CalibrationSetEvaluator.MIN_AREA_RATIO)
        assertTrue(result.coverage.metadataComplete)
    }

    @Test
    fun lowDiversityIsNotMisreportedAsNumericConvergence() {
        val inputs = (0 until 10).map { index ->
            validView(index).copy(
                boardCenterXFraction = 0.50 + index * 0.001,
                boardCenterYFraction = 0.50,
                boardAreaFraction = 0.10,
            )
        }

        val result = CalibrationSetEvaluator.evaluate(inputs)

        assertEquals(CalibrationFailureCode.LOW_VIEW_DIVERSITY, result.failure?.code)
        assertEquals(10, result.coverage.acceptedViews)
        assertTrue(result.views.all { it.accepted })
    }

    @Test
    fun mixedCropAndLensViewsAreRejectedWithConcretePerViewEvidence() {
        val compatible = (0 until 12).map(::validView)
        val incompatible = (12 until 15).map { index ->
            validView(index).copy(
                uprightWidth = 1200,
                uprightHeight = 1600,
                optical = validOptical.copy(
                    lensId = "rear-ultrawide",
                    focal35mm = 14.0,
                    cropFingerprint = "crop-ultrawide-0001",
                ),
            )
        }

        val result = CalibrationSetEvaluator.evaluate(compatible + incompatible)

        assertNull(result.failure)
        assertEquals((0 until 12).toList(), result.acceptedIndices)
        val rejected = result.views.filterNot { it.accepted }
        assertEquals(3, rejected.size)
        assertTrue(rejected.all {
            it.rejection?.code == CalibrationViewRejectionCode.UPRIGHT_DIMENSIONS_MISMATCH
        })
    }

    @Test
    fun malformedAndUndetectedViewsRemainVisibleInEvidence() {
        val detected = (0 until 9).map(::validView)
        val missing = validView(9).copy(
            detectedCornerCount = 0,
            boardCenterXFraction = null,
            boardCenterYFraction = null,
            boardAreaFraction = null,
            preflightRejection = CalibrationViewRejection(
                CalibrationViewRejectionCode.BOARD_NOT_FOUND,
                "Board not found in synthetic fixture.",
            ),
        )

        val result = CalibrationSetEvaluator.evaluate(detected + missing)

        assertEquals(CalibrationFailureCode.INSUFFICIENT_ACCEPTED_VIEWS, result.failure?.code)
        assertEquals(CalibrationViewRejectionCode.BOARD_NOT_FOUND, result.views.last().rejection?.code)
        assertEquals(9, result.coverage.acceptedViews)
    }

    @Test
    fun evaluationIsIndependentOfInputListOrder() {
        val inputs = (0 until 14).map { index ->
            if (index < 12) validView(index)
            else validView(index).copy(optical = validOptical.copy(focal35mm = 50.0))
        }
        val forward = CalibrationSetEvaluator.evaluate(inputs)
        val reverse = CalibrationSetEvaluator.evaluate(inputs.reversed())

        assertEquals(forward.acceptedIndices, reverse.acceptedIndices)
        assertEquals(
            forward.views.map { it.index to it.rejection?.code },
            reverse.views.map { it.index to it.rejection?.code },
        )
    }

    private fun validView(index: Int): CalibrationViewMeasurement {
        val x = 0.18 + (index % 4) * 0.20
        val y = 0.22 + (index / 4) * 0.23
        val area = 0.035 + index * 0.012
        return CalibrationViewMeasurement(
            index = index,
            sourceId = "view-$index",
            sourceSha256 = "%064x".format(index + 1),
            uprightWidth = 1600,
            uprightHeight = 1200,
            detectedCornerCount = CalibrationSetEvaluator.EXPECTED_CORNERS,
            boardCenterXFraction = x,
            boardCenterYFraction = y,
            boardAreaFraction = area,
            optical = validOptical,
        )
    }

    private companion object {
        val validOptical = CalibrationOpticalMetadata(
            cameraId = "camera-back-0",
            lensId = "rear-wide",
            zoomRatio = 1.0,
            cropFingerprint = "crop-wide-00000001",
            focal35mm = 26.0,
            evidenceSource = CalibrationEvidenceSource.PHYSICAL_DEVICE,
        )
    }
}
