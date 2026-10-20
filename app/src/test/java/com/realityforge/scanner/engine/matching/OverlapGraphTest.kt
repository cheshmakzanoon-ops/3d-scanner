package com.realityforge.scanner.engine.matching
import org.junit.Assert.*
import org.junit.Test
class OverlapGraphTest {
    @Test fun orderingDoesNotChangeCandidatesAndDegreesAreBoundedAt250() {
        val photos=(0 until 250).map{i->PhotoFeatureSummary("photo-%03d".format(i),FloatArray(128){j->if(j==i%128)1f else .001f})}
        val graph=OverlapGraph(8,64)
        val a=graph.build(photos);val b=graph.build(photos.shuffled(java.util.Random(31)))
        assertEquals(a.pairs,b.pairs);assertTrue(a.pairs.size<=250*8/2)
        assertTrue(a.comparisons<=250*64)
        val degrees=a.pairs.flatMap{listOf(it.firstPhotoId,it.secondPhotoId)}.groupingBy{it}.eachCount()
        assertTrue(degrees.values.all{it<=8})
    }
    @Test fun zeroEvidenceIsDisconnectedAndDuplicatesAreOnlySummaryEvidence() {
        val a=PhotoFeatureSummary("a",FloatArray(8){1f});val b=PhotoFeatureSummary("b",FloatArray(8){1f});val z=PhotoFeatureSummary("z",FloatArray(8))
        val graph=OverlapGraph().build(listOf(z,b,a))
        assertEquals(listOf(listOf("a","b")),graph.duplicateSummaryGroups)
        assertEquals(listOf("z"),graph.zeroEvidencePhotos)
        assertTrue(graph.candidateComponents.contains(listOf("z")))
        assertEquals(1,graph.pairs.size)
    }
    @Test fun smallKnownSummaryNeighborsAreRetrievedWithoutOrderHeuristic() {
        val photos=(0 until 12).map{i->PhotoFeatureSummary("p$i",FloatArray(16){j->if(j==i/2)1f else 0f})}
        val graph=OverlapGraph(2).build(photos.reversed())
        for(i in 0 until 12 step 2)assertTrue(graph.pairs.any{setOf(it.firstPhotoId,it.secondPhotoId)==setOf("p$i","p${i+1}")})
    }
    @Test fun syntheticCollectionProfilesReportBoundedRetrievalCost() {
        for(size in listOf(30,80,250)) {
            val photos=(0 until size).map{i->PhotoFeatureSummary("manifest-%03d".format(i),FloatArray(128){j->if(j==i%32)1f else .01f})}
            val started=System.nanoTime();val result=OverlapGraph(12,96).build(photos)
            val elapsed=(System.nanoTime()-started)/1000000.0
            assertTrue(result.pairs.size<=size*12/2)
            assertTrue(result.comparisons<=size*maxOf(96,size-1).coerceAtMost(96))
            println("SYNTHETIC retrieval photos=$size edges=${result.pairs.size} comparisons=${result.comparisons} exhaustiveDirected=${size*(size-1)} elapsedMs=$elapsed")
        }
    }
    @Test fun resultCollectionsCannotMutatePublishedEvidence() {
        val graph=OverlapGraph().build(listOf(PhotoFeatureSummary("a",FloatArray(8){1f}),PhotoFeatureSummary("b",FloatArray(8){1f})))
        assertThrows(UnsupportedOperationException::class.java){(graph.pairs as MutableList<PairCandidate>).clear()}
        assertThrows(UnsupportedOperationException::class.java){(graph.candidateComponents[0] as MutableList<String>).clear()}
    }
    @Test fun invalidIdentityOrDescriptorSummaryFails() {
        assertThrows(IllegalArgumentException::class.java){OverlapGraph().build(listOf(PhotoFeatureSummary("p",FloatArray(8)),PhotoFeatureSummary("p",FloatArray(8))))}
        assertThrows(IllegalArgumentException::class.java){PhotoFeatureSummary.siftMean("p",FloatArray(129))}
    }
}
