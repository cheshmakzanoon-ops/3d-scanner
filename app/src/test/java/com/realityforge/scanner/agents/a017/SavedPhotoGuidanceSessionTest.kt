package com.realityforge.scanner.agents.a017

import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudget
import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.guidance.*
import com.realityforge.scanner.capture.session.CaptureSessionIdentity as CameraIdentity
import com.realityforge.scanner.capture.session.CaptureMetadataSnapshot
import com.realityforge.scanner.capture.session.CapturedPhoto
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Injected descriptors are labeled synthetic here; Android tests exercise the actual extractor. */
class SavedPhotoGuidanceSessionTest {
    @Test fun boundedSavedPairsCarrySessionAndDoNotTurnImageHomographyIntoCameraPose() = runBlocking {
        var time = 0L
        var comparisons = 0
        val service = SavedPhotoGuidanceSession(IDENTITY, CaptureAnalysisBudgetProvider { CaptureAnalysisBudget() },
            SavedPhotoFeatureExtractor { _, edge, count -> features(edge, minOf(100, count)) },
            SavedPhotoPairProvider { _, _, _ -> comparisons++; repeatedEvidence() }, { time })
        val first = requireNotNull(service.acceptedPhoto(photo(0)))
        assertEquals(GuidanceOutcome.UNKNOWN, first.outcome)
        time = 1_000_000_000L
        val second = requireNotNull(service.acceptedPhoto(photo(1)))
        assertEquals(GuidanceOutcome.REPEATED_VIEW, second.outcome)
        assertEquals(ImageOverlapState.VERY_HIGH_REPEATED_VIEW, second.imageOverlap)
        assertFalse(second.localPairGeometryValidated)
        assertFalse(GuidanceReasonCode.NEAR_ZERO_TRANSLATION in second.reasonCodes)
        assertEquals(ObjectCoverageEvidence.UNKNOWN_NOT_MEASURED, second.objectCoverage)
        assertEquals(100 * 44, service.retainedFeatureBytes())
        time += 50_000_000L
        assertNull(service.acceptedPhoto(photo(2)))
        assertEquals(1, comparisons)
        service.close()
        assertEquals(0, service.retainedFeatureBytes())
        time += 1_000_000_000L
        assertNull(service.acceptedPhoto(photo(3)))
    }

    @Test fun closeDuringExtractionRevokesCompletionWithoutRetainingNativeOrPixelOwnership() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val measured = java.util.concurrent.atomic.AtomicInteger()
        val service = SavedPhotoGuidanceSession(IDENTITY, CaptureAnalysisBudgetProvider { CaptureAnalysisBudget() },
            SavedPhotoFeatureExtractor { _, edge, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); features(edge, 40) },
            SavedPhotoPairProvider { _, _, _ -> error("No pair should be admitted") }, { 1L },
            onAcceptedMeasurement = { _, _ -> measured.incrementAndGet() })
        val running = async(Dispatchers.Default) { service.acceptedPhoto(photo(0)) }
        check(entered.await(5, TimeUnit.SECONDS))
        service.close()
        release.countDown()
        assertNull(running.await())
        assertEquals(0, service.retainedFeatureBytes())
        assertEquals(0, measured.get())
    }

    @Test fun severeBudgetClearsRetainedFeaturesAndForeignSessionCannotReachExtractor() = runBlocking {
        var budget = CaptureAnalysisBudget()
        var time = 0L
        var extracts = 0
        val service = SavedPhotoGuidanceSession(IDENTITY, CaptureAnalysisBudgetProvider { budget },
            SavedPhotoFeatureExtractor { _, edge, count -> extracts++; features(edge, minOf(count, 40)) },
            SavedPhotoPairProvider { _, _, _ -> repeatedEvidence() }, { time })
        service.acceptedPhoto(photo(0))
        try { service.acceptedPhoto(photo(1).copy(projectId = "foreign")); fail("Accepted foreign session") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, extracts)
        budget = CaptureAnalysisBudget(maxAnalysisEdge = 64, minFrameIntervalNanos = 2_000_000_000L,
            maxRetainedFrameBytes = 4096)
        time = 3_000_000_000L
        val unknown = requireNotNull(service.acceptedPhoto(photo(2)))
        assertEquals(GuidanceOutcome.UNKNOWN, unknown.outcome)
        assertEquals(0, service.retainedFeatureBytes())
        assertEquals(1, extracts)
        service.close()
    }

    private fun features(edge: Int, count: Int) = SavedPhotoGuidanceFeatures("a".repeat(64), edge, edge,
        FloatArray(count * 2) { (it % edge).toFloat() }, ByteArray(count * 32), IntArray(count), null)
    private fun photo(index: Int) = CapturedPhoto(IDENTITY.projectId, IDENTITY.initialRevision + index,
        IDENTITY.sessionToken, "capture-$index", File("synthetic-unused-$index"),
        CaptureMetadataSnapshot(capturedAtEpochMillis = index.toLong()))
    private fun repeatedEvidence() = PipelinePairEvidence("synthetic-repeated-pair", 100, 80, 72, 12, 16,
        0.0, GeometricModel.HOMOGRAPHY_DOMINANT, false, null, null, null, null, null)
    companion object { private val IDENTITY = CameraIdentity("project-017", 7, "session-017") }
}
