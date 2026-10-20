package com.realityforge.scanner.ui.navigation

import com.realityforge.scanner.data.curation.PhotoCurationService
import com.realityforge.scanner.data.curation.PhotoRemovalDirective
import com.realityforge.scanner.data.curation.ReconstructionSelectionLease
import com.realityforge.scanner.data.library.ProjectLibraryService
import com.realityforge.scanner.ui.photoreview.ReviewAction
import com.realityforge.scanner.ui.processing.JobAction
import com.realityforge.scanner.ui.processing.JobUiState
import kotlinx.coroutines.flow.Flow

/** Providers are installed by001 only after their transactional/engine owners are integrated.
 * No default creates a second database, fake command, optimistic mutation or background worker.
 */
data class ScannerFeatureServices(
    val library:ProjectLibraryService?=null,
    val curation:PhotoCurationService?=null,
    val removePhotos:(suspend(List<PhotoRemovalDirective>)->Unit)?=null,
    val reconstructSelection:((ReviewAction.Command,ReconstructionSelectionLease)->Unit)?=null,
    val jobStates:((projectId:String,revision:Long)->Flow<JobUiState?>)?=null,
    val jobAction:(suspend(JobAction)->Boolean)?=null,
)
