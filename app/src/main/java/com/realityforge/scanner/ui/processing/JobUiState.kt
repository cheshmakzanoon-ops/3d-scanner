package com.realityforge.scanner.ui.processing

enum class JobPhase { QUEUED, RUNNING, PAUSED, INTERRUPTED, FAILED, CANCELLED, COMPLETED, STALE, BLOCKED_EXTERNAL }
data class JobIdentity(val projectId: String, val revision: Long, val jobId: String, val attempt:Long=0,val stateVersion:Long=0) {
    init { require(projectId.isNotBlank() && revision >= 0 && jobId.isNotBlank() && attempt>=0 && stateVersion>=0) }
}
enum class JobCommand { PAUSE, RESUME, RETRY, CANCEL, OPEN_PREVIOUS, NOTIFICATION_SETTINGS }
data class JobAction(val identity: JobIdentity, val command: JobCommand)
data class JobUiState(val identity: JobIdentity, val phase: JobPhase, val stage: String? = null,
    val completed: Long? = null, val total: Long? = null, val reusedStages: Int? = null,
    val message: String? = null, val technicalDetails: String? = null,
    val settingsIdentity: String? = null, val resourceConstraint: String? = null,
    val hasPreviousResult: Boolean = false, val inputsCurrent: Boolean = true,
    val resumeSupported: Boolean = false, val pauseSupported: Boolean = true,
    val cancelSupported: Boolean = false, val notificationsBlocked: Boolean = false) {
    /** Stage completion is not whole-pipeline completion and unknown totals remain indeterminate. */
    val stageFraction: Float? get() = if (completed != null && total != null && total > 0 && completed in 0..total)
        (completed.toDouble()/total).toFloat() else null
    fun can(command: JobCommand): Boolean = when(command) {
        JobCommand.PAUSE -> pauseSupported && phase in setOf(JobPhase.QUEUED,JobPhase.RUNNING)
        JobCommand.RESUME -> resumeSupported && inputsCurrent && phase in setOf(JobPhase.PAUSED,JobPhase.INTERRUPTED)
        JobCommand.RETRY -> inputsCurrent && phase in setOf(JobPhase.FAILED,JobPhase.BLOCKED_EXTERNAL,JobPhase.INTERRUPTED)
        JobCommand.CANCEL -> cancelSupported && phase in setOf(JobPhase.QUEUED,JobPhase.RUNNING,JobPhase.PAUSED)
        JobCommand.OPEN_PREVIOUS -> hasPreviousResult && phase != JobPhase.RUNNING
        JobCommand.NOTIFICATION_SETTINGS -> notificationsBlocked
    }
}
/** Reject repeated/stale UI commands before dispatch; durable owner still arbitrates actual mutation. */
class JobActionGate {
    private var pending: JobAction? = null
    @Synchronized fun admit(state: JobUiState, action: JobAction): Boolean {
        if (pending?.identity != state.identity) pending = null
        if (action.identity != state.identity || !state.can(action.command) || pending != null) return false
        pending = action; return true
    }
    @Synchronized fun acknowledge(action: JobAction) { if (pending == action) pending = null }
    /** An accepted job mutation remains pending until a new persisted identity/version arrives.
     * Navigation does not mutate a job and can finish against the same snapshot.
     */
    @Synchronized fun complete(action: JobAction, accepted: Boolean) {
        if (!accepted || action.command == JobCommand.OPEN_PREVIOUS || action.command == JobCommand.NOTIFICATION_SETTINGS)
            acknowledge(action)
    }
    @Synchronized fun isPending(identity: JobIdentity): Boolean = pending?.identity == identity
}
