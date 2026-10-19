package com.realityforge.scanner.capture.guidance

/**
 * Lightweight reference passed to Agent 002's pipeline adapter. It identifies existing feature and
 * calibration artifacts; it never carries pixels or descriptor arrays across this contract.
 */
data class PipelineGuidanceFrameReference(
    val photoId: String,
    val featureArtifactId: String,
    val calibrationIdentity: String,
) {
    init {
        require(photoId.isNotBlank())
        require(featureArtifactId.isNotBlank())
        require(calibrationIdentity.isNotBlank())
    }
}

/** Normalized output required from the real matcher / pose validator owned by Agent 002. */
data class PipelinePairEvidence(
    val evidenceId: String,
    val rawMatchCount: Int,
    val reciprocalMatchCount: Int,
    val geometricInlierCount: Int,
    val occupiedImageCells: Int,
    val totalImageCells: Int,
    val medianImageDisplacementFraction: Double?,
    val geometricModel: GeometricModel,
    val poseValidated: Boolean,
    val poseRotationDegrees: Double?,
    val translationOverSceneDepth: Double?,
    val medianParallaxDegrees: Double?,
    val normalizedReprojectionError: Double?,
    val dynamicOutlierFraction: Double?,
)

/**
 * Agent 002 implements this provider from the production feature/matching/pose stage. A provider
 * must return evidence for the exact immutable artifact identities supplied in the two references.
 */
fun interface PipelinePairEvidenceProvider {
    fun compare(
        reference: PipelineGuidanceFrameReference,
        candidate: PipelineGuidanceFrameReference,
    ): PipelinePairEvidence
}

/** Maps the production pipeline contract into the session-safe guidance contract. */
class PipelineGuidanceMatcher(
    private val provider: PipelinePairEvidenceProvider,
) : GuidanceMatcher<PipelineGuidanceFrameReference> {
    override fun compare(
        reference: GuidanceFrame<PipelineGuidanceFrameReference>,
        candidate: GuidanceFrame<PipelineGuidanceFrameReference>,
    ): PairwiseGuidanceEvidence {
        require(reference.payload.calibrationIdentity == candidate.payload.calibrationIdentity) {
            "guidance comparison requires one calibration identity"
        }
        val result = provider.compare(reference.payload, candidate.payload)
        return PairwiseGuidanceEvidence(
            evidenceId = result.evidenceId,
            provenance = EvidenceProvenance.ENGINE_MATCHER,
            reference = reference.identity,
            candidate = candidate.identity,
            rawMatchCount = result.rawMatchCount,
            reciprocalMatchCount = result.reciprocalMatchCount,
            geometricInlierCount = result.geometricInlierCount,
            occupiedImageCells = result.occupiedImageCells,
            totalImageCells = result.totalImageCells,
            medianImageDisplacementFraction = result.medianImageDisplacementFraction,
            geometricModel = result.geometricModel,
            poseValidated = result.poseValidated,
            poseRotationDegrees = result.poseRotationDegrees,
            translationOverSceneDepth = result.translationOverSceneDepth,
            medianParallaxDegrees = result.medianParallaxDegrees,
            normalizedReprojectionError = result.normalizedReprojectionError,
            dynamicOutlierFraction = result.dynamicOutlierFraction,
        )
    }
}
