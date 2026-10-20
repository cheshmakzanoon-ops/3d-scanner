package com.realityforge.scanner.engine

import org.junit.Assert.*
import org.junit.Test

class ScanQualityEvidenceTest {
    private fun input()=ScanQualityInputs(8,8,1500,3.0,.002,10000,4000,2600,5000,.92)
    @Test fun observedTexturedModelIsNotCalledComplete() {
        val result=ScanQualityEvidence.assess(input())
        assertEquals("PHOTO_TEXTURED_OPEN_SURFACE",result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(.4,result.reciprocalFraction!!,1e-9)
    }
    @Test fun poorlyRegisteredPhotosGiveActionableGuidance() {
        val result=ScanQualityEvidence.assess(input().copy(registeredCameras=2,
            medianParallaxDegrees=.6,medianReprojectionNormalized=.02))
        assertEquals(setOf("FEW_REGISTERED_CAMERAS","PHOTOS_NOT_REGISTERED",
            "LOW_PARALLAX","HIGH_REPROJECTION_ERROR"),result.findings.map {it.code}.toSet())
    }
    @Test fun weakDepthAndUntexturedMeshAreReportedSeparately() {
        val result=ScanQualityEvidence.assess(input().copy(sampledDepthPixels=1000,
            consistentDepthPixels=5,photographicFaceFraction=.12))
        assertEquals("PARTIAL_OPEN_MESH",result.status)
        assertEquals(listOf("LOW_RECIPROCAL_DEPTH_SUPPORT","LIMITED_PHOTOGRAPHIC_FACE_COVERAGE"),result.findings.map {it.code})
    }
    @Test fun sparseOnlyIsNotPromotedToMesh() {
        val result=ScanQualityEvidence.assess(input().copy(meshVertices=0,meshTriangles=0,
            photographicFaceFraction=null,sampledDepthPixels=0,consistentDepthPixels=0))
        assertEquals("SPARSE_OR_DEPTH_ONLY",result.status)
        assertNull(result.reciprocalFraction)
        assertTrue(result.findings.any { it.code=="DEPTH_UNAVAILABLE" })
    }
    @Test fun badInputsAndImpossibleFractionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java){ScanQualityEvidence.assess(input().copy(registeredCameras=10))}
        assertThrows(IllegalArgumentException::class.java){ScanQualityEvidence.assess(input().copy(consistentDepthPixels=10001))}
        assertThrows(IllegalArgumentException::class.java){ScanQualityEvidence.assess(input().copy(photographicFaceFraction=1.01))}
        assertThrows(IllegalArgumentException::class.java){ScanQualityEvidence.assess(input().copy(medianParallaxDegrees=Double.NaN))}
    }
}
