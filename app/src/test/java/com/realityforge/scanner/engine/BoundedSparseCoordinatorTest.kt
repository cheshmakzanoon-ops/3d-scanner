package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.*
import org.junit.Assert.*
import org.junit.Test

internal class WindowFixture(val count:Int=30,val trackCount:Int=48) {
    val photoIds=List(count) { "photo-%03d".format(it) }
    val lens=LensProjection(500.0,510.0,319.5,239.5,-.04,.006,.001,-.0005,.0002)
    val xyz=List(trackCount) { k -> doubleArrayOf((k%8-3.5)*.12,(k/8-2.5)*.10,3.0+(k%7)*.22) }
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

class BoundedSparseCoordinatorTest {
    private fun reject(block:()->Unit) { try { block();fail("Expected explicit rejection") } catch(_:IllegalArgumentException) {} }
    @Test fun thirtyCamerasHaveExactDeterministicCompleteAccountingAndGlobalIds() {
        val f=WindowFixture();val plan=f.plan();val seen=linkedSetOf<Pair<String,String>>()
        val windows=mutableListOf<List<String>>()
        val receipt=plan.execute { window ->
            assertTrue(window.photoIds.size in 2..12);windows.add(window.photoIds)
            for(row in window.normalizedRows.toList().chunked(7)) {
                val id=window.globalTrackIds[row[0].toInt()];val photo=window.photoIds[row[1].toInt()]
                seen.add(id to photo)
                val source=f.tracks.single { it.id==id }.observations.single { it.photo==photo }
                val ray=f.views[f.photoIds.indexOf(photo)].normalized(source.keypoint)
                assertEquals(ray.first,row[2],0.0);assertEquals(ray.second,row[3],0.0)
            }
            f.candidates(window)
        }
        assertEquals(3,receipt.windowsExecuted);assertEquals(48,receipt.seeds.size)
        assertEquals(1440,seen.size);assertEquals(1440,receipt.inputObservations)
        assertEquals(1440,receipt.acceptedObservations);assertEquals(0,receipt.rejectedObservations)
        assertEquals(1632,receipt.packedObservations)
        assertEquals(f.tracks.map { it.id }.sorted(),plan.sourceTrackIds)
        val reversed=BoundedSparseCoordinator.prepare(f.photoIds.reversed(),f.views.reversed(),TrackSnapshot(f.tracks.reversed()),anchorPhotoIds=f.photoIds.take(2))
        val secondWindows=mutableListOf<List<String>>()
        val second=reversed.execute { secondWindows.add(it.photoIds);f.candidates(it) }
        assertEquals(windows,secondWindows);assertEquals(receipt,second)
    }
    @Test fun badObservationOutsideProposingWindowRejectsCandidateGlobally() {
        val f=WindowFixture();f.views[28].xy[0]+=80f
        val p=f.plan();val receipt=p.execute { window -> if("photo-028" !in window.photoIds)f.candidates(window) else emptyList() }
        assertEquals(listOf(f.tracks[0].id),receipt.invalidTrackIds)
        assertEquals(47,receipt.seeds.size);assertEquals(30,receipt.rejectedObservations)
        assertEquals(receipt.inputObservations,receipt.acceptedObservations+receipt.rejectedObservations)
    }
    @Test fun noCandidatesAreAccountedAsMissingNotInventedSupport() {
        val f=WindowFixture();val receipt=f.plan().execute { emptyList() }
        assertEquals(48,receipt.missingTrackIds.size);assertTrue(receipt.invalidTrackIds.isEmpty())
        assertEquals(1440,receipt.rejectedObservations);assertEquals(0,receipt.acceptedObservations)
        assertEquals(f.photoIds,receipt.unsupportedPhotoIds)
    }
    @Test fun allBudgetsRejectBeforeAnySolverCanRun() {
        val f=WindowFixture()
        reject { f.plan(BoundedSparseCoordinator.Budget(maxCameras=29)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxTracks=47)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxObservations=1439)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxWindows=2)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxWindowObservations=575)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxPackedObservations=1631)) }
        reject { f.plan(BoundedSparseCoordinator.Budget(maxCandidateObservationChecks=4319)) }
    }
    @Test fun malformedReceiptsAndUnknownSourcesFailClosed() {
        val f=WindowFixture();val p=f.plan()
        reject { p.execute { listOf(BoundedSparseCoordinator.Candidate("invented",0.0,0.0,3.0)) } }
        reject { p.execute { val one=f.candidates(it).first();listOf(one,one) } }
        reject { BoundedSparseCoordinator.prepare(f.photoIds.dropLast(1),f.views.dropLast(1),f.snapshot) }
        reject { p.execute(Double.NaN) { emptyList() } }
        val receipt=p.execute { f.candidates(it).map { c -> c.copy(x=Double.NaN) } }
        assertEquals(48,receipt.invalidTrackIds.size);assertEquals(144,receipt.rejectedCandidates)
    }
    @Test fun snapshotDoesNotChangeWhenCallerOrCallbackMutatesArrays() {
        val f=WindowFixture();val plan=f.plan()
        f.views[0].xy.fill(0f);f.views[0].rotation.fill(0.0);f.views[0].translation.fill(999.0)
        val receipt=plan.execute { window ->
            val candidates=f.candidates(window);window.cameraRows.fill(999.0);window.normalizedRows.fill(999.0);candidates
        }
        assertEquals(48,receipt.seeds.size)
    }
    @Test fun zeroBaselineAndBehindCameraSeedsNeverBecomeAcceptedGeometry() {
        val f=WindowFixture(3)
        for(view in f.views) { view.translation.fill(0.0);f.views[0].xy.copyInto(view.xy) }
        reject { f.plan() }
        val g=WindowFixture(3)
        assertTrue(g.plan().execute { g.candidates(it).map { p -> p.copy(z=-p.z) } }.seeds.isEmpty())
    }

    @Test fun globalTypedPackingPreservesEveryOriginalPixelAndExplicitAnchor() {
        val f=WindowFixture();val anchors=listOf(f.photoIds[8],f.photoIds[4])
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,f.snapshot,anchorPhotoIds=anchors)
        val receipt=plan.execute { f.candidates(it) };val input=plan.bundleInput(receipt)
        assertEquals(anchors,input.photoIds.take(2));assertEquals(1440,input.pixelRows.size/7)
        assertEquals(30,input.groupIds.distinct().size)
        for(row in input.pixelRows.toList().chunked(7)) {
            val track=f.tracks.single { it.id==input.sourceTrackIds[row[0].toInt()] }
            val photo=input.photoIds[row[1].toInt()];val key=track.observations.single { it.photo==photo }.keypoint
            val view=f.views[f.photoIds.indexOf(photo)]
            assertEquals(view.xy[2*key].toDouble(),row[2],0.0)
            assertEquals(view.xy[2*key+1].toDouble(),row[3],0.0)
        }
        assertEquals(1,plan.bundleInput(receipt,"c".repeat(64)).groupIds.distinct().size)
        reject { plan.bundleInput(receipt.copy(seeds=receipt.seeds.drop(1))) }
        reject { plan.bundleInput(receipt.copy(acceptedObservations=1)) }
        reject { plan.bundleInput(receipt.copy(seeds=receipt.seeds.map { it.copy(candidate=it.candidate.copy(z=-1.0)) })) }
        reject { WindowFixture(33).plan() }
        assertEquals(32,WindowFixture(32).plan().photoIds.size)
    }
    @Test fun unsupportedRegisteredCameraIsReportedAndGlobalBundleFailsClosed() {
        val f=WindowFixture()
        val tracks=f.tracks.map { t -> val observations=t.observations.dropLast(1)
            StableTrack(observations.minOf { it.identity() },observations) }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks))
        val points=tracks.withIndex().associate { (key,t) -> t.id to BoundedSparseCoordinator.Candidate(t.id,f.xyz[key][0],f.xyz[key][1],f.xyz[key][2]) }
        val receipt=plan.execute { w -> w.normalizedRows.toList().chunked(7).map { it[0].toInt() }.distinct().map { points.getValue(w.globalTrackIds[it]) } }
        assertEquals(listOf("photo-029"),receipt.unsupportedPhotoIds)
        assertEquals(1392,receipt.acceptedObservations)
        reject { plan.bundleInput(receipt) }
    }

    @Test fun cooperativeCancellationInterruptsPlanningValidationAndPacking() {
        val f=WindowFixture();val plan=f.plan();val receipt=plan.execute { f.candidates(it) }
        fun cancelled(action:(()->Unit)->Unit) {
            var checks=0
            try { action { if(++checks==20)throw java.util.concurrent.CancellationException("test") };fail("Cancellation swallowed") }
            catch(_:java.util.concurrent.CancellationException) { assertEquals(20,checks) }
        }
        cancelled { check -> BoundedSparseCoordinator.prepare(f.photoIds,f.views,f.snapshot,checkpoint=check) }
        cancelled { check -> plan.execute(checkpoint=check) { f.candidates(it) } }
        cancelled { check -> plan.bundleInput(receipt,checkpoint=check) }
    }
    @Test fun disconnectedAcceptedComponentsCannotClaimOneAnchoredGlobalBundle() {
        val f=WindowFixture()
        val tracks=f.tracks.mapIndexed { key,t -> val obs=if(key<24)t.observations.take(15) else t.observations.drop(15)
            StableTrack(obs.minOf { it.identity() },obs) }
        val points=tracks.withIndex().associate { (key,t) -> t.id to BoundedSparseCoordinator.Candidate(t.id,f.xyz[key][0],f.xyz[key][1],f.xyz[key][2]) }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks))
        val receipt=plan.execute { w -> w.normalizedRows.toList().chunked(7).map { it[0].toInt() }.distinct().map { points.getValue(w.globalTrackIds[it]) } }
        assertTrue(receipt.unsupportedPhotoIds.isEmpty());assertEquals(48,receipt.seeds.size)
        reject { plan.bundleInput(receipt) }
    }
    @Test fun heterogeneousWindowExplosionIsExplicitRatherThanTruncated() {
        val f=WindowFixture(32,129)
        val pairs=(1..30).flatMap { a -> (a+1..31).map { b -> a to b } }.take(129)
        val tracks=f.tracks.mapIndexed { key,t -> val (a,b)=pairs[key]
            val obs=t.observations.filter { it.photo in listOf(f.photoIds[0],f.photoIds[a],f.photoIds[b]) }
            StableTrack(obs.minOf { it.identity() },obs) }
        reject { BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks)) }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(tracks),BoundedSparseCoordinator.Budget(maxWindows=129))
        assertEquals(129,plan.windowCount);assertEquals(387,plan.inputObservations)
    }
    @Test fun repeatedFirstViewpointUsesNoncoincidentWindowAnchors() {
        val f=WindowFixture()
        f.views[0].translation.copyInto(f.views[1].translation);f.views[0].xy.copyInto(f.views[1].xy)
        val plan=f.plan();val receipt=plan.execute { w ->
            val t0=w.cameraRows.sliceArray(9..11);val t1=w.cameraRows.sliceArray(21..23)
            assertTrue(t0.indices.any { t0[it]!=t1[it] });f.candidates(w)
        }
        assertEquals(48,receipt.seeds.size)
        // Explicit global BA gauge still requires distinct caller anchors.
        reject { plan.bundleInput(receipt) }
    }
    @Test fun heterogeneousTracksAndTailHaveNoDroppedObservations() {
        val f=WindowFixture(13,3)
        val sparse=f.tracks.mapIndexed { i,t ->
            val obs=when(i){0->t.observations;1->t.observations.filterIndexed { k,_ -> k%2==0 };else->t.observations.takeLast(2)}
            StableTrack(obs.minOf { it.identity() },obs)
        }
        val plan=BoundedSparseCoordinator.prepare(f.photoIds,f.views,TrackSnapshot(sparse))
        val seen=hashSetOf<Pair<String,String>>()
        val receipt=plan.execute { w ->
            for(row in w.normalizedRows.toList().chunked(7))seen.add(w.globalTrackIds[row[0].toInt()] to w.photoIds[row[1].toInt()])
            emptyList()
        }
        assertEquals(22,seen.size);assertEquals(22,receipt.inputObservations)
        assertEquals(4,receipt.windowsExecuted)
    }
}
