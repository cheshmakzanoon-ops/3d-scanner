package com.realityforge.scanner.data.operations

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

class AcquiredProjectLeaseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellationAfterAcquisitionBeforeReturnReleasesReservation() = runBlocking {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        FileProjectLeaseStore(paths).use { store ->
            val coordinator = ProjectLeaseCoordinator(store)
            val project = UUID.randomUUID().toString()
            val dispatcher = QueuedDispatcher()
            var entered = false
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                withAcquiredProjectLease(coordinator, request(project), dispatcher) { entered = true }
            }
            // Run IO acquisition but do not let runBlocking resume the caller yet.
            dispatcher.runNext()
            assertEquals(1, coordinator.activeCount(project))
            operation.cancel()
            operation.join()
            assertFalse(entered)
            assertEquals(0, coordinator.activeCount(project))
            coordinator.acquire(request(project)).close()
        }
    }

    @Test fun successfulAndFailingBodiesReleaseReservation() = runBlocking {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        FileProjectLeaseStore(paths).use { store ->
            val coordinator = ProjectLeaseCoordinator(store)
            val project = UUID.randomUUID().toString()
            assertEquals(42, withAcquiredProjectLease(coordinator, request(project)) { 42 })
            assertEquals(0, coordinator.activeCount(project))
            val failure = runCatching {
                withAcquiredProjectLease(coordinator, request(project)) { error("fixture failure") }
            }.exceptionOrNull()
            assertEquals("fixture failure", failure?.message)
            assertEquals(0, coordinator.activeCount(project))
        }
    }

    private fun request(project: String) =
        ProjectOperationRequest.create(project, ProjectOperationKind.MUTATE_INPUTS, 0)

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runNext() { checkNotNull(queue.poll()) { "Expected acquisition dispatch" }.run() }
    }
}
