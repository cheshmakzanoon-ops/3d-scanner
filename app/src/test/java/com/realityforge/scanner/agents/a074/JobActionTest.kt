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
    @Test fun acceptedMutationBlocksAllRepeatedActionsUntilThePersistedVersionChanges() {
        val gate=JobActionGate()
        val state=JobUiState(id,JobPhase.RUNNING,cancelSupported=true)
        val pause=JobAction(id,JobCommand.PAUSE)
        assertTrue(gate.admit(state,pause))
        gate.complete(pause,true)
        assertTrue(gate.isPending(id))
        assertFalse(gate.admit(state,pause))
        assertFalse(gate.admit(state,JobAction(id,JobCommand.CANCEL)))
        val updated=state.copy(identity=id.copy(stateVersion=1))
        assertTrue(gate.admit(updated,JobAction(updated.identity,JobCommand.CANCEL)))
    }
    @Test fun rejectedOrCancelledDispatchCanRetryButAnOldCompletionCannotClearANewAttempt() {
        val gate=JobActionGate()
        val state=JobUiState(id,JobPhase.RUNNING)
        val action=JobAction(id,JobCommand.PAUSE)
        assertTrue(gate.admit(state,action))
        gate.complete(action,false)
        assertFalse(gate.isPending(id))
        assertTrue(gate.admit(state,action))
        val resumed=state.copy(identity=id.copy(attempt=1,stateVersion=2))
        val next=JobAction(resumed.identity,JobCommand.PAUSE)
        assertTrue(gate.admit(resumed,next))
        gate.complete(action,false)
        assertTrue(gate.isPending(resumed.identity))
        assertFalse(gate.admit(resumed,next))
    }
    @Test fun completedNavigationDoesNotLatchAnUnchangedJobSnapshot() {
        val gate=JobActionGate()
        val state=JobUiState(id,JobPhase.INTERRUPTED,hasPreviousResult=true,notificationsBlocked=true)
        for(command in listOf(JobCommand.OPEN_PREVIOUS,JobCommand.NOTIFICATION_SETTINGS)) {
            val action=JobAction(id,command)
            assertTrue(gate.admit(state,action))
            gate.complete(action,true)
            assertFalse(gate.isPending(id))
        }
    }
}
