package com.realityforge.scanner.ui.photoreview
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.realityforge.scanner.data.curation.ReconstructionAdmission
import com.realityforge.scanner.data.curation.ReconstructionSelectionLease
import kotlinx.coroutines.launch

/** Optional real-service binding; construction requires the application's transactional curation store. */
@Composable
fun CurationReviewScreen(presenter:PhotoCurationPresenter,metadata:ProjectReviewUiState,
    onHostAction:(ReviewAction)->Unit,onReconstruct:(ReviewAction.Command,ReconstructionSelectionLease)->Unit,
    modifier:Modifier=Modifier,fallbackFocal35mm:Double=26.0) {
    val state by presenter.state.collectAsState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(metadata.projectId,metadata.revision,metadata.photos) { presenter.attach(metadata) }
    val selected=state.review?.takeIf { it.projectId==metadata.projectId } ?: metadata.copy(busy=state.failure==null,canCurate=false,canReconstruct=false)
    val visible=state.failure?.let { selected.copy(diagnostics=listOf(it)+selected.diagnostics) } ?: selected
    ProjectReviewScreen(visible,onAction={action ->
        when {
            action is ReviewAction.Command && action.command==ReviewCommand.RECONSTRUCT -> scope.launch {
                val admitted=presenter.reconstructionAdmission()
                if(admitted is ReconstructionAdmission.Ready) onReconstruct(action,admitted.lease)
            }
            action !is ReviewAction.Command || action.command==ReviewCommand.UNDO -> scope.launch { presenter.execute(action) }
            else->onHostAction(action)
        }
    },modifier=modifier,fallbackFocal35mm=fallbackFocal35mm)
}
