package com.realityforge.scanner.agents.a030

import com.realityforge.scanner.processing.resources.*
import org.junit.Assert.*
import org.junit.Test

class ResourcePolicyTest {
    private val plan = WorkloadPlan(depthEdge = 256, estimatedManagedBytes = 8L * 1024 * 1024,
        estimatedNativeBytes = 32L * 1024 * 1024, estimatedTransientStorageBytes = 8L * 1024 * 1024)
    private fun reading(t: Long, thermal: Int? = 1, memory: Boolean = false,
                        free: Long? = 512L * 1024 * 1024, battery: Int? = 80) =
        DeviceResourceSnapshot(t, thermal, memory, battery, false, free, 256L * 1024 * 1024)

    @Test fun severeThermalOscillationDoesNotFlap() {
        val policy = ResourcePolicy(30_000)
        assertEquals(ResourceAction.RUN, policy.decide(reading(0), plan).action)
        assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, policy.decide(reading(1, 4), plan).action)
        assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, policy.decide(reading(2, 3), plan).action)
        assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, policy.decide(reading(29_999, 3), plan).action)
        assertEquals(ResourceAction.RUN, policy.decide(reading(30_002, 3), plan).action)
    }

    @Test fun lowMemoryStorageAndPowerBlockUntilRecovered() {
        for (snapshot in listOf(reading(0, memory = true), reading(0, free = 12),
                reading(0, free = null), reading(0, battery = 5))) {
            assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, ResourcePolicy().decide(snapshot, plan).action)
        }
        assertEquals(ResourceAction.RUN, ResourcePolicy(0).decide(reading(0, thermal = null), plan).action)
    }

    @Test fun pressureResetsRecoveryAndNeverExpandsBaselinePlan() {
        val policy = ResourcePolicy(100)
        policy.decide(reading(0, 5), plan)
        policy.decide(reading(50), plan)
        policy.decide(reading(90, memory = true), plan)
        assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, policy.decide(reading(120), plan).action)
        val resumed = policy.decide(reading(220), plan)
        assertEquals(ResourceAction.RUN, resumed.action)
        assertSame(plan, resumed.plan)
        assertEquals(256, resumed.plan.depthEdge)
        assertEquals(1, resumed.plan.maxConcurrentNativeTasks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPlanCannotEnableParallelNativeProcessing() {
        plan.copy(maxConcurrentNativeTasks = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decreasingObservationTimeIsRejected() {
        val policy = ResourcePolicy()
        policy.decide(reading(10), plan)
        policy.decide(reading(9), plan)
    }
}
