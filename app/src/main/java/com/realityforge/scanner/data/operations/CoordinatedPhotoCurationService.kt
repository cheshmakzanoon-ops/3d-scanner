package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.curation.*

/** Room CAS and the reconstruction/deletion exclusion cover the same curation command. */
class CoordinatedPhotoCurationService(
    private val repository: ProjectRepository,
    private val delegate: PhotoCurationService,
) : PhotoCurationService {
    override suspend fun state(projectId: String): PhotoCurationState? = repository.withProjectAccess(
        ProjectOperationRequest.create(projectId, ProjectOperationKind.READ_INPUTS)) { delegate.state(projectId) }

    override suspend fun selectedPhotos(projectId: String): SelectedPhotoSet? = repository.withProjectAccess(
        ProjectOperationRequest.create(projectId, ProjectOperationKind.READ_INPUTS)) { delegate.selectedPhotos(projectId) }

    override suspend fun execute(command: PhotoCurationCommand): PhotoCurationOutcome = repository.withProjectAccess(
        ProjectOperationRequest.create(command.projectId, ProjectOperationKind.MUTATE_INPUTS,
            command.expectedRevision.projectRevision)) { delegate.execute(command) }
}
