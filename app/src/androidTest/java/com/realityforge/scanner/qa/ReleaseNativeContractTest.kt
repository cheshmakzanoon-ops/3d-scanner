package com.realityforge.scanner.qa

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.NativeSmokeTest
import com.realityforge.scanner.PhotographicViewerTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs existing behavioral JNI/OpenCV/photographic export/reopen regressions against R8 output. */
class ReleaseNativeContractTest {
    @Test fun installedCandidateIsMinifiedReleaseAndNativeExportsReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Candidate must be non-debuggable", 0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val native = NativeSmokeTest()
        native.bundledOpenCvInitializesWithoutDownload()
        native.grayscaleStatisticsCrossJni()
        native.recoverTranslatedCameraOnDevice()
        native.rectifiedCpuDenseStereoReconstructsActualPixelsThroughJni()
        PhotographicViewerTest().actualDialogViewerRendersAfterActivityPauseAndResume()
    }
}
