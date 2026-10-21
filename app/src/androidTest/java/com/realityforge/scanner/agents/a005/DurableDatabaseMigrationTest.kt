package com.realityforge.scanner.agents.a005

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ReconstructionJobEntity
import com.realityforge.scanner.data.ReconstructionJobState
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanProject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ScanDatabase::class.java,
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanBefore() = clean()

    @After
    fun cleanAfter() = clean()

    @Test
    fun version1UpgradePreservesRowsHashesFilesAndCompletedResultAcrossRestart() {
        val projectId = "agent005-v1-project"
        val resultKey = "sparse-m19-r3-0123456789abcdef"
        val originalA = "original-a".toByteArray()
        val originalB = "original-b".toByteArray()
        val calibration = """{"schema":1,"camera":"fixture"}""".toByteArray()
        val completedResult = """{"revision":3,"artifactKey":"$resultKey"}""".toByteArray()

        createVersion1Fixture(
            MIGRATION_DB,
            projectId,
            resultKey,
            sha256(originalA),
            sha256(originalB),
        )

        val projectDirectory = File(fixtureRoot(), projectId)
        val originalsDirectory = File(projectDirectory, "originals").apply { mkdirs() }
        val originalAFile = File(originalsDirectory, "photo-a.image").apply { writeBytes(originalA) }
        val originalBFile = File(originalsDirectory, "photo-b.image").apply { writeBytes(originalB) }
        val calibrationFile = File(projectDirectory, "camera-calibration-v1.json").apply {
            parentFile!!.mkdirs()
            writeBytes(calibration)
        }
        val resultFile = File(projectDirectory, "$resultKey.json").apply { writeBytes(completedResult) }

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 2, true).use { migrated ->
            migrated.query(
                "SELECT revision,state,resultRevision,resultKey FROM projects WHERE id = ?",
                arrayOf(projectId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals("SPARSE_READY", cursor.getString(1))
                assertEquals(3L, cursor.getLong(2))
                assertEquals(resultKey, cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            migrated.query(
                "SELECT id,sha256,included,selectionOrder FROM photos " +
                    "WHERE projectId = ? ORDER BY selectionOrder",
                arrayOf(projectId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("photo-a", cursor.getString(0))
                assertEquals(sha256(originalA), cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(0L, cursor.getLong(3))
                assertTrue(cursor.moveToNext())
                assertEquals("photo-b", cursor.getString(0))
                assertEquals(sha256(originalB), cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(1L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            migrated.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                    "('capture_sessions','reconstruction_jobs','artifacts','edit_recipes','deletion_journals')",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
            migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }

        Room.databaseBuilder(context, ScanDatabase::class.java, MIGRATION_DB).build().use { reopened ->
            runBlocking {
                val project = reopened.dao().project(projectId)!!
                assertEquals(3L, project.revision)
                assertEquals(resultKey, project.resultKey)
                val photos = reopened.dao().photos(projectId)
                assertEquals(listOf("photo-a", "photo-b"), photos.map { it.id })
                assertEquals(listOf(sha256(originalA), sha256(originalB)), photos.map { it.sha256 })
            }
            reopened.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        }

        assertArrayEquals(originalA, originalAFile.readBytes())
        assertArrayEquals(originalB, originalBFile.readBytes())
        assertArrayEquals(calibration, calibrationFile.readBytes())
        assertArrayEquals(completedResult, resultFile.readBytes())
    }

    @Test
    fun interruptedUpgradeTransactionRollsBackAndLeavesVersion1Recoverable() {
        val projectId = "agent005-interrupted-project"
        createVersion1Fixture(INTERRUPTED_DB, projectId, null, "hash-a", "hash-b")

        val file = context.getDatabasePath(INTERRUPTED_DB)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.beginTransaction()
            try {
                raw.execSQL(
                    "ALTER TABLE projects ADD COLUMN interruptedMarker INTEGER NOT NULL DEFAULT 0",
                )
                raw.execSQL(
                    "UPDATE projects SET message='partial-upgrade-write' WHERE id=?",
                    arrayOf(projectId),
                )
                raw.execSQL("PRAGMA user_version = 2")
                // No setTransactionSuccessful(): this models process death before publication.
            } finally {
                raw.endTransaction()
            }
        }

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { recovered ->
            recovered.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            recovered.rawQuery("SELECT message FROM projects WHERE id=?", arrayOf(projectId)).use {
                assertTrue(it.moveToFirst())
                assertEquals("legacy-message", it.getString(0))
            }
            recovered.rawQuery("PRAGMA table_info(projects)", null).use { cursor ->
                var markerFound = false
                while (cursor.moveToNext()) {
                    markerFound = markerFound ||
                        cursor.getString(cursor.getColumnIndexOrThrow("name")) == "interruptedMarker"
                }
                assertFalse(markerFound)
            }
        }

        migrationHelper.runMigrationsAndValidate(INTERRUPTED_DB, 2, true).use { migrated ->
            migrated.query("SELECT id,revision FROM projects WHERE id=?", arrayOf(projectId)).use {
                assertTrue(it.moveToFirst())
                assertEquals(projectId, it.getString(0))
                assertEquals(3L, it.getLong(1))
            }
            migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun runningLeasePersistsAcrossRestartAndExpiredWorkIsReclaimedByAttempt() = runBlocking {
        Room.databaseBuilder(context, ScanDatabase::class.java, RESTART_DB).build().use { first ->
            first.dao().insert(ScanProject("restart-project", "Restart fixture", 1, revision = 7))
            val job = ReconstructionJobEntity(
                id = "restart-job",
                projectId = "restart-project",
                inputRevision = 7,
                identitySha256 = "restart-job-identity",
                settingsSha256 = "restart-settings",
                engineVersion = "engine-v1",
                createdAt = 100,
                updatedAt = 100,
            )
            assertTrue(first.durableStateDao().createJobForCurrentRevision(job))
            assertEquals(
                1,
                first.durableStateDao().tryAcquireJob(
                    job.id,
                    0,
                    "lease-before-restart",
                    "worker-before-restart",
                    100,
                    200,
                ),
            )
        }

        Room.databaseBuilder(context, ScanDatabase::class.java, RESTART_DB).build().use { second ->
            val persisted = second.durableStateDao().job("restart-job")!!
            assertEquals(ReconstructionJobState.RUNNING, persisted.stateCode)
            assertEquals(1L, persisted.attempt)
            assertEquals("lease-before-restart", persisted.leaseToken)
            assertEquals(200L, persisted.leaseExpiresAt)
            assertEquals(
                1,
                second.durableStateDao().tryAcquireJob(
                    persisted.id,
                    1,
                    "lease-after-restart",
                    "worker-after-restart",
                    201,
                    500,
                ),
            )
            val reclaimed = second.durableStateDao().job(persisted.id)!!
            assertEquals(2L, reclaimed.attempt)
            assertEquals("lease-after-restart", reclaimed.leaseToken)
            assertEquals(
                0,
                second.durableStateDao().renewJobLease(
                    reclaimed.id,
                    1,
                    "lease-before-restart",
                    220,
                    600,
                ),
            )
            assertEquals(
                1,
                second.durableStateDao().renewJobLease(
                    reclaimed.id,
                    2,
                    "lease-after-restart",
                    220,
                    600,
                ),
            )
            assertEquals(
                1,
                second.durableStateDao().finishJobWithFailure(
                    reclaimed.id,
                    2,
                    "lease-after-restart",
                    ReconstructionJobState.FAILED,
                    "PROCESS_RESTART_FIXTURE",
                    230,
                ),
            )
        }

        Room.databaseBuilder(context, ScanDatabase::class.java, RESTART_DB).build().use { third ->
            val terminal = third.durableStateDao().job("restart-job")!!
            assertEquals(ReconstructionJobState.FAILED, terminal.stateCode)
            assertEquals(2L, terminal.attempt)
            assertEquals("PROCESS_RESTART_FIXTURE", terminal.terminalCode)
            assertNull(terminal.leaseToken)
        }
    }

    private fun createVersion1Fixture(
        databaseName: String,
        projectId: String,
        resultKey: String?,
        originalAHash: String,
        originalBHash: String,
    ) {
        migrationHelper.createDatabase(databaseName, 1).use { legacy ->
            legacy.execSQL("PRAGMA foreign_keys = ON")
            legacy.execSQL(
                "INSERT INTO projects " +
                    "(id,name,createdAt,revision,state,message,resultRevision,resultKey) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(
                    projectId,
                    "Legacy fixture",
                    10L,
                    3L,
                    if (resultKey == null) "READY" else "SPARSE_READY",
                    "legacy-message",
                    if (resultKey == null) null else 3L,
                    resultKey,
                ),
            )
            insertLegacyPhoto(legacy, "photo-a", projectId, originalAHash)
            insertLegacyPhoto(legacy, "photo-b", projectId, originalBHash)
        }
    }

    private fun insertLegacyPhoto(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        projectId: String,
        hash: String,
    ) {
        database.execSQL(
            "INSERT INTO photos " +
                "(id,projectId,filename,sha256,bytes,width,height,focal35mm," +
                "sharpness,darkFraction,brightFraction,addedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                id,
                projectId,
                "$id.image",
                hash,
                10L,
                100,
                80,
                50.0,
                1.0,
                0.0,
                0.0,
                100L,
            ),
        )
    }

    private fun clean() {
        DATABASES.forEach { context.deleteDatabase(it) }
        fixtureRoot().deleteRecursively()
    }

    private fun fixtureRoot(): File = File(context.filesDir, "agent005-migration-fixtures")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MIGRATION_DB = "agent005-v1-to-v2.db"
        private const val INTERRUPTED_DB = "agent005-interrupted.db"
        private const val RESTART_DB = "agent005-restart.db"
        private val DATABASES = listOf(MIGRATION_DB, INTERRUPTED_DB, RESTART_DB)
    }
}
