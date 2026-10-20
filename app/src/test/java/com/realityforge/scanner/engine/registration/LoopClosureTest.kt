package com.realityforge.scanner.engine.registration
import org.junit.Assert.*
import org.junit.Test
class LoopClosureTest {
    @Test fun proposalDefensivelySnapshotsFullStableIds(){
        val ids=(0..30).map { "%064x".format(it) }.toMutableSet()
        val p=LoopClosure.Proposal("a","b","component",0,8,ids,true);ids.clear()
        assertEquals(31,p.commonStableTrackIds.size)
        try {(p.commonStableTrackIds as MutableSet<String>).clear();fail("Mutable stable identity escaped")}catch(_:UnsupportedOperationException){}
        assertTrue(p.commonStableTrackIds.all { it.length==64 })
    }
    @Test fun similarityIsNeverAcceptedAsLoopAndLimitsAreDeterministic(){
        val valid=LoopClosure.Proposal("first","revisit","component",0,8,(0..30).map { "%064x".format(it) }.toSet(),true)
        val unverified=valid.copy(firstPhotoId="lookalike",geometricallyVerifiedPair=false)
        val decisions=LoopClosure.candidates(listOf(unverified,valid,valid),1)
        assertEquals(1,decisions.count{it.eligibleForNativeVerification})
        assertTrue(decisions.any{it.reason=="pair_unverified"})
        assertEquals(decisions,LoopClosure.candidates(listOf(valid,valid,unverified),1))
        assertEquals("requires_native_geometric_verification",decisions.first{it.eligibleForNativeVerification}.reason)
    }
}
