package com.realityforge.scanner.agents.a007

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.data.jobs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FencedJobCommandDeviceTest : DurableJobStoreDeviceSupport() {
    @Test fun staleVersionAndAttemptCannotMutateNewWorker() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, IsolatedJobStoreDatabase::class.java).build()
        try {
            val store = store(db, "fenced")
            val initial = store.enqueue(input("fenced-project"), 10)
            val running = requireNotNull(store.acquireNext("worker1", 11, 1000)).snapshot
            suspend fun command(expected: JobSnapshot, cmd: FencedJobCommand, time: Long) =
                store.commandIfCurrent(expected.jobId, expected.input.projectId,
                    expected.input.projectRevision, expected.attempt, expected.stateVersion, cmd, time)
            assertNull(command(initial, FencedJobCommand.CANCEL, 12))
            val paused = requireNotNull(command(running, FencedJobCommand.PAUSE, 13)).snapshot
            assertEquals(ReconstructionJobState.PAUSED, paused.state)
            assertNull(command(running, FencedJobCommand.RESUME, 14))
            requireNotNull(command(paused, FencedJobCommand.RESUME, 15))
            val newer = requireNotNull(store.acquireNext("worker2", 16, 1000)).snapshot
            assertTrue(newer.attempt > running.attempt)
            assertNull(command(running, FencedJobCommand.CANCEL, 17))
            assertEquals(newer, store.snapshot(initial.jobId))
            assertNull(store.commandIfCurrent(newer.jobId, "other-project", newer.input.projectRevision,
                newer.attempt, newer.stateVersion, FencedJobCommand.CANCEL, 18))
            assertNull(store.commandIfCurrent(newer.jobId, newer.input.projectId, newer.input.projectRevision + 1,
                newer.attempt, newer.stateVersion, FencedJobCommand.CANCEL, 19))
            assertEquals(ReconstructionJobState.CANCELLED,
                requireNotNull(command(newer, FencedJobCommand.CANCEL, 20)).snapshot.state)
        } finally { db.close() }
    }
}
