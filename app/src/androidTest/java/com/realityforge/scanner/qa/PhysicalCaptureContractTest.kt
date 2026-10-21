package com.realityforge.scanner.qa

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.RealityForgeApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Operator-selected physical project. Never manufactures capture evidence on an emulator. */
@RunWith(AndroidJUnit4::class)
class PhysicalCaptureContractTest {
    @Test fun savedPhysicalOriginalsDecodeWithSelectedCameraAndExactHashes() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val id=args.getString("physicalProjectId")
        assumeTrue("Requires an authorized physical capture project", id!=null)
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertFalse("Emulator cannot qualify physical capture",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val cameraId=requireNotNull(args.getString("physicalCameraId"))
        val app=instrumentation.targetContext.applicationContext as RealityForgeApplication
        val cameras=app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assertTrue(cameras.cameraIdList.contains(cameraId))
        val characteristics=cameras.getCameraCharacteristics(cameraId)
        assertNotNull(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
        val photos=app.repository.dao.photos(requireNotNull(id))
        assertTrue("At least twelve saved original photos required",photos.size>=12)
        val rows=JSONArray()
        photos.forEach { photo ->
            val file=app.repository.photoFile(photo)
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(photo.sha256,hash)
            val exif=ExifInterface(file)
            val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
            assertTrue(orientation in 1..8)
            val bounds=com.realityforge.scanner.engine.ImageLoader.bounds(file)
            assertTrue(bounds.first>0 && bounds.second>0)
            rows.put(JSONObject().put("sha256",hash).put("orientation",orientation).put("width",bounds.first).put("height",bounds.second))
        }
        val report=JSONObject().put("schema",1).put("source","physical-camera-input-audit").put("deviceFingerprint",Build.FINGERPRINT).put("cameraId",cameraId).put("photos",rows)
        instrumentation.addResults(android.os.Bundle().apply {putString("physicalCaptureReceipt",report.toString())})
    }
}
