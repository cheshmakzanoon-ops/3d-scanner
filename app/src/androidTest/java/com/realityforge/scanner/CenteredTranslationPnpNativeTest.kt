package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Licensed, derived numeric observations only: not image extraction, UI or physical quality. */
@RunWith(AndroidJUnit4::class)
class CenteredTranslationPnpNativeTest {
    private data class Pose(val rotation: DoubleArray, val translation: DoubleArray)

    private fun fixture(): DoubleArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pnp/centered-translation-45.json").use { it.readBytesBounded() }
        assertEquals("Fixture byte identity", FIXTURE_SHA256,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(setOf("schema", "classification", "sourceReplaySha256", "count", "threshold", "rows"),
            json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("schema"))
        assertEquals("DERIVED_NUMERIC_PNP_REGRESSION_NOT_PHYSICAL_QUALITY", json.getString("classification"))
        assertEquals(REPLAY_SHA256, json.getString("sourceReplaySha256"))
        assertEquals(45, json.getInt("count"))
        assertEquals(THRESHOLD, json.getDouble("threshold"), 0.0)
        val rows = json.getJSONArray("rows")
        assertEquals(45, rows.length())
        val flattened = DoubleArray(45 * 5)
        val distinct = mutableSetOf<List<Double>>()
        for (i in 0 until 45) {
            val row = rows.getJSONArray(i)
            assertEquals(5, row.length())
            val values = List(5) { k -> row.getDouble(k).also { value ->
                assertTrue("Finite bounded fixture coordinate", value.isFinite() && abs(value) < if (k < 3) 16.0 else 2.0)
                flattened[i * 5 + k] = value
            } }
            assertTrue("Distinct input rows", distinct.add(values))
        }
        return flattened
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            assertTrue("Fixture exceeds 16 KiB", output.size() + count <= 16 * 1024)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun verifyPose(text: String, rows: DoubleArray): Pose {
        val result = NativeResultJson.decode(text)
        assertFalse("Native error: $result", result.has("error"))
        assertFalse("Native error code: $result", result.has("errorCode"))
        assertEquals(2, result.getInt("schema"))
        assertEquals("camera_pose", result.getString("representation"))
        assertEquals("arbitrary", result.getString("scale"))
        assertEquals(45, result.getInt("inputTracks"))
        val rotation = result.getJSONArray("rotation")
        val translation = result.getJSONArray("translation")
        assertEquals(9, rotation.length()); assertEquals(3, translation.length())
        val r = DoubleArray(9) { rotation.getDouble(it) }
        val t = DoubleArray(3) { translation.getDouble(it) }
        assertTrue(r.all { it.isFinite() && abs(it) <= 1.000001 })
        assertTrue(t.all { it.isFinite() && abs(it) < 32.0 })
        for (i in 0..2) for (j in 0..2) {
            val dot = (0..2).sumOf { k -> r[3 * i + k] * r[3 * j + k] }
            assertEquals("Orthonormal rotation", if (i == j) 1.0 else 0.0, dot, 1e-6)
        }
        val det = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6])
        assertEquals("Proper rotation", 1.0, det, 1e-6)
        val inliers = result.getJSONArray("inlierIndices")
        assertTrue("Strong support at original threshold", inliers.length() in 40..45)
        assertEquals(inliers.length(), result.getInt("inlierCount"))
        val seen = mutableSetOf<Int>()
        for (n in 0 until inliers.length()) {
            val raw = inliers.getDouble(n)
            assertTrue("Exact original row index", raw.isFinite() && raw >= 0 && raw < 45 && raw % 1.0 == 0.0)
            val index = raw.toInt()
            assertTrue("Unique original row index", seen.add(index))
            val q = DoubleArray(3) { i -> t[i] + (0..2).sumOf { k -> r[3 * i + k] * rows[index * 5 + k] } }
            assertTrue("Positive camera depth", q.all { it.isFinite() } && q[2] > 1e-8)
            val du = q[0] / q[2] - rows[index * 5 + 3]
            val dv = q[1] / q[2] - rows[index * 5 + 4]
            assertTrue("Every reported inlier satisfies original strict gate", sqrt(du * du + dv * dv) < THRESHOLD)
        }
        return Pose(r, t)
    }

    @Test fun realNumericRowsKeepStrongSupportAcrossBoundedOriginShift() {
        val rows = fixture()
        val shift = doubleArrayOf(1.0, -0.5, 2.0)
        val shifted = rows.copyOf()
        for (i in 0 until 45) for (k in 0..2) shifted[i * 5 + k] += shift[k]
        val token = NativeCore.beginReconstruction()
        assertTrue("Own a valid native operation lease", token > 0)
        try {
            val base = verifyPose(NativeCore.registerCameraCancellable(rows, THRESHOLD, token), rows)
            val translated = verifyPose(NativeCore.registerCameraCancellable(shifted, THRESHOLD, token), shifted)
            // X' = X + d => t' = t - R*d. No platform-specific hypothesis count assertion.
            assertArrayEquals("Origin shift preserves rotation", base.rotation, translated.rotation, 1e-5)
            val expected = DoubleArray(3) { i -> base.translation[i] -
                (0..2).sumOf { k -> base.rotation[3 * i + k] * shift[k] } }
            assertArrayEquals("Centered translation is correctly denormalized", expected, translated.translation, 1e-5)
        } finally {
            assertTrue("Release this test's native operation lease", NativeCore.finishReconstruction(token))
        }
    }

    companion object {
        private const val THRESHOLD = 5.113144661933157E-4
        private const val FIXTURE_SHA256 = "661a975f2bebcd509538ad9f7507f86c8cf63170b9f4c67714ac99e4d6bdd12a"
        private const val REPLAY_SHA256 = "d7dcb3daa08de6be743da959d05e19599ce7aa2ae01cc3b20c8f5f9a89808361"
    }
}
