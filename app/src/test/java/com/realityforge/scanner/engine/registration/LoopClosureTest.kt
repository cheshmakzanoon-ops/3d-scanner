package com.realityforge.scanner.engine.registration
import org.junit.Assert.*
import org.junit.Test
class LoopClosureTest {
    @Test fun similarityIsNeverAcceptedAsLoopAndLimitsAreDeterministic(){
        val valid=LoopClosure.Proposal("first","revisit","component",0,8,(0L..30).toSet(),true)
        val unverified=valid.copy(firstPhotoId="lookalike",geometricallyVerifiedPair=false)
        val decisions=LoopClosure.candidates(listOf(unverified,valid,valid),1)
        assertEquals(1,decisions.count{it.eligibleForNativeVerification})
        assertTrue(decisions.any{it.reason=="pair_unverified"})
        assertEquals(decisions,LoopClosure.candidates(listOf(valid,valid,unverified),1))
        assertEquals("requires_native_geometric_verification",decisions.first{it.eligibleForNativeVerification}.reason)
    }
}
