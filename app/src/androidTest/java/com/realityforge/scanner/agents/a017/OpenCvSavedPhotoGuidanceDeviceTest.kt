package com.realityforge.scanner.agents.a017

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.capture.guidance.GeometricModel
import com.realityforge.scanner.capture.guidance.OpenCvSavedPhotoGuidance
import com.realityforge.scanner.capture.session.CapturedPhoto
import com.realityforge.scanner.capture.session.CaptureMetadataSnapshot
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels run through the real decoder/ORB/homography adapter, not physical evidence. */
@RunWith(AndroidJUnit4::class)
class OpenCvSavedPhotoGuidanceDeviceTest {
    @Test fun realFeaturesMeasureRepeatedAndShiftedImagesWithoutInventingPoseOrCoverage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "guidance-${UUID.randomUUID()}").apply { mkdirs() }
        val original = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val shifted = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(17)
            val pixels = IntArray(320 * 240) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            original.setPixels(pixels, 0, 320, 0, 0, 320, 240)
            Canvas(shifted).drawBitmap(original, 28f, 0f, null)
            blank.eraseColor(Color.WHITE)
            val files = listOf(original, shifted, blank).mapIndexed { index, bitmap ->
                File(root, "synthetic-$index.png").apply { outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            }
            val hashes = files.map(::hash)
            val features = files.mapIndexed { index, file -> OpenCvSavedPhotoGuidance.extract(
                CapturedPhoto("project", index.toLong(), "session", "capture-$index", file,
                    CaptureMetadataSnapshot(capturedAtEpochMillis = index.toLong())), 320, 1000) }
            features.forEach {
                assertEquals(it.width * it.height, requireNotNull(it.savedQuality).sampledPixelCount)
                assertNull(requireNotNull(it.savedQuality).interFrameResidual)
            }
            assertTrue(requireNotNull(features[0].savedQuality).sharpness > requireNotNull(features[2].savedQuality).sharpness)
            assertEquals(1.0, requireNotNull(features[2].savedQuality).brightClippedFraction, 0.0)
            val repeated = OpenCvSavedPhotoGuidance.compare(features[0], features[0], false)
            assertTrue(repeated.geometricInlierCount >= 36)
            assertEquals(0.0, requireNotNull(repeated.medianImageDisplacementFraction), 0.000001)
            assertEquals(GeometricModel.HOMOGRAPHY_DOMINANT, repeated.geometricModel)
            assertFalse(repeated.poseValidated)
            assertNull(repeated.translationOverSceneDepth)
            val moved = OpenCvSavedPhotoGuidance.compare(features[0], features[1], false)
            assertTrue(moved.geometricInlierCount >= 18)
            assertTrue(requireNotNull(moved.medianImageDisplacementFraction) > .025)
            assertFalse(moved.poseValidated)
            val textureless = OpenCvSavedPhotoGuidance.compare(features[0], features[2], false)
            assertEquals(0, textureless.rawMatchCount)
            assertEquals(GeometricModel.UNKNOWN, textureless.geometricModel)
            assertEquals(hashes, files.map(::hash))
        } finally {
            original.recycle(); shifted.recycle(); blank.recycle(); root.deleteRecursively()
        }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
