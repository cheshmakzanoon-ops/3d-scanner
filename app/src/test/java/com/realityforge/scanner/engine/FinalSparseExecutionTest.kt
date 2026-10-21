package com.realityforge.scanner.engine
import com.realityforge.scanner.engine.tracks.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

internal class FinalSparseFixture(val count:Int=12,val trackCount:Int=48) {
    val photoIds=List(count) { "photo-%03d".format(it) }
    val lens=LensProjection(500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002)
    val xyz=List(trackCount) { k -> doubleArrayOf(((k%48)%8-3.5)*.12,((k%48)/8-2.5)*.10,3.0+((k%48)%7)*.22) }
    val views=List(count) { camera ->
        val pixels=FloatArray(trackCount*2)
        for(k in 0 until trackCount) {
            val p=xyz[k];val ray=lens.distort((p[0]-camera*.03)/p[2],p[1]/p[2])
            pixels[k*2]=(ray.first*lens.fx+lens.cx).toFloat()
            pixels[k*2+1]=(ray.second*lens.fy+lens.cy).toFloat()
        }
        SparseView(640,480,500.0,pixels,IntArray(trackCount){0x825a3c},
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),doubleArrayOf(-camera*.03,0.0,0.0),lens)
    }
    val tracks=List(trackCount) { key ->
        val obs=photoIds.map { SourceObservation(it,key) }.sorted()
        StableTrack(obs.minOf { it.identity() },obs)
    }
    val snapshot=TrackSnapshot(tracks)
    val points=tracks.withIndex().associate { (key,t) -> t.id to BoundedSparseCoordinator.Candidate(t.id,xyz[key][0],xyz[key][1],xyz[key][2]) }
    fun plan(budget:BoundedSparseCoordinator.Budget=BoundedSparseCoordinator.Budget()) =
        BoundedSparseCoordinator.prepare(photoIds,views,snapshot,budget)
    fun candidates(window:BoundedSparseCoordinator.Window)=window.normalizedRows.toList().chunked(7)
        .map { it[0].toInt() }.distinct().map { points.getValue(window.globalTrackIds[it]) }
}

class FinalSparseExecutionTest {
    private fun reject(block:()->Unit) { try { block();fail("Expected explicit rejection") } catch(_:IllegalArgumentException) {} }
    @Test fun admittedTwelveHeterogeneousVisibilityUsesOneCompleteSharedWindow() {
        val f=FinalSparseFixture(12,129)
        val triples=(0..9).flatMap { a -> (a+1..10).flatMap { b -> (b+1..11).map { c -> listOf(a,b,c) } } }.take(129)
        val tracks=f.tracks.mapIndexed { key,t -> val ids=triples[key].map { f.photoIds[it] }
            val obs=t.observations.filter { it.photo in ids };StableTrack(obs.minOf { it.identity() },obs) }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks))
        var calls=0;val seen=hashSetOf<Pair<String,String>>()
        val receipt=plan.execute { w -> calls++;assertEquals(12,w.photoIds.size)
            w.normalizedRows.toList().chunked(7).forEach { seen.add(w.globalTrackIds[it[0].toInt()] to w.photoIds[it[1].toInt()]) }
            emptyList() }
        assertEquals(1,calls);assertEquals(387,seen.size);assertEquals(387,receipt.rejectedObservations)
    }
    @Test fun oversizedCompleteGraphIsRejectedRatherThanLegacyTruncated() {
        val f=FinalSparseFixture(12,9000);var called=false
        reject { FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
            triangulate={called=true;emptyList()},bundle={error("Unexpected bundle")}) }
        assertFalse(called)
    }
    @Test fun productionStillRejectsThirteenAndActiveJobCancellationPropagates() {
        val f=FinalSparseFixture(13)
        reject { FinalSparseExecution.execute(f.photoIds,f.views,f.snapshot,null,.003,24,
            triangulate={error("Unexpected native")},bundle={error("Unexpected bundle")}) }
        val admitted=FinalSparseFixture();val job=Job();job.cancel()
        try { FinalSparseExecution.execute(admitted.photoIds,admitted.views,admitted.snapshot,null,.003,24,
            checkpoint={job.ensureActive()},triangulate={error("Unexpected native")},bundle={error("Unexpected bundle")})
            fail("Coroutine cancellation swallowed")
        } catch(_:java.util.concurrent.CancellationException) {}
    }
    @Test fun accountingMakesNoPublishedOrWholeObjectCoverageClaim() {
        val f=FinalSparseFixture();val receipt=f.plan().execute { emptyList() }
        val json=FinalSparseExecution.accounting(receipt)
        assertFalse(json.getBoolean("published"));assertEquals(576,json.getInt("rejectedSeedObservations"))
        assertEquals(48,json.getJSONArray("missingTrackIds").length());assertFalse(json.has("coverage"))
    }
}
