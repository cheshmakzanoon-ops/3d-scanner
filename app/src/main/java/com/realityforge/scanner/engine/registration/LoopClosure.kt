package com.realityforge.scanner.engine.registration

/** Scheduling evidence only: proposal confidence never means geometric acceptance. */
object LoopClosure {
    class Proposal(val firstPhotoId: String, val revisitPhotoId: String, val componentId: String,
                   val firstCaptureIndex: Int, val revisitCaptureIndex: Int,
                   commonStableTrackIds: Set<String>, val geometricallyVerifiedPair: Boolean) {
        val commonStableTrackIds: Set<String> = java.util.Collections.unmodifiableSet(HashSet(commonStableTrackIds))
        fun copy(firstPhotoId: String=this.firstPhotoId, revisitPhotoId: String=this.revisitPhotoId,
                 componentId: String=this.componentId, firstCaptureIndex: Int=this.firstCaptureIndex,
                 revisitCaptureIndex: Int=this.revisitCaptureIndex, commonStableTrackIds: Set<String> =this.commonStableTrackIds,
                 geometricallyVerifiedPair: Boolean=this.geometricallyVerifiedPair) =
            Proposal(firstPhotoId,revisitPhotoId,componentId,firstCaptureIndex,revisitCaptureIndex,commonStableTrackIds,geometricallyVerifiedPair)
        override fun equals(other: Any?): Boolean = other is Proposal && firstPhotoId==other.firstPhotoId && revisitPhotoId==other.revisitPhotoId &&
            componentId==other.componentId && firstCaptureIndex==other.firstCaptureIndex && revisitCaptureIndex==other.revisitCaptureIndex &&
            commonStableTrackIds==other.commonStableTrackIds && geometricallyVerifiedPair==other.geometricallyVerifiedPair
        override fun hashCode() = listOf(firstPhotoId,revisitPhotoId,componentId,firstCaptureIndex,revisitCaptureIndex,commonStableTrackIds,geometricallyVerifiedPair).hashCode()
    }
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
                p.commonStableTrackIds.size !in 16..2000 || p.commonStableTrackIds.any { !it.matches(Regex("[0-9a-f]{64}")) } -> "insufficient_independent_support"
                admitted>=maximum -> "verification_budget"
                else -> "requires_native_geometric_verification"
            }
            val eligible=reason=="requires_native_geometric_verification";if(eligible)admitted++
            Decision(p,eligible,reason)
        }
    }
}
