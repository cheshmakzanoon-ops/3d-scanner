package com.realityforge.scanner.ui.photoreview

import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

data class CurationReviewState(val review:ProjectReviewUiState?=null,val failure:String?=null,
    val selected:SelectedPhotoSet?=null)
/** Uses Agent018's committed snapshots, never an optimistic copy of a checkbox or drag order. */
class PhotoCurationPresenter(private val service:PhotoCurationService,
    private val failureMessage:(PhotoCurationFailure)->String,
    private val unexpectedFailure:(Throwable)->String,
    private val removalSink:(suspend(List<PhotoRemovalDirective>)->Unit)?=null,
    private val selectionAwareReconstruction:Boolean=false) {
    private val mutable=MutableStateFlow(CurationReviewState())
    val state:StateFlow<CurationReviewState> = mutable.asStateFlow()
    private val commands=Mutex()
    private var current:PhotoCurationState?=null
    private var base:ProjectReviewUiState?=null
    private var undo:CurationUndoToken?=null
    suspend fun attach(metadata:ProjectReviewUiState) {
        commands.lock()
        try {
            val previous=current
            val sameOriginals=previous!=null && previous.projectId==metadata.projectId &&
                previous.photos.map { it.id to it.sha256 }.toSet()==metadata.photos.map { it.id to it.sha256 }.toSet()
            // A lagging Room emission after our commit must not rewind the displayed revision.
            if(sameOriginals && metadata.revision<previous!!.revision.projectRevision) return
            val preserveCandidate=sameOriginals && metadata.revision==previous!!.revision.projectRevision
            base=metadata
            if(!preserveCandidate) { current=null;undo=null;mutable.value=CurationReviewState() }
            val committed=service.state(metadata.projectId)?:error("Curation project is unavailable")
            // A same-revision refresh preserves immediate undo only for the exact committed state.
            if(!preserveCandidate || committed.revision!=previous!!.revision || committed.photos!=previous.photos) undo=null
            apply(committed)
        }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { mutable.update { it.copy(failure=unexpectedFailure(error)) } }
        finally { commands.unlock() }
    }
    private suspend fun reload() {
        val metadata=base?:return
        val committed=service.state(metadata.projectId)?:error("Curation project is unavailable")
        apply(committed)
    }
    private fun apply(committed:PhotoCurationState) {
        val metadata=base?:return
        require(committed.projectId==metadata.projectId)
        val rows=metadata.photos.associateBy { it.id }
        val mapped=committed.photos.map { photo ->
            val row=rows[photo.id]?:error("Committed photo metadata is missing")
            require(row.sha256==photo.sha256) { "Committed original identity differs from metadata" }
            row.copy(included=photo.state==CuratedPhotoState.INCLUDED,pendingRemoval=photo.state==CuratedPhotoState.REMOVAL_PENDING)
        }
        current=committed
        mutable.value=CurationReviewState(metadata.copy(revision=committed.revision.projectRevision,photos=mapped,
            canCurate=true,canRemove=removalSink!=null,canUndo=undo!=null,canReconstruct=metadata.canReconstruct && selectionAwareReconstruction),selected=committed.selectedPhotoSet)
    }
    suspend fun execute(action:ReviewAction):Boolean {
        if(!commands.tryLock()) return false
        try {
            val visible=mutable.value.review?:return false
            val snapshot=current?:return false
            if(!ReviewAdmission.allowed(visible,action)) return false
            val requestId=UUID.randomUUID().toString();val revision=snapshot.revision
            val command=when(action) {
                is ReviewAction.Include->if(action.included) PhotoCurationCommand.Include(action.projectId,revision,requestId,action.photoId)
                    else PhotoCurationCommand.Exclude(action.projectId,revision,requestId,action.photoId,PhotoCurationReason(PhotoExclusionCode.USER_CHOICE))
                is ReviewAction.Move->{ val ids=snapshot.photos.map { it.id }.toMutableList();val index=ids.indexOf(action.photoId);val target=index+action.offset
                    if(index<0 || target !in ids.indices)return false
                    val id=ids.removeAt(index);ids.add(target,id);PhotoCurationCommand.Reorder(action.projectId,revision,requestId,ids) }
                is ReviewAction.Remove->PhotoCurationCommand.RequestRemoval(action.projectId,revision,requestId,action.photoId,PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST))
                is ReviewAction.Restore->PhotoCurationCommand.RestorePendingRemoval(action.projectId,revision,requestId,action.photoId)
                is ReviewAction.Command->if(action.command==ReviewCommand.UNDO) PhotoCurationCommand.Undo(action.projectId,revision,requestId,undo?:return false) else return false
            }
            mutable.update { it.copy(review=visible.copy(busy=true),failure=null) }
            return when(val outcome=service.execute(command)) {
                is PhotoCurationOutcome.Applied->{ undo=outcome.undoToken
                    // Reload from the committed service state before processing deletion-owner directives.
                    reload()
                    if(outcome.removalDirectives.isNotEmpty()) checkNotNull(removalSink).invoke(outcome.removalDirectives)
                    true }
                is PhotoCurationOutcome.Unchanged->{ reload();true }
                is PhotoCurationOutcome.Rejected->{
                    if(outcome.failure is PhotoCurationFailure.RevisionConflict) { undo=null;reload() }
                    else mutable.update { it.copy(review=it.review?.copy(busy=false)) }
                    mutable.update { it.copy(failure=failureMessage(outcome.failure)) };false }
            }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { mutable.update { it.copy(failure=unexpectedFailure(error)) };return false }
        finally { mutable.update { it.copy(review=it.review?.copy(busy=false)) };commands.unlock() }
    }
    suspend fun reconstructionAdmission():ReconstructionAdmission? {
        val visible=mutable.value.review?:return null
        val committed=service.selectedPhotos(visible.projectId)?:return null
        if(committed.revision.projectRevision!=visible.revision) { undo=null;reload();return null }
        return committed.admitForReconstruction()
    }
}
