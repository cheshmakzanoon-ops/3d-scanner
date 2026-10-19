package com.realityforge.scanner.capture.guidance

/** Frame wrapper used by an injected matcher. Payload ownership remains with the caller. */
data class GuidanceFrame<T>(
    val identity: GuidanceFrameIdentity,
    val payload: T,
)

/**
 * Implementations compare two frames and return bounded evidence. The coordinator never retains
 * either payload, which prevents a live preview window from accidentally retaining image buffers.
 */
fun interface GuidanceMatcher<T> {
    fun compare(reference: GuidanceFrame<T>, candidate: GuidanceFrame<T>): PairwiseGuidanceEvidence
}

/**
 * Small orchestration seam for CameraScreen/Agent 014. Matching must run on its bounded analysis
 * executor; this class performs identity validation and hands only summaries to the state machine.
 */
class CaptureGuidanceCoordinator<T>(
    private val matcher: GuidanceMatcher<T>,
    private val engine: CaptureGuidanceEngine,
) {
    fun beginSession(session: CaptureSessionIdentity) = engine.beginSession(session)

    fun endSession(session: CaptureSessionIdentity): Boolean = engine.endSession(session)

    fun recordQuality(observation: CaptureQualityObservation): GuidanceIngestResult =
        engine.recordQuality(observation)

    fun recordMotion(observation: CaptureMotionObservation): GuidanceIngestResult =
        engine.recordMotion(observation)

    fun compare(
        reference: GuidanceFrame<T>,
        candidate: GuidanceFrame<T>,
    ): GuidanceIngestResult {
        require(reference.identity.session == candidate.identity.session) {
            "matcher frames must use one capture session"
        }
        val evidence = matcher.compare(reference, candidate)
        require(evidence.reference == reference.identity) {
            "matcher returned evidence for a different reference frame"
        }
        require(evidence.candidate == candidate.identity) {
            "matcher returned evidence for a different candidate frame"
        }
        return engine.recordGeometry(evidence)
    }

    fun currentObservation(): GuidanceObservation? = engine.currentObservation()
}
