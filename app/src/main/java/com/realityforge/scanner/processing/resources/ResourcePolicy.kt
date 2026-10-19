package com.realityforge.scanner.processing.resources

/**
 * Conservative, versioned scheduling decision. Unknown sensor readings are not treated as
 * favorable readings. Decisions apply at checkpoint boundaries, never during publication.
 */
internal data class DeviceResourceSnapshot(
    val elapsedRealtimeMillis: Long,
    val thermalStatus: Int?,
    val lowMemory: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val freeStorageBytes: Long?,
    val managedHeapBytes: Long,
) {
    init {
        require(elapsedRealtimeMillis >= 0)
        require(thermalStatus == null || thermalStatus in 0..6)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(freeStorageBytes == null || freeStorageBytes >= 0)
    }
}

internal data class WorkloadPlan(
    val version: Int = 1,
    val depthEdge: Int,
    val maxConcurrentNativeTasks: Int = 1,
    val estimatedManagedBytes: Long,
    val estimatedNativeBytes: Long,
    val estimatedGpuBytes: Long = 0,
    val estimatedTransientStorageBytes: Long,
) {
    init {
        require(version == 1)
        require(depthEdge in listOf(256, 384))
        require(maxConcurrentNativeTasks == 1)
        require(estimatedManagedBytes >= 0 && estimatedNativeBytes >= 0)
        require(estimatedGpuBytes >= 0 && estimatedTransientStorageBytes >= 0)
    }
}

internal enum class ResourceAction { RUN, PAUSE_AT_CHECKPOINT }

internal data class ResourcePolicyDecision(
    val action: ResourceAction,
    val plan: WorkloadPlan,
    val reasons: Set<String>,
    val observedAtMillis: Long,
)

/**
 * Stateful hysteresis is scoped to one reconstruction operation. The caller must preserve
 * completed depth checkpoints and revalidate the same revision before restarting.
 */
internal class ResourcePolicy(private val recoveryMillis: Long = 30_000L) {
    init { require(recoveryMillis >= 0) }
    private var healthySince: Long? = null
    private var paused = false
    private var lastTime = -1L

    fun decide(snapshot: DeviceResourceSnapshot, plan: WorkloadPlan): ResourcePolicyDecision {
        require(snapshot.elapsedRealtimeMillis >= lastTime) { "Snapshot clock moved backward" }
        lastTime = snapshot.elapsedRealtimeMillis
        val reasons = linkedSetOf<String>()
        if (snapshot.thermalStatus != null && snapshot.thermalStatus >= 4) reasons.add("thermal")
        if (snapshot.lowMemory) reasons.add("memory")
        if (snapshot.managedHeapBytes < 128L * 1024 * 1024) reasons.add("heap")
        if (snapshot.freeStorageBytes == null) reasons.add("storage_unknown")
        else if (snapshot.freeStorageBytes < plan.estimatedTransientStorageBytes + 64L * 1024 * 1024) reasons.add("storage")
        if (snapshot.batteryPercent != null && snapshot.batteryPercent <= 10 && snapshot.charging != true) reasons.add("battery")
        if (reasons.isNotEmpty()) {
            paused = true
            healthySince = null
        } else if (paused) {
            if (healthySince == null) healthySince = snapshot.elapsedRealtimeMillis
            if (snapshot.elapsedRealtimeMillis - healthySince!! >= recoveryMillis) paused = false
            else reasons.add("recovery_hysteresis")
        }
        return ResourcePolicyDecision(
            if (paused) ResourceAction.PAUSE_AT_CHECKPOINT else ResourceAction.RUN,
            plan, reasons, snapshot.elapsedRealtimeMillis)
    }
}
