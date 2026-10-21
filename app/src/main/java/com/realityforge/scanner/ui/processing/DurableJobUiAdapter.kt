package com.realityforge.scanner.ui.processing

import com.realityforge.scanner.data.jobs.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** C7 must atomically admit this full identity. A job-ID-only command cannot fence a new attempt. */
fun interface JobActionExecutor { suspend fun execute(action:JobAction):Boolean }
class DurableJobUiAdapter(private val store:JobStore,private val executor:JobActionExecutor,
    private val stageLabel:(ReconstructionStage)->String,private val outcomeLabel:(String)->String) {
    fun observe(jobId:JobId,currentRevision:Long,hasPreviousResult:Boolean=false):Flow<JobUiState?> =
        store.observe(jobId).map { snapshot -> snapshot?.let { map(it,currentRevision,hasPreviousResult,stageLabel,outcomeLabel) } }
    suspend fun dispatch(action:JobAction,currentRevision:Long):Boolean {
        val snapshot=store.snapshot(JobId(action.identity.jobId))?:return false
        val visible=map(snapshot,currentRevision,false,stageLabel,outcomeLabel)
        if(visible.identity!=action.identity || !visible.can(action.command)) return false
        // Execution owner rechecks the same attempt/version in its atomic admission boundary.
        return executor.execute(action)
    }
    companion object {
        fun map(snapshot:JobSnapshot,currentRevision:Long,hasPreviousResult:Boolean,
            stageLabel:(ReconstructionStage)->String,outcomeLabel:(String)->String):JobUiState {
            val phase=when(snapshot.state) {
                ReconstructionJobState.QUEUED->JobPhase.QUEUED; ReconstructionJobState.RUNNING->JobPhase.RUNNING
                ReconstructionJobState.PAUSED->JobPhase.PAUSED; ReconstructionJobState.COMPLETED->JobPhase.COMPLETED
                ReconstructionJobState.FAILED->JobPhase.FAILED; ReconstructionJobState.CANCELLED->JobPhase.CANCELLED
                ReconstructionJobState.STALE->JobPhase.STALE; ReconstructionJobState.BLOCKED_EXTERNAL->JobPhase.BLOCKED_EXTERNAL
            }
            val current=snapshot.input.projectRevision==currentRevision && snapshot.state!=ReconstructionJobState.STALE
            return JobUiState(JobIdentity(snapshot.input.projectId,snapshot.input.projectRevision,snapshot.jobId.value,snapshot.attempt,snapshot.stateVersion),phase,
                stage=stageLabel(snapshot.progress?.stage?:snapshot.cursor.acceptedStage),completed=snapshot.progress?.completedUnits,total=snapshot.progress?.totalUnits,
                message=snapshot.outcomeCode?.let(outcomeLabel),settingsIdentity=snapshot.input.settingsSha256,
                technicalDetails="inputSha256=${snapshot.input.inputSha256}\nengine=${snapshot.input.engineVersion}\nengineSha256=${snapshot.input.engineSha256}\nacceptedStage=${snapshot.cursor.acceptedStage}\nprogressUnit=${snapshot.progress?.unit}\nattempt=${snapshot.attempt}",
                hasPreviousResult=hasPreviousResult,inputsCurrent=current,resumeSupported=current && phase==JobPhase.PAUSED,
                pauseSupported=phase in setOf(JobPhase.RUNNING,JobPhase.QUEUED),cancelSupported=phase in setOf(JobPhase.RUNNING,JobPhase.QUEUED,JobPhase.PAUSED))
        }
    }
}
