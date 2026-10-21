package com.realityforge.scanner.data.jobs

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * A real Room database used by Agent 007's isolated fixture and downstream contract tests.
 * The production app must register these entities and [ReconstructionJobDao] in ScanDatabase
 * through Agent 005's additive migration; this class is never opened by application startup.
 */
@Database(
    entities = [ReconstructionJobEntity::class, ReconstructionJobEventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class IsolatedJobStoreDatabase : RoomDatabase() {
    abstract fun jobDao(): ReconstructionJobDao
}
