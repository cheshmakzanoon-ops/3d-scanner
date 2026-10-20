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
) {
    companion object {
        fun create(application: Application): ScannerCompositionRoot {
            val database = Room.databaseBuilder(
                application,
                ScanDatabase::class.java,
                "realityforge.db",
            ).build()
            return ScannerCompositionRoot(
                database = database,
                repository = ProjectRepository(application, database.dao()),
                dispatchers = ScannerDispatchers(),
            )
        }
    }
}

data class ScannerDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val compute: CoroutineDispatcher = Dispatchers.Default,
)
