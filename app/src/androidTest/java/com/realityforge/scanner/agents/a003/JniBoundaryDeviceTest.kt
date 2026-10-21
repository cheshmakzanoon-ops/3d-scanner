package com.realityforge.scanner.agents.a003

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JniBoundaryDeviceTest {
    @Test fun invalidShapesAndNonFiniteScalarsRemainRecoverable() {
        repeat(20) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeCore.reconstruct(DoubleArray(140), Double.NaN)
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeCore.quality(byteArrayOf(), Int.MAX_VALUE, Int.MAX_VALUE)
            }
        }
        assertEquals(5, NativeCore.quality(ByteArray(16 * 16), 16, 16).size)
    }

    @Test fun staleHandlesCannotAffectNewOperations() {
        val old = NativeCore.beginReconstruction()
        assertTrue(NativeCore.finishReconstruction(old))
        val active = NativeCore.beginReconstruction()
        try {
            assertNotEquals(old, active)
            assertFalse(NativeCore.cancelReconstruction(old))
            assertFalse(NativeCore.finishReconstruction(old))
            assertTrue(NativeCore.cancelReconstruction(active))
        } finally { NativeCore.finishReconstruction(active) }
    }

    @Test fun operationCapacityRecoversAfterEveryLeaseCloses() {
        val leases = (1..16).map { NativeCore.beginReconstruction() }
        try { assertThrows(IllegalArgumentException::class.java) { NativeCore.beginReconstruction() } }
        finally { leases.forEach { NativeCore.finishReconstruction(it) } }
        val next = NativeCore.beginReconstruction()
        assertTrue(NativeCore.finishReconstruction(next))
    }

    @Test fun nonFiniteExportDoesNotCreateACompletedFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(context.cacheDir, "jni-invalid-point-${System.nanoTime()}.ply")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                NativeCore.exportPoints(doubleArrayOf(Double.NaN, 0.0, 1.0, 10.0, 20.0, 30.0), target.path, false)
            }
            assertFalse(target.exists())
        } finally { target.delete() }
    }
}
