package com.realityforge.scanner.agents.a071.check
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.ui.navigation.*
import com.realityforge.scanner.ui.processing.*
suspend fun checkActualDispatchBody(action:JobAction,model:ScannerViewModel,features:ScannerFeatureServices,visible:JobUiState,state:JobUiState,durable:JobUiState?,lastFocal:Double,navigate:(AppRoute)->Unit):Boolean = run {

                            val currentProject=model.projects.value.find {it.id==action.identity.projectId}
                            if(action.identity!=visible.identity || !visible.can(action.command) ||
                                model.selectedId.value!=action.identity.projectId || currentProject?.revision!=action.identity.revision) false
                            else if(durable!=null) features.jobAction?.invoke(action) ?: false
                            else if(action.identity!=state.identity || !state.can(action.command)) false
                            else when(action.command) {
                                JobCommand.PAUSE -> {
                                    val current=model.scannerState.value.activeOperation
                                    if(model.busy.value && current?.kind==OperationKind.RECONSTRUCT &&
                                        current.jobId.value==action.identity.jobId && current.source?.projectId==action.identity.projectId &&
                                        current.source.revision==action.identity.revision) {model.pause();true} else false
                                }
                                JobCommand.RETRY -> if(!model.busy.value && currentProject.state in setOf("FAILED","INTERRUPTED")) {
                                    model.reconstruct(lastFocal)
                                    model.busy.value // C1 leaves this false when reservation rejects the dispatch.
                                } else false
                                JobCommand.OPEN_PREVIOUS -> {
                                    val currentPreview=model.preview.value
                                    if(currentProject.resultRevision==currentProject.revision && currentProject.resultKey!=null &&
                                        currentPreview?.optString("artifactKey")==currentProject.resultKey) {
                                        navigate(AppRoute(Destination.STUDIO,action.identity.projectId));true
                                    } else false
                                }
                                else -> false
                            }

}
