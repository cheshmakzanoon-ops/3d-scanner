package com.realityforge.scanner.data.curation

import java.util.UUID

interface PhotoCurationService {
    suspend fun state(projectId: String): PhotoCurationState?
    suspend fun selectedPhotos(projectId: String): SelectedPhotoSet?
    suspend fun execute(command: PhotoCurationCommand): PhotoCurationOutcome
}

class DefaultPhotoCurationService(
    internal val store: PhotoCurationStore,
    internal val clockMillis: () -> Long = System::currentTimeMillis,
    internal val removalRequestId: () -> String = { UUID.randomUUID().toString() },
    internal val undoTokenId: () -> String = { UUID.randomUUID().toString() },
) : PhotoCurationService {
    override suspend fun state(projectId: String): PhotoCurationState? {
        stableId(projectId, "project"); return store.read(projectId)?.toPhotoCurationState()
    }
    override suspend fun selectedPhotos(projectId: String): SelectedPhotoSet? {
        stableId(projectId, "project"); return store.read(projectId)?.toSelectedPhotoSet()
    }
    override suspend fun execute(command: PhotoCurationCommand) = executeCuration(this, command)
}
