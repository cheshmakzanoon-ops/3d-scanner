package com.realityforge.scanner.engine.registration

/** Scheduling evidence only: proposal confidence never means geometric acceptance. */
object LoopClosure {
    data class Proposal(val firstPhotoId: String, val revisitPhotoId: String, val componentId: String,
                        val firstCaptureIndex: Int, val revisitCaptureIndex: Int,
                        val commonStableTrackIds: Set<Long>, val geometricallyVerifiedPair: Boolean)
    data class Decision(val proposal: Proposal, val eligibleForNativeVerification: Boolean, val reason: String)
    fun candidates(proposals: List<Proposal>, maximum: Int=16, cancelled: () -> Boolean={false}): List<Decision> {
        require(maximum in 1..64 && proposals.size<=4096)
        val seen=HashSet<Pair<String,String>>()
        var admitted=0
        return proposals.sortedWith(compareByDescending<Proposal>{it.commonStableTrackIds.size}.thenBy{it.firstPhotoId}.thenBy{it.revisitPhotoId}).map { p ->
            if(cancelled())throw java.util.concurrent.CancellationException("Loop verification cancelled")
            val key=if(p.firstPhotoId<p.revisitPhotoId)p.firstPhotoId to p.revisitPhotoId else p.revisitPhotoId to p.firstPhotoId
            val reason=when {
                p.firstPhotoId.isBlank() || p.revisitPhotoId.isBlank() || p.firstPhotoId==p.revisitPhotoId -> "invalid_photo_identity"
                p.componentId.isBlank() -> "disconnected_components"
                p.firstCaptureIndex<0 || p.revisitCaptureIndex<0 || kotlin.math.abs(p.firstCaptureIndex-p.revisitCaptureIndex)<3 -> "adjacent_views"
                !seen.add(key) -> "duplicate_candidate"
                !p.geometricallyVerifiedPair -> "pair_unverified"
                p.commonStableTrackIds.size !in 16..2000 || p.commonStableTrackIds.any {it<0} -> "insufficient_independent_support"
                admitted>=maximum -> "verification_budget"
                else -> "requires_native_geometric_verification"
            }
            val eligible=reason=="requires_native_geometric_verification";if(eligible)admitted++
            Decision(p,eligible,reason)
        }
    }
}
