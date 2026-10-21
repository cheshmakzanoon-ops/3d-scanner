package com.realityforge.scanner.agents.a030

import com.realityforge.scanner.engine.DenseWorkload
import com.realityforge.scanner.processing.resources.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResourceWorkloadSelectionTest {
    private fun healthy() = DeviceResourceSnapshot(0, 0, false, 90, false, 1024L * MIB,
        512L * MIB, 32L * MIB, 2048L * MIB, false)

    @Test fun observedHeadroomAndSensorsSelectOnlyImplementedConservativeTiers() {
        assertEquals(ResourceWorkloadSelection.STANDARD, ResourceWorkloadScheduler.select(healthy()))
        for (snapshot in listOf(healthy().copy(thermalStatus = 2), healthy().copy(thermalStatus = null),
            healthy().copy(availableSystemBytes = null), healthy().copy(availableSystemBytes = 128L * MIB),
            healthy().copy(managedUsedBytes = 450L * MIB), healthy().copy(lowMemory = true),
            healthy().copy(powerSave = true), healthy().copy(powerSave = null), healthy().copy(batteryPercent = 15))) {
            assertEquals(ResourceWorkloadSelection.COMPACT, ResourceWorkloadScheduler.select(snapshot))
        }
    }

    @Test fun persistedTierReproducesBuffersSamplesAndCameraSubsetAcrossChangedConditions() {
        val selected = ResourceWorkloadScheduler.select(healthy())
        val persisted = selected.encode().toString()
        assertEquals(ResourceWorkloadSelection.COMPACT, ResourceWorkloadScheduler.select(healthy().copy(thermalStatus = 2)))
        val restored = ResourceWorkloadSelection.decode(JSONObject(persisted))
        assertEquals(selected, restored)
        for (selection in ResourceWorkloadSelection.entries) {
            val indices = DenseWorkload.selectCameraIndices(12, selection.maximumViews)
            assertEquals(selection.maximumViews, indices.size)
            assertEquals(0, indices[0]); assertEquals(1, indices[1]); assertEquals(11, indices.last())
            assertEquals(indices.size, indices.toSet().size)
            val plan = DenseWorkload.plan(selection.depthEdge, selection.depthEdge, indices.size, selection)
            assertTrue(plan.sampledGridPixels <= selection.maximumGridSamples)
            assertEquals(selection.identitySha256, plan.resourceSelectionIdentity)
            assertEquals(selection.depthEdge, plan.depthEdge)
            assertTrue(plan.textureEdge <= selection.textureEdgeLimit)
            assertEquals(1, plan.nativeThreads)
            assertTrue(plan.resourcePlan().estimatedManagedBytes > plan.packedDepthBytes)
        }
        assertNotEquals(ResourceWorkloadSelection.COMPACT.identitySha256, ResourceWorkloadSelection.STANDARD.identitySha256)
    }

    @Test fun standardResumePausesUnderCompactOnlyThermalEvidenceWithoutChangingIdentity() {
        val plan = ResourceWorkloadSelection.STANDARD.resourcePlan()
        val result = ResourcePolicy().decide(healthy().copy(thermalStatus = 2), plan)
        assertEquals(ResourceAction.PAUSE_AT_CHECKPOINT, result.action)
        assertSame(plan, result.plan)
        assertTrue(result.reasons.contains("standard_tier_thermal_capacity"))
        assertThrows(IllegalArgumentException::class.java) {
            ResourceWorkloadSelection.decode(ResourceWorkloadSelection.COMPACT.encode().put("tier", "standard"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceWorkloadSelection.decode(ResourceWorkloadSelection.COMPACT.encode().put("schema", "1"))
        }
    }

    @Test fun appearancePolicyChangesPreserveGeometryIdentityForEveryImplementedTier() {
        for (selection in ResourceWorkloadSelection.entries) {
            val stage = selection.stageIdentity
            for (changed in listOf(stage.copy(textureEdgeLimit = stage.textureEdgeLimit + 64),
                stage.copy(maximumAtlasPixels = stage.maximumAtlasPixels + 1024))) {
                assertEquals(stage.geometryText, changed.geometryText)
                assertNotEquals(stage.appearanceText, changed.appearanceText)
            }
            for (changed in listOf(stage.copy(depthEdge = stage.depthEdge + 32),
                stage.copy(maximumViews = stage.maximumViews + 1),
                stage.copy(maximumGridSamples = stage.maximumGridSamples + 1),
                stage.copy(nativeThreads = stage.nativeThreads + 1))) {
                assertNotEquals(stage.geometryText, changed.geometryText)
                assertEquals(stage.appearanceText, changed.appearanceText)
            }
            val resumed = ResourceWorkloadSelection.decode(selection.encode())
            assertEquals(selection.geometryIdentityText, resumed.geometryIdentityText)
            assertEquals(selection.geometryIdentitySha256, resumed.geometryIdentitySha256)
            assertEquals(selection.appearanceIdentityText, resumed.appearanceIdentityText)
            assertEquals(selection.appearanceIdentitySha256, resumed.appearanceIdentitySha256)
            assertNotEquals(selection.geometryIdentitySha256, selection.appearanceIdentitySha256)
        }
        assertNotEquals(ResourceWorkloadSelection.COMPACT.geometryIdentitySha256,
            ResourceWorkloadSelection.STANDARD.geometryIdentitySha256)
    }

    @Test fun acceptedSurfaceWorkingAndStagingAllowanceParticipatesInActualAdmission() {
        for (selection in ResourceWorkloadSelection.entries) {
            val plan = selection.resourcePlan()
            assertTrue(plan.estimatedNativeBytes >= AcceptedSurfaceCheckpointBudget.WORKING_BYTES)
            assertTrue(plan.estimatedTransientStorageBytes >= AcceptedSurfaceCheckpointBudget.PEAK_DISK_BYTES)
            // A device that fits the earlier buffers but has only half the new checkpoint
            // headroom must pause before any accepted-surface allocation or staging write.
            val memory = healthy().copy(availableSystemBytes = plan.estimatedManagedBytes +
                plan.estimatedNativeBytes + 64L * MIB - AcceptedSurfaceCheckpointBudget.WORKING_BYTES / 2)
            assertTrue(ResourcePolicy().decide(memory, plan).reasons.contains("system_memory_budget"))
            val disk = healthy().copy(freeStorageBytes = plan.estimatedTransientStorageBytes +
                64L * MIB - AcceptedSurfaceCheckpointBudget.PEAK_DISK_BYTES / 2)
            assertTrue(ResourcePolicy().decide(disk, plan).reasons.contains("storage"))
            assertEquals(ResourceAction.RUN, ResourcePolicy().decide(healthy(), plan).action)
            assertEquals(selection, ResourceWorkloadSelection.decode(selection.encode()))
        }
    }
    companion object { const val MIB = 1024L * 1024 }
}
