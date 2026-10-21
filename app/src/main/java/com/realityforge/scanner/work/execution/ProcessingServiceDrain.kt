package com.realityforge.scanner.work.execution

/** Main-thread state for commands delivered while a worker is running or unwinding cancellation. */
internal class ProcessingServiceDrain {
    var latestStartId: Int = 0
        private set
    private var pendingCommands = 0
    var workerBound = false
        private set
    var startRequested = false
        private set
    val canStop: Boolean get() = pendingCommands == 0 && !workerBound && !startRequested

    fun commandReceived(startId: Int) {
        latestStartId = startId
        pendingCommands++
    }

    fun commandFinished() {
        check(pendingCommands > 0)
        pendingCommands--
    }

    fun requestStart() { startRequested = true }

    fun claimWorker(): Boolean {
        if (workerBound || !startRequested) return false
        workerBound = true
        startRequested = false
        return true
    }

    /** Called only after the prior worker Job has actually completed. */
    fun workerStopped() {
        check(workerBound)
        workerBound = false
    }

    fun abandonUnboundStart() {
        if (!workerBound) startRequested = false
    }
}
