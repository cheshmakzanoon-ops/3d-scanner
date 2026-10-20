package com.realityforge.scanner.agents.a074
import com.realityforge.scanner.ui.processing.*
import org.junit.Assert.*
import org.junit.Test
class JobActionTest {
    private val id=JobIdentity("p",4,"job")
    @Test fun unknownTotalsNeverPretendToBePercentComplete() {
        assertNull(JobUiState(id,JobPhase.RUNNING,completed=3,total=null).stageFraction)
        assertNull(JobUiState(id,JobPhase.RUNNING,completed=3,total=2).stageFraction)
        assertNull(JobUiState(id,JobPhase.RUNNING,completed=0,total=0).stageFraction)
        assertEquals(.5f,JobUiState(id,JobPhase.RUNNING,completed=1,total=2).stageFraction!!,0f)
    }
    @Test fun staleAndDuplicateActionsAreRejected() {
        val state=JobUiState(id,JobPhase.RUNNING)
        val gate=JobActionGate(); val action=JobAction(id,JobCommand.PAUSE)
        assertFalse(gate.admit(state,action.copy(identity=id.copy(revision=3))))
        assertTrue(gate.admit(state,action)); assertFalse(gate.admit(state,action))
        gate.acknowledge(action); assertTrue(gate.admit(state,action))
    }
    @Test fun changedInputsCannotResumeOrRetryOldJob() {
        val state=JobUiState(id,JobPhase.INTERRUPTED,resumeSupported=true,inputsCurrent=false,hasPreviousResult=true)
        assertFalse(state.can(JobCommand.RESUME)); assertFalse(state.can(JobCommand.RETRY)); assertTrue(state.can(JobCommand.OPEN_PREVIOUS))
    }
    @Test fun baselineHasNoUnimplementedCancelOrResumeButton() {
        assertFalse(JobUiState(id,JobPhase.PAUSED).can(JobCommand.RESUME))
        assertFalse(JobUiState(id,JobPhase.RUNNING).can(JobCommand.CANCEL))
    }
}
