package com.realityforge.scanner.processing.resources

import com.realityforge.scanner.data.storagebudget.StorageAccounting

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
    val managedUsedBytes: Long = 0,
    val availableSystemBytes: Long? = null,
    val powerSave: Boolean? = null,
) {
    init {
        require(elapsedRealtimeMillis >= 0)
        require(thermalStatus == null || thermalStatus in 0..6)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(freeStorageBytes == null || freeStorageBytes >= 0)
        require(managedHeapBytes >= 0 && managedUsedBytes in 0..managedHeapBytes)
        require(availableSystemBytes == null || availableSystemBytes >= 0)
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
    val unavailableSensors: Set<String> = emptySet(),
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
        // Android THERMAL_STATUS_SEVERE is 3; waiting for CRITICAL (4) is too late.
        if (snapshot.thermalStatus != null && snapshot.thermalStatus >= 3) reasons.add("thermal")
        if (plan.depthEdge > 256 && (snapshot.thermalStatus == null || snapshot.thermalStatus >= 2))
            reasons.add("standard_tier_thermal_capacity")
        if (plan.depthEdge > 256 && snapshot.availableSystemBytes == null)
            reasons.add("standard_tier_system_memory_unknown")
        if (snapshot.lowMemory) reasons.add("memory")
        if (snapshot.managedHeapBytes < 128L * 1024 * 1024) reasons.add("heap")
        if (plan.estimatedManagedBytes > snapshot.managedHeapBytes - snapshot.managedUsedBytes)
            reasons.add("managed_memory_budget")
        snapshot.availableSystemBytes?.let { available ->
            if (!StorageAccounting.decide(plan.estimatedNativeBytes, available,
                    listOf(plan.estimatedManagedBytes, plan.estimatedGpuBytes)).admitted) reasons.add("system_memory_budget")
        }
        if (snapshot.freeStorageBytes == null) reasons.add("storage_unknown")
        else if (!StorageAccounting.decide(plan.estimatedTransientStorageBytes,
                snapshot.freeStorageBytes, emptyList()).admitted) reasons.add("storage")
        if (snapshot.batteryPercent != null && snapshot.batteryPercent <= 10 && snapshot.charging != true) reasons.add("battery")
        if (snapshot.powerSave == true && snapshot.charging != true) reasons.add("power_save")
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
            plan, reasons, snapshot.elapsedRealtimeMillis, buildSet {
                if (snapshot.thermalStatus == null) add("thermal")
                if (snapshot.batteryPercent == null) add("battery")
                if (snapshot.charging == null) add("charging")
                if (snapshot.availableSystemBytes == null) add("system_memory")
            })
    }
}
