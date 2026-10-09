package com.realityforge.scanner

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @Test fun grayscaleStatisticsCrossJni() {
        val q = NativeCore.quality(ByteArray(256) { 128.toByte() }, 16, 16)
        assertEquals(128.0, q[0], 0.0)
        assertEquals(0.0, q[2], 0.0)
    }
    @Test fun recoverTranslatedCameraOnDevice() {
        val random = Random(9)
        val rows = DoubleArray(80 * 7)
        for (i in 0 until 80) {
            val x = random.nextDouble(-1.0, 1.0); val y = random.nextDouble(-1.0, 1.0); val z = random.nextDouble(3.0, 8.0)
            val values = doubleArrayOf(x / z, y / z, (x - 1) / z, y / z, 100.0, 190.0, 220.0)
            values.copyInto(rows, i * 7)
        }
        val result = JSONObject(NativeCore.reconstruct(rows, .002))
        assertFalse(result.toString(), result.has("error"))
        assertTrue(result.getInt("pointCount") >= 75)
    }
    @Test fun registerThirdPoseOnDevice() {
        val random = Random(121)
        val tracks = DoubleArray(80 * 5)
        for (i in 0 until 80) {
            val x = random.nextDouble(-1.0, 1.0)
            val y = random.nextDouble(-1.0, 1.0)
            val z = random.nextDouble(3.0, 8.0)
            val values = doubleArrayOf(x, y, z, (x - 1.2) / z, (y + 0.1) / z)
            values.copyInto(tracks, i * 5)
        }
        val result = JSONObject(NativeCore.registerCamera(tracks, .003))
        assertFalse(result.toString(), result.has("error"))
        assertTrue(result.getInt("inlierCount") >= 75)
        assertEquals(-1.2, result.getJSONArray("translation").getDouble(0), .02)
    }
    @Test fun bundledOpenCvInitializesWithoutDownload() { assertTrue(OpenCVLoader.initLocal()) }
    @Test fun mergedApkHasNoInternetPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("android.permission.INTERNET"))
    }
}
