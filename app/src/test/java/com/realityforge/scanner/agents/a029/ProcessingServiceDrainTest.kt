package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.work.execution.ProcessingServiceDrain
import org.junit.Assert.*
import org.junit.Test

class ProcessingServiceDrainTest {
    @Test fun pauseCommandDoesNotOrphanWorkerShutdown() {
        val drain = running()
        drain.commandReceived(2) // A valid PAUSE changes durable state; it never starts a new worker.
        drain.commandFinished()
        assertFalse(drain.canStop)
        drain.workerStopped()
        assertTrue(drain.canStop)
        assertEquals(2, drain.latestStartId)
    }

    @Test fun duplicateStartDuringCancellationDrainsOnlyAfterPriorWorkerStops() {
        val drain = running()
        drain.commandReceived(2)
        drain.requestStart()
        assertFalse(drain.claimWorker())
        drain.commandFinished()
        drain.workerStopped()
        assertFalse(drain.canStop)
        assertTrue(drain.claimWorker())
        assertFalse(drain.claimWorker())
        drain.workerStopped() // Empty second queue drain still gets its own idle callback.
        assertTrue(drain.canStop)
    }

    @Test fun invalidCommandCannotStopExistingWorkerOrLosePendingResume() {
        val drain = running()
        drain.commandReceived(2)
        drain.commandReceived(3) // Delivered RESUME is still waiting for the command mutex.
        drain.commandFinished()
        drain.workerStopped()
        assertFalse(drain.canStop)
        drain.requestStart()
        assertTrue(drain.claimWorker())
        drain.commandFinished()
        assertFalse(drain.canStop)
        drain.workerStopped()
        assertTrue(drain.canStop)
        assertEquals(3, drain.latestStartId)
    }

    @Test fun rejectedIdleCommandAndFailedAdmissionStopInitialForeground() {
        val drain = ProcessingServiceDrain()
        drain.commandReceived(1)
        drain.requestStart()
        drain.abandonUnboundStart()
        drain.commandFinished()
        assertTrue(drain.canStop)
    }

    private fun running() = ProcessingServiceDrain().apply {
        commandReceived(1)
        requestStart()
        assertTrue(claimWorker())
        commandFinished()
    }
}
