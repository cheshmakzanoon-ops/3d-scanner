package com.realityforge.scanner.qa

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** Complements existing full-session GPU/reconstruction/export tests in the same APK. */
@RunWith(AndroidJUnit4::class)
class CompatibilityContractTest {
    @Test fun installedNativeLibrariesRunOnMeasuredPageSize() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" })
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue("Unexpected runtime page size: $pageSize", pageSize == 4096L || pageSize == 16384L)
        assertTrue("Bundled OpenCV must initialize offline", OpenCVLoader.initLocal())
        val quality = NativeCore.quality(ByteArray(16 * 16) { 42 }, 16, 16)
        assertEquals(42.0, quality[0], 0.0)
        android.util.Log.i("RealityForgeCompatibility", "fingerprint=${Build.FINGERPRINT};abi=${Build.SUPPORTED_ABIS.joinToString()};pageSize=$pageSize")
    }
}
