package com.realityforge.scanner.processing.resources

/** Versioned, local-only resource contract. Null signals unavailable telemetry, never "healthy". */
data class DeviceResourceSnapshot(
    val elapsedRealtimeMillis: Long,
    val managedHeapLimitBytes: Long,
    val availableStorageBytes: Long?,
    val availableMemoryBytes: Long?,
    val lowMemory: Boolean?,
    val thermalStatus: Int?,
    val batteryPercent: Int?,
    val pluggedIn: Boolean?,
    val powerSaveMode: Boolean?,
) {
    init {
        require(elapsedRealtimeMillis >= 0 && managedHeapLimitBytes >= 0)
        require(availableStorageBytes == null || availableStorageBytes >= 0)
        require(availableMemoryBytes == null || availableMemoryBytes >= 0)
        require(thermalStatus == null || thermalStatus in 0..6)
        require(batteryPercent == null || batteryPercent in 0..100)
    }
}

enum class ResourceAction { RUN, PAUSE, REJECT }
enum class ResourceReason { READY, THERMAL, MEMORY, STORAGE, BATTERY, POWER_SAVE, UNKNOWN_MEMORY }

/** Resource costs are conservative planning estimates, not measured RSS or GPU allocation. */
data class WorkloadPlan(
    val schemaVersion: Int = 1,
    val depthEdge: Int,
    val textureEdge: Int,
    val views: Int,
    val pixelStride: Int,
    val estimatedManagedBytes: Long,
    val estimatedNativeBytes: Long,
    val estimatedGpuBytes: Long,
    val estimatedTemporaryStorageBytes: Long,
    val concurrentStages: Int = 1,
) {
    init {
        require(schemaVersion == 1 && depthEdge in 32..384 && textureEdge in 32..768)
        require(views in 2..8 && pixelStride in 3..8 && concurrentStages == 1)
        require(estimatedManagedBytes >= 0 && estimatedNativeBytes >= 0)
        require(estimatedGpuBytes >= 0 && estimatedTemporaryStorageBytes >= 0)
    }
    /** Stable identity input; actual reconstruction inputs and engine version belong to Agent 009. */
    fun settingsIdentity(): String = listOf(schemaVersion,depthEdge,textureEdge,views,pixelStride,
        estimatedManagedBytes,estimatedNativeBytes,estimatedGpuBytes,
        estimatedTemporaryStorageBytes,concurrentStages).joinToString(":")
}

data class ResourcePolicyDecision(
    val action: ResourceAction,
    val reason: ResourceReason,
    val plan: WorkloadPlan,
    val nextState: ResourcePolicyState,
)
data class ResourcePolicyState(val heldReason: ResourceReason? = null, val recoveryObservations: Int = 0)

/**
 * Pure boundary policy: never raises workload sizes, drops views or creates partial results.
 * The caller pauses at a checkpoint/cancellation-safe boundary and resumes from the saved stage.
 * Recovery requires three consecutive healthy samples to resist thermal oscillation.
 */
object DeviceResourcePolicy {
    const val RESUME_OBSERVATIONS = 3
    private const val MIN_STORAGE_MARGIN = 64L * 1024 * 1024
    private const val MIN_AVAILABLE_MEMORY = 96L * 1024 * 1024

    fun decide(snapshot: DeviceResourceSnapshot, plan: WorkloadPlan, previous: ResourcePolicyState = ResourcePolicyState()): ResourcePolicyDecision {
        val storage = snapshot.availableStorageBytes
        val reason = when {
            snapshot.managedHeapLimitBytes < 128L * 1024 * 1024 -> ResourceReason.UNKNOWN_MEMORY
            storage == null || plan.estimatedTemporaryStorageBytes > storage ||
                storage - plan.estimatedTemporaryStorageBytes < MIN_STORAGE_MARGIN -> ResourceReason.STORAGE
            snapshot.lowMemory == true || (snapshot.availableMemoryBytes != null &&
                snapshot.availableMemoryBytes < MIN_AVAILABLE_MEMORY) -> ResourceReason.MEMORY
            snapshot.thermalStatus != null && snapshot.thermalStatus >= 4 -> ResourceReason.THERMAL
            snapshot.batteryPercent != null && snapshot.batteryPercent <= 10 && snapshot.pluggedIn != true -> ResourceReason.BATTERY
            snapshot.powerSaveMode == true -> ResourceReason.POWER_SAVE
            else -> ResourceReason.READY
        }
        if (reason != ResourceReason.READY) {
            val action = if (reason == ResourceReason.STORAGE || reason == ResourceReason.UNKNOWN_MEMORY) ResourceAction.REJECT else ResourceAction.PAUSE
            return ResourcePolicyDecision(action, reason, plan, ResourcePolicyState(reason, 0))
        }
        if (previous.heldReason != null) {
            // Thermal resumes only below MODERATE (2); battery resumes above 15%.
            val safe = (snapshot.thermalStatus == null || snapshot.thermalStatus <= 2) &&
                (snapshot.batteryPercent == null || snapshot.batteryPercent > 15 || snapshot.pluggedIn == true)
            if (!safe) return ResourcePolicyDecision(ResourceAction.PAUSE, previous.heldReason, plan, ResourcePolicyState(previous.heldReason, 0))
            val count = previous.recoveryObservations.coerceIn(0, RESUME_OBSERVATIONS - 1) + 1
            if (count < RESUME_OBSERVATIONS) return ResourcePolicyDecision(ResourceAction.PAUSE, previous.heldReason, plan, ResourcePolicyState(previous.heldReason, count))
        }
        return ResourcePolicyDecision(ResourceAction.RUN, ResourceReason.READY, plan, ResourcePolicyState())
    }
}
