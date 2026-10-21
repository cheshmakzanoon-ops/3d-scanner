package com.realityforge.scanner.agents.a021

import com.realityforge.scanner.data.storagebudget.*
import org.junit.Assert.*
import org.junit.Test

class StorageBudgetTest {
    @Test fun competingClaimsAndReclaimableDoNotBecomeFreeSpace() {
        val denied = StorageAccounting.decide(60, 100, listOf(30), reclaimableBytes = 1000, safetyBytes = 20)
        assertFalse(denied.admitted)
        assertEquals(10L, denied.shortfallBytes)
        assertTrue(StorageAccounting.decide(50, 100, listOf(30), safetyBytes = 20).admitted)
    }

    @Test fun overflowAndUnknownMeasurementsFailClosed() {
        assertFalse(StorageAccounting.decide(1, Long.MAX_VALUE, listOf(Long.MAX_VALUE, 1), safetyBytes = 0).admitted)
        assertFalse(StorageAccounting.decide(0, null, emptyList()).admitted)
        assertFalse(StorageAccounting.decide(Long.MAX_VALUE, Long.MAX_VALUE, emptyList(), safetyBytes = 1).admitted)
        assertTrue(runCatching { StorageEstimate(originalBytes = Long.MAX_VALUE, stagingBytes = 1) }.isFailure)
    }

    @Test fun unknownProviderReservesBoundAndTemporaryDuplication() {
        val estimate = StorageEstimate.photoImport(null)
        assertTrue(estimate.unknownInputSize)
        assertEquals(StorageEstimate.MAX_PHOTO_BYTES * 2 + 16L * 1024 * 1024, estimate.additionalPeakBytes)
        assertEquals(4096L, StorageEstimate.export(2048).additionalPeakBytes)
        assertTrue(runCatching { StorageEstimate.export(Long.MAX_VALUE) }.isFailure)
    }
}
