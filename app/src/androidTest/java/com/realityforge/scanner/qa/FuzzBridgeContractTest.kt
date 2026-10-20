package com.realityforge.scanner.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Bounded synthetic argument regressions; does not replace actual phone execution. */
@RunWith(AndroidJUnit4::class)
class FuzzBridgeContractTest {
    private fun rejected(block: () -> Any?) {
        try { block(); fail("Invalid argument accepted") } catch (expected: IllegalArgumentException) { }
    }

    @Test fun mismatchedLengthsAreRejectedRepeatedlyBeforeNativeReads() {
        repeat(32) {
            for (length in listOf(0, 1, 6, 139, 141)) rejected { NativeCore.reconstruct(DoubleArray(length), .002) }
            for (length in listOf(0, 1, 79, 81)) rejected { NativeCore.registerCamera(DoubleArray(length), .002) }
            rejected { NativeCore.reconstructDense(ByteArray(13), 32, 32, DoubleArray(32), 2.0, 7.0, 8, 4) }
            rejected { NativeCore.quality(ByteArray(13), 32, 32) }
        }
    }

    @Test fun nonfiniteArgumentsProduceSupportedErrorsAndNoOutput() {
        val rows = DoubleArray(20 * 7) { Double.NaN }
        for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            val result = JSONObject(NativeCore.reconstruct(rows, threshold))
            assertTrue(result.toString(), result.has("error"))
        }
        val path = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "synthetic-invalid-export.glb")
        path.delete()
        try {
            rejected { NativeCore.exportPoints(doubleArrayOf(0.0, 0.0, 3.0, Double.NaN, 0.0, 0.0), path.path, true) }
            assertFalse(path.exists())
        } finally { path.delete() }
    }

    @Test fun cancellationAndFinishRacesNeverRecycleTheHandle() {
        repeat(32) {
            val token = NativeCore.beginReconstruction()
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val threads = listOf(Thread {
                try { start.await(); NativeCore.cancelReconstruction(token) } catch (t: Throwable) { failure.set(t) }
            }, Thread {
                try { start.await(); NativeCore.finishReconstruction(token) } catch (t: Throwable) { failure.set(t) }
            })
            threads.forEach { it.start() }; start.countDown()
            threads.forEach { it.join(5000); assertFalse("Lease race did not terminate", it.isAlive) }
            failure.get()?.let { throw AssertionError("JNI lease race", it) }
            assertFalse(NativeCore.cancelReconstruction(token))
            assertFalse(NativeCore.finishReconstruction(token))
            val next = NativeCore.beginReconstruction()
            assertTrue(next > token)
            assertTrue(NativeCore.finishReconstruction(next))
        }
    }
}
