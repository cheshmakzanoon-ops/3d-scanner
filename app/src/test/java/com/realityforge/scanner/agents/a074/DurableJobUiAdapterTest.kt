package com.realityforge.scanner.agents.a074
import com.realityforge.scanner.ui.processing.*
import com.realityforge.scanner.data.jobs.*
import org.junit.Assert.*
import org.junit.Test
class DurableJobUiAdapterTest {
    private val hash="a".repeat(64)
    private fun snapshot(state:ReconstructionJobState):JobSnapshot {
        val job=JobId("job");val running=state==ReconstructionJobState.RUNNING
        return JobSnapshot(job,hash,JobInputSnapshot("p",4,"request",hash,hash,hash,hash,"test-v1"),state,JobStageCursor.initial(),if(running)1 else 0,
            if(running)JobLease(job,1,"token","worker",100,7) else null,
            if(state==ReconstructionJobState.COMPLETED)"result" else null,if(state==ReconstructionJobState.COMPLETED)hash else null,null,
            JobProgress(ReconstructionStage.CREATED,1,null,JobProgressUnit.PHOTOS),5,0,10,7)
    }
    @Test fun everyDurableStateRemainsDistinctAndUnknownTotalsStayIndeterminate() {
        val states=ReconstructionJobState.entries.map { DurableJobUiAdapter.map(snapshot(it),4,true,{it.name},{it}) }
        assertEquals(ReconstructionJobState.entries.size,states.map {it.phase}.distinct().size)
        assertTrue(states.all {it.stageFraction==null})
        assertFalse(states.single {it.phase==JobPhase.CANCELLED}.can(JobCommand.RETRY))
        assertTrue(states.single {it.phase==JobPhase.BLOCKED_EXTERNAL}.can(JobCommand.RETRY))
    }
    @Test fun attemptAndVersionFenceSurvivesAChangedWorkerAndProjectRevision() {
        val state=DurableJobUiAdapter.map(snapshot(ReconstructionJobState.RUNNING),5,false,{it.name},{it})
        assertEquals(1L,state.identity.attempt);assertEquals(7L,state.identity.stateVersion)
        assertFalse(state.inputsCurrent);assertFalse(state.can(JobCommand.RETRY))
        val gate=JobActionGate();assertFalse(gate.admit(state,JobAction(state.identity.copy(attempt=2),JobCommand.PAUSE)))
    }
}
