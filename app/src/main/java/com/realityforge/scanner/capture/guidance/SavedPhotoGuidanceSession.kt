package com.realityforge.scanner.capture.guidance

import com.realityforge.scanner.capture.analysis.CaptureAnalysisBudgetProvider
import com.realityforge.scanner.capture.analysis.CaptureQualityActionCode
import com.realityforge.scanner.capture.analysis.CaptureQualityMetrics
import com.realityforge.scanner.capture.analysis.CaptureQualityObservation as LiveQualityObservation
import com.realityforge.scanner.capture.session.CaptureSessionIdentity as CameraSessionIdentity
import com.realityforge.scanner.capture.session.CapturedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bounded feature payload. Retention contains primitive arrays only, never bitmaps/native Mats. */
data class SavedPhotoGuidanceFeatures(
    val originalSha256: String,
    val width: Int,
    val height: Int,
    val xy: FloatArray,
    val descriptors: ByteArray,
    val colors: IntArray,
    val focal35mm: Double?,
    val savedQuality: CaptureQualityMetrics? = null,
) {
    init {
        require(originalSha256.matches(Regex("[0-9a-f]{64}")))
        require(width in 3..1024 && height in 3..1024)
        require(colors.size <= 1_000 && xy.size == colors.size * 2 && descriptors.size == colors.size * 32)
        require(xy.indices.all { i -> xy[i].isFinite() && xy[i] >= 0 && xy[i] < if (i % 2 == 0) width else height })
        require(focal35mm == null || focal35mm.isFinite() && focal35mm in 10.0..600.0)
        require(savedQuality == null || savedQuality.sampledPixelCount == width * height &&
            savedQuality.interFrameResidual == null)
    }
    val retainedBytes: Int get() = xy.size * 4 + descriptors.size + colors.size * 4
}

fun interface SavedPhotoFeatureExtractor {
    fun extract(photo: CapturedPhoto, maxEdge: Int, maxFeatures: Int): SavedPhotoGuidanceFeatures
}

fun interface SavedPhotoPairProvider {
    fun compare(reference: SavedPhotoGuidanceFeatures, candidate: SavedPhotoGuidanceFeatures,
        allowPose: Boolean): PipelinePairEvidence
}

/**
 * Optional advice about the last two accepted photographs. The screen calls this while it owns
 * the staged file, after durable import acknowledgment. One descriptor frame is retained; one
 * extraction/comparison is admitted at a time, and close immediately revokes publication.
 */
class SavedPhotoGuidanceSession(
    private val session: CameraSessionIdentity,
    private val budgets: CaptureAnalysisBudgetProvider,
    private val extractor: SavedPhotoFeatureExtractor = OpenCvSavedPhotoGuidance,
    private val matcher: SavedPhotoPairProvider = OpenCvSavedPhotoGuidance,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onAcceptedMeasurement: (CapturedPhoto, SavedPhotoGuidanceFeatures) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val identity = CaptureSessionIdentity(session.projectId, session.initialRevision, session.sessionToken)
    private val engine = CaptureGuidanceEngine().apply { beginSession(identity) }
    private val operation = Mutex()
    private val lock = Any()
    private var closed = false
    private var lastAdmittedNanos: Long? = null
    private var sequence = 0L
    private var previous: GuidanceFrame<SavedPhotoGuidanceFeatures>? = null

    suspend fun acceptedPhoto(photo: CapturedPhoto, quality: LiveQualityObservation? = null): GuidanceObservation? =
        withContext(Dispatchers.Default) {
            operation.withLock {
                require(photo.projectId == session.projectId && photo.sessionToken == session.sessionToken &&
                    photo.revision >= session.initialRevision) { "Guidance photo belongs to another capture session." }
                val budget = budgets.currentBudget()
                val timestamp = clockNanos().coerceAtLeast(0)
                val frameIdentity = synchronized(lock) {
                    if (closed) return@withLock null
                    val prior = lastAdmittedNanos
                    val cadence = maxOf(750_000_000L, budget.minFrameIntervalNanos)
                    if (prior != null && (timestamp < prior || timestamp - prior < cadence)) return@withLock null
                    // Severe-resource analysis is optional; clear old descriptors rather than
                    // retaining a frame larger than the new budget or delaying photo saving.
                    if (budget.maxAnalysisEdge < 128) {
                        previous = null
                        engine.beginSession(identity)
                        return@withLock engine.currentObservation()
                    }
                    if ((previous?.payload?.retainedBytes ?: 0) > budget.maxRetainedFrameBytes) previous = null
                    lastAdmittedNanos = timestamp
                    sequence += 1
                    GuidanceFrameIdentity(identity, photo.captureToken, timestamp, sequence)
                }
                coroutineContext.ensureActive()
                val features = extractor.extract(photo, minOf(640, budget.maxAnalysisEdge),
                    minOf(1_000, budget.maxRetainedFrameBytes / 44))
                require(features.retainedBytes <= budget.maxRetainedFrameBytes)
                coroutineContext.ensureActive()
                val candidate = GuidanceFrame(frameIdentity, features)
                val reference = synchronized(lock) { if (closed) return@withLock null else previous }
                val pair = reference?.let { matcher.compare(it.payload, features,
                    allowPose = budget.maxAnalysisEdge >= 256 && budget.minFrameIntervalNanos < 500_000_000L) }
                if (reference != null && pair != null) {
                    require(pair.rawMatchCount <= reference.payload.colors.size &&
                        pair.reciprocalMatchCount <= minOf(reference.payload.colors.size, features.colors.size) &&
                        pair.geometricInlierCount <= pair.reciprocalMatchCount) { "Pair evidence exceeds measured features." }
                }
                coroutineContext.ensureActive()
                synchronized(lock) {
                    if (closed) return@withLock null
                    onAcceptedMeasurement(photo, features)
                    previous = candidate
                    if (reference == null || pair == null) return@withLock engine.currentObservation()
                    associatedQuality(photo, quality, timestamp)?.let { engine.recordQuality(it) }
                    engine.recordGeometry(PairwiseGuidanceEvidence(pair.evidenceId, EvidenceProvenance.ENGINE_MATCHER,
                        reference.identity, candidate.identity, pair.rawMatchCount, pair.reciprocalMatchCount,
                        pair.geometricInlierCount, pair.occupiedImageCells, pair.totalImageCells,
                        pair.medianImageDisplacementFraction, pair.geometricModel, pair.poseValidated,
                        pair.poseRotationDegrees, pair.translationOverSceneDepth, pair.medianParallaxDegrees,
                        pair.normalizedReprojectionError, pair.dynamicOutlierFraction))
                    engine.currentObservation()
                }
            }
        }

    private fun associatedQuality(photo: CapturedPhoto, quality: LiveQualityObservation?, timestamp: Long): CaptureQualityObservation? {
        if (quality == null || quality.sessionId.value != session.sessionToken) return null
        val stillTimestamp = photo.metadata.fields["sensorTimestampNanos"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        // Both values are from the same camera sensor clock. Only after checking that association
        // do we map this signal to the accepted-photo observation's monotonic event timestamp.
        if (nearestTimestampDistance(stillTimestamp, quality.frameTimestampNanos) > 180_000_000L) return null
        val state = when (quality.action) {
            CaptureQualityActionCode.READY_TO_CAPTURE -> CaptureQualityState.ACCEPTABLE
            CaptureQualityActionCode.HOLD_STEADY, CaptureQualityActionCode.REFOCUS_OR_REFRAME -> CaptureQualityState.BLUR_RISK
            CaptureQualityActionCode.ADD_LIGHT, CaptureQualityActionCode.REDUCE_LIGHT,
            CaptureQualityActionCode.EVEN_OUT_LIGHTING -> CaptureQualityState.EXPOSURE_RISK
            CaptureQualityActionCode.WAIT_FOR_EVIDENCE -> CaptureQualityState.UNKNOWN
        }
        return CaptureQualityObservation(quality.observationId, identity, timestamp, state,
            if (state == CaptureQualityState.UNKNOWN) null else quality.confidence)
    }

    fun retainedFeatureBytes(): Int = synchronized(lock) { previous?.payload?.retainedBytes ?: 0 }

    override fun close() {
        synchronized(lock) {
            closed = true
            previous = null
            engine.endSession(identity)
        }
    }
}
