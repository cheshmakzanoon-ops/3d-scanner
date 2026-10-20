package com.realityforge.scanner.agents.a021

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.storagebudget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Room transactions and database close/reopen; these tests do not simulate an OS process kill. */
@RunWith(AndroidJUnit4::class)
class StorageReservationDeviceTest {
    @Test fun concurrentRoomClaimsCannotOverbookAndUnknownRecheckRetainsExistingReservation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val available = AtomicReference<Long?>(128L * MIB)
            val service = StorageBudgetService(database.storageReservationDao(), SpaceProbe { available.get() })
            val tokens = List(12) { ReservationToken("claim-$it", "project-$it", 4) }
            val decisions = coroutineScope {
                tokens.map { token -> async(Dispatchers.IO) { token to service.recheck(token, 32L * MIB) } }.awaitAll()
            }
            assertEquals(2, decisions.count { it.second.admitted })
            val admitted = decisions.filter { it.second.admitted }.map { it.first }
            val rows = database.storageReservationDao().all()
            assertEquals(admitted.map { it.operationId }.toSet(), rows.map { it.operationId }.toSet())
            assertEquals(64L * MIB, rows.sumOf { it.remainingBytes })
            val token = admitted.first()
            assertEquals(0, service.release(token.copy(revision = token.revision + 1)))
            try {
                service.recheck(token.copy(projectId = "another-project"), 1)
                fail("Reservation identity was allowed to change")
            } catch (_: IllegalStateException) { }
            available.set(null)
            val unknown = service.recheck(token, 16L * MIB, reclaimableBytes = Long.MAX_VALUE)
            assertFalse(unknown.admitted)
            assertNull(unknown.measuredAvailableBytes)
            assertEquals("storage_measurement_unavailable", unknown.reason)
            assertEquals(32L * MIB, database.storageReservationDao().all().single { it.operationId == token.operationId }.remainingBytes)
            available.set(128L * MIB)
            assertTrue(service.recheck(token, 16L * MIB).admitted)
            assertEquals(48L * MIB, database.storageReservationDao().all().sumOf { it.remainingBytes })
            admitted.forEach { service.release(it) }
            assertTrue(database.storageReservationDao().all().isEmpty())
        } finally { database.close() }
    }

    @Test fun reopenedRoomRecoveryRetainsAuthoritativeActiveAndResumableClaimsWithoutAgeGuessing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "a021-reservations-${UUID.randomUUID()}.db"
        var database: ScanDatabase? = null
        try {
            database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            val before = requireNotNull(database)
            val service = StorageBudgetService(before.storageReservationDao(), SpaceProbe { 512L * MIB }, clock = { 1L })
            val active = ReservationToken("active", "project-active", 9)
            val paused = ReservationToken("resumable", "project-paused", 12)
            val abandoned = ReservationToken("abandoned", "project-abandoned", 1)
            listOf(active, paused, abandoned).forEach { assertTrue(service.recheck(it, 8L * MIB).admitted) }
            before.close()
            database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            val after = requireNotNull(database)
            val recovered = StorageBudgetService(after.storageReservationDao(), SpaceProbe { 512L * MIB }, clock = { Long.MAX_VALUE })
            assertEquals(1, recovered.recover(setOf(active.operationId, paused.operationId)))
            assertEquals(setOf(active.operationId, paused.operationId), after.storageReservationDao().all().map { it.operationId }.toSet())
            assertEquals(0, recovered.recover(setOf(active.operationId, paused.operationId)))
            assertTrue(after.storageReservationDao().all().all { it.updatedAt == 1L && it.remainingBytes == 8L * MIB })
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    companion object { private const val MIB = 1024L * 1024 }
}
