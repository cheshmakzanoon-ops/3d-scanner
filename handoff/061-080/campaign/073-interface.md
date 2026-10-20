package com.realityforge.scanner.ui.photoreview
import com.realityforge.scanner.data.ScanPhoto
import java.io.File

data class ReviewPhoto(val id:String,val filename:String,val width:Int,val height:Int,val bytes:Long,
    val sharpness:Double?,val darkFraction:Double?,val brightFraction:Double?,val focal35mm:Double?,
    val included:Boolean=true,val file:File?=null,val calibrationCompatible:Boolean?=null) {
    companion object { fun from(photo:ScanPhoto,file:File?=null)=ReviewPhoto(photo.id,photo.filename,photo.width,photo.height,photo.bytes,
        photo.sharpness.takeIf(Double::isFinite),photo.darkFraction.takeIf { it.isFinite() && it in 0.0..1.0 },photo.brightFraction.takeIf { it.isFinite() && it in 0.0..1.0 },photo.focal35mm,file=file) }
}
data class ProjectReviewUiState(val projectId:String,val revision:Long,val photos:List<ReviewPhoto>,
    val title:String,val busy:Boolean=false,val calibrationStatus:String?=null,val diagnostics:List<String> = emptyList(),
    val canCurate:Boolean=false,val canUndo:Boolean=false,val canCalibrate:Boolean=true,
    val canInspectResult:Boolean=false,val canReconstruct:Boolean=true) {
    init { require(projectId.isNotBlank() && revision>=0); require(photos.map { it.id }.distinct().size==photos.size) }
    val selectedCount:Int get()=photos.count { it.included }
    fun canStart(focal:Double?)=canReconstruct && !busy && selectedCount>=2 && focal!=null && focal.isFinite() && focal in 10.0..200.0
    fun photo(id:String)=photos.find { it.id==id }
}
enum class ReviewCommand { CAPTURE, IMPORT, RECONSTRUCT, CALIBRATE, CLEAR_CALIBRATION, OPEN_RESULT, HELP, UNDO }
sealed interface ReviewAction {
    val projectId:String
    val revision:Long
    data class Command(override val projectId:String,override val revision:Long,val command:ReviewCommand,val focal35mm:Double?=null):ReviewAction
    data class Include(override val projectId:String,override val revision:Long,val photoId:String,val included:Boolean):ReviewAction
    data class Move(override val projectId:String,override val revision:Long,val photoId:String,val offset:Int):ReviewAction
    data class Remove(override val projectId:String,override val revision:Long,val photoId:String):ReviewAction
}
object ReviewAdmission {
    fun allowed(state:ProjectReviewUiState,action:ReviewAction):Boolean {
        if(state.busy || action.projectId!=state.projectId || action.revision!=state.revision) return false
        return when(action) {
            is ReviewAction.Include->state.canCurate && state.photo(action.photoId)!=null
            is ReviewAction.Remove->state.canCurate && state.photo(action.photoId)!=null
            is ReviewAction.Move->state.canCurate && action.offset in setOf(-1,1) && state.photos.indexOfFirst { it.id==action.photoId }.let { it>=0 && it+action.offset in state.photos.indices }
            is ReviewAction.Command->when(action.command) {
                ReviewCommand.RECONSTRUCT->state.canStart(action.focal35mm)
                ReviewCommand.CALIBRATE,ReviewCommand.CLEAR_CALIBRATION->state.canCalibrate
                ReviewCommand.OPEN_RESULT->state.canInspectResult
                ReviewCommand.UNDO->state.canCurate && state.canUndo
                else->true
            }
        }
    }
}
