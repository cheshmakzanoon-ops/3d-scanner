package com.realityforge.scanner.agents.a077

import com.realityforge.scanner.ui.accessibility.AccessibilityAuditContract as Audit
import com.realityforge.scanner.ui.accessibility.ScanAnnouncementPolicy
import org.junit.Assert.*
import org.junit.Test

class AccessibilityContractTest {
    @Test fun stageAnnouncementsDoNotRepeatEveryProgressTick() {
        val policy = ScanAnnouncementPolicy()
        assertTrue(policy.shouldAnnounce("job1", "depth"))
        repeat(100) { assertFalse(policy.shouldAnnounce("job1", "depth")) }
        assertTrue(policy.shouldAnnounce("job1", "texture"))
        assertTrue(policy.shouldAnnounce("job1", "completed", true))
        assertFalse(policy.shouldAnnounce("job1", "completed", true))
        assertTrue(policy.shouldAnnounce("job2", "depth"))
    }
    @Test fun absentManualOrStaleEvidenceCannotQualifyAccessibility() {
        val hash = "a".repeat(64)
        val records = Audit.requiredScreens.flatMap { screen -> Audit.requiredChecks.map { check ->
            Audit.Evidence(screen,check,Audit.Result.PASS,hash,"physical-device-identity","recording/$screen-$check")
        } }
        assertTrue(Audit.incomplete(records,hash).isEmpty())
        assertEquals(1,Audit.incomplete(records.dropLast(1),hash).size)
        assertEquals(records.size,Audit.incomplete(records,"b".repeat(64)).size)
        assertEquals(1,Audit.incomplete(records.mapIndexed { i,r -> if(i==0) r.copy(result=Audit.Result.NOT_RUN) else r },hash).size)
        assertEquals(1,Audit.incomplete(records+records.first(),hash).size)
    }
    @Test fun helperDoesNotTreatAnUnknownScreenAsPassing() {
        try { Audit.incomplete(listOf(Audit.Evidence("unknown","talkback",Audit.Result.PASS,"a".repeat(64),"phone","record")),"a".repeat(64)); fail() }
        catch (_: IllegalArgumentException) {}
    }
}
