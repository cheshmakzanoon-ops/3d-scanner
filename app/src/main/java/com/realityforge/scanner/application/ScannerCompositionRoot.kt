package com.realityforge.scanner.application

import android.app.Application
import androidx.room.Room
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Small process composition root. Domain work remains in services and the ViewModel adapter. */
class ScannerCompositionRoot private constructor(
    val database: ScanDatabase,
    val repository: ProjectRepository,
    val dispatchers: ScannerDispatchers,
    val execution: com.realityforge.scanner.work.execution.ReconstructionExecutionController,
) {
    val curation get() = repository.curation
    val cacheService get() = repository.cacheService
    val storageBudget get() = requireNotNull(repository.storageBudget)
    val publications get() = requireNotNull(repository.publications)

    companion object {
        fun create(application: Application): ScannerCompositionRoot {
            val database = Room.databaseBuilder(
                application,
                ScanDatabase::class.java,
                "realityforge.db",
            ).build()
            val repository = ProjectRepository(application, database.dao(), database = database)
            return ScannerCompositionRoot(
                database = database,
                repository = repository,
                dispatchers = ScannerDispatchers(),
                execution = com.realityforge.scanner.work.execution.ReconstructionExecutionController(application, database, repository),
            )
        }
    }
}

data class ScannerDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val compute: CoroutineDispatcher = Dispatchers.Default,
)
