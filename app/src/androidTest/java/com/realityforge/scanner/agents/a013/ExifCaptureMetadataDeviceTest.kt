package com.realityforge.scanner.agents.a013

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.capture.metadata.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ExifCaptureMetadataDeviceTest {
    @Test fun exifAdapterPreservesOriginalBytesAndNeverInventsPhysicalCameraId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "agent-013-exif-${System.nanoTime()}.jpg")
        try {
            val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb(x * 13, y * 17, (x + y) * 7))
            }
            FileOutputStream(file).use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "17/4")
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "23")
                setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, "5/4")
                setAttribute(ExifInterface.TAG_MAKE, "RealityForge")
                setAttribute(ExifInterface.TAG_MODEL, "Fixture Camera")
                setAttribute(ExifInterface.TAG_LENS_MAKE, "Fixture Optics")
                setAttribute(ExifInterface.TAG_LENS_MODEL, "Wide Fixture")
                saveAttributes()
            }
            val before = sha256(file)
            val metadata = ExifCaptureMetadataAdapter.read(file, PixelSize(16, 12))
            val after = sha256(file)

            assertEquals(before, after)
            assertEquals(ImageOrientation.ROTATE_90, metadata.orientation.value)
            assertEquals(4.25, metadata.focalLengthMm.value!!, 1e-9)
            assertEquals(23.0, metadata.focal35mmEquivalent.value!!, 1e-9)
            assertEquals(1.25, metadata.zoomRatio.value!!, 1e-9)
            assertEquals("Wide Fixture", metadata.reportedLensModel.value)
            assertEquals(EvidenceStatus.MISSING, metadata.physicalCameraId.status)
            assertTrue(metadata.originalMetadataRetained)
            assertEquals(metadata, CaptureMetadataCodec.decode(CaptureMetadataCodec.encode(metadata)))
        } finally {
            file.delete()
        }
    }

    @Test fun absentExifRemainsExplicitlyUnknown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "agent-013-no-exif-${System.nanoTime()}.jpg")
        try {
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GRAY)
            FileOutputStream(file).use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
            bitmap.recycle()
            val before = sha256(file)
            val metadata = ExifCaptureMetadataAdapter.read(file, PixelSize(8, 6))
            assertEquals(before, sha256(file))
            assertEquals(EvidenceStatus.MISSING, metadata.orientation.status)
            assertEquals(EvidenceStatus.MISSING, metadata.focalLengthMm.status)
            assertEquals(EvidenceStatus.MISSING, metadata.zoomRatio.status)
            assertEquals(EvidenceStatus.MISSING, metadata.physicalCameraId.status)
        } finally {
            file.delete()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
