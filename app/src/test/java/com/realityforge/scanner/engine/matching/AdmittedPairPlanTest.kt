package com.realityforge.scanner.engine.matching

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AdmittedPairPlanTest {
    private fun summaries(count: Int) = (0 until count).map { i ->
        PhotoFeatureSummary("photo-%02d".format(i), FloatArray(128) { if (it == i / 2) 1f else 0f })
    }

    @Test fun everyAdmittedPairIsConsideredRegardlessOfImportOrder() {
        val photos = summaries(12)
        val expected = AdmittedPairPlan.build(photos)
        assertEquals(66, expected.pairs.size)
        assertEquals(66, expected.pairs.map { it.firstPhotoId to it.secondPhotoId }.toSet().size)
        for (seed in 0..12) {
            val actual = AdmittedPairPlan.build(photos.shuffled(java.util.Random(seed.toLong())))
            assertEquals(expected.pairs, actual.pairs)
            assertEquals(expected.photoIds, actual.photoIds)
        }
        assertTrue(expected.pairs.any { it.firstPhotoId == "photo-00" && it.secondPhotoId == "photo-11" })
    }

    @Test fun zeroOrOrthogonalSummaryDoesNotSilentlyExcludePhotograph() {
        val photos = summaries(3) + PhotoFeatureSummary("zero", FloatArray(128))
        val plan = AdmittedPairPlan.build(photos)
        assertEquals(6, plan.pairs.size)
        assertEquals(listOf("zero"), plan.retrieval.zeroEvidencePhotos)
        assertEquals(3, plan.pairs.count { it.secondPhotoId == "zero" })
        assertTrue(plan.pairs.any { it.provenance == "admitted-small-scene-exhaustive-fallback-v1" })
    }

    @Test fun retrievalConnectivityIsNeverSubstitutedForVerifiedGeometry() {
        val photos = (0..3).map { PhotoFeatureSummary("p$it", FloatArray(128) { 1f }) }
        val plan = AdmittedPairPlan.build(photos)
        assertEquals(1, plan.retrieval.candidateComponents.size)
        assertEquals(listOf(listOf("p0"), listOf("p1"), listOf("p2"), listOf("p3")),
            plan.verifiedComponents(emptySet()))
        assertEquals(listOf(listOf("p0", "p1"), listOf("p2", "p3")),
            plan.verifiedComponents(setOf("p0" to "p1", "p2" to "p3")))
        assertThrows(IllegalArgumentException::class.java) { plan.verifiedComponents(setOf("p0" to "unknown")) }
    }

    @Test fun noLargeProfileAdmissionOrDuplicateIdentity() {
        assertThrows(IllegalArgumentException::class.java) { AdmittedPairPlan.build(summaries(13)) }
        assertThrows(IllegalArgumentException::class.java) { AdmittedPairPlan.build(summaries(1)) }
        val photo = summaries(1).single()
        assertThrows(IllegalArgumentException::class.java) { AdmittedPairPlan.build(listOf(photo, photo)) }
    }

    @Test fun cancellationPropagatesBeforeAndDuringPairEnumeration() {
        assertThrows(CancellationException::class.java) {
            AdmittedPairPlan.build(summaries(12)) { throw CancellationException("cancelled") }
        }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            AdmittedPairPlan.build(summaries(12)) { if (++checks == 4) throw CancellationException("cancelled") }
        }
        assertEquals(4, checks)
    }
}
