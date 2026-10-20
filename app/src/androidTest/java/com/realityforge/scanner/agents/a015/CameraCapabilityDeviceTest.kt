package com.realityforge.scanner.agents.a015

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.capture.controls.Camera2CapabilityMapper
import com.realityforge.scanner.capture.controls.Camera2CharacteristicSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCapabilityDeviceTest {
    @Test
    fun realCameraCharacteristicsMapWithoutInventingUnsupportedControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.CAMERA,
            )
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = manager.cameraIdList
        assumeTrue("The test device exposes no cameras.", cameraIds.isNotEmpty())

        cameraIds.forEach { cameraId ->
            val snapshot = Camera2CharacteristicSnapshot.from(
                manager.getCameraCharacteristics(cameraId),
            )
            val capabilities = Camera2CapabilityMapper.interpret(
                cameraId = cameraId,
                characteristics = snapshot,
            )

            assertEquals(cameraId, capabilities.camera.logicalCameraId)
            assertTrue(capabilities.zoom.minimumRatio > 0f)
            assertTrue(capabilities.zoom.maximumRatio >= capabilities.zoom.minimumRatio)
            assertTrue(
                capabilities.jpegOutputSizes.all { size -> size.width > 0 && size.height > 0 },
            )
            assertFalse(capabilities.physicalCameraIds.any(String::isBlank))
            if (!capabilities.aeLockAvailable) {
                assertFalse(snapshot.aeLockAvailable == true)
            }
            if (!capabilities.awbLockAvailable) {
                assertFalse(snapshot.awbLockAvailable == true)
            }
        }
    }
}
