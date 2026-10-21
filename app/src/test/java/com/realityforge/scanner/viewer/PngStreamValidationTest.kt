package com.realityforge.scanner.viewer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class PngStreamValidationTest {
    private fun compressed(raw: ByteArray, dictionary: Boolean = false): ByteArray {
        val z = Deflater()
        try {
            if (dictionary) z.setDictionary(byteArrayOf(1, 2, 3, 4))
            z.setInput(raw); z.finish()
            val out = ByteArrayOutputStream(); val scratch = ByteArray(64)
            while (!z.finished()) { val n = z.deflate(scratch); check(n > 0); out.write(scratch, 0, n) }
            return out.toByteArray()
        } finally { z.end() }
    }
    private fun png(parts: List<ByteArray>, width: Int = 1, height: Int = 1, colorType: Byte = 6): ByteArray {
        val out = ByteArrayOutputStream(); val data = DataOutputStream(out)
        data.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            data.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(8, colorType, 0, 0, 0)).array())
        for (part in parts) chunk("IDAT", part)
        chunk("IEND", byteArrayOf()); return out.toByteArray()
    }
    private val row = byteArrayOf(0, 1, 2, 3, -1)
    private fun valid(bytes: ByteArray) { assertEquals(1 to 1, PhotographicGlb.validatePng(bytes)) }
    private fun invalid(bytes: ByteArray) { assertThrows(IllegalArgumentException::class.java) { PhotographicGlb.validatePng(bytes) } }

    @Test fun acceptsOrdinarySingleChunk() = valid(png(listOf(compressed(row))))
    @Test fun acceptsHeaderOnlyChunk() {
        val z = compressed(row); valid(png(listOf(z.copyOfRange(0, 2), z.copyOfRange(2, z.size))))
    }
    @Test fun acceptsEveryByteFragmentation() = valid(png(compressed(row).map { byteArrayOf(it) }))
    @Test fun acceptsAdlerBoundaryAndRgb() {
        val z = compressed(row)
        valid(png(listOf(z.copyOfRange(0, z.size - 4), z.copyOfRange(z.size - 4, z.size))))
        valid(png(listOf(compressed(byteArrayOf(0, 1, 2, 3))), colorType = 2))
    }
    @Test fun rejectsDictionary() = invalid(png(listOf(compressed(row, true))))
    @Test fun rejectsTruncatedStreamAndChecksum() {
        val z = compressed(row)
        for (cut in listOf(1, 2, z.size - 4, z.size - 1)) invalid(png(listOf(z.copyOf(cut))))
    }
    @Test fun rejectsTrailingCompressedBytesAndChunks() {
        val z = compressed(row)
        invalid(png(listOf(z + byteArrayOf(0))))
        invalid(png(listOf(z, byteArrayOf(0))))
        invalid(png(listOf(z + z)))
    }
    @Test fun rejectsExcessExpansionAndInvalidFilter() {
        invalid(png(listOf(compressed(row + row))))
        invalid(png(listOf(compressed(byteArrayOf(5, 1, 2, 3, -1)))))
        invalid(png(listOf(compressed(row.copyOf(4)))))
    }
    @Test fun preservesCancellationIdentity() {
        val expected = CancellationException("stop")
        val actual = assertThrows(CancellationException::class.java) {
            PhotographicGlb.validatePng(png(listOf(compressed(row)))) { throw expected }
        }
        assertSame(expected, actual)
    }
    // Captured from the existing rf_texture_png_tests production encodeRgbaPng path.
    // 127x89 RGBA, byte i=(i*37).toByte(); IDAT lengths 2,665. Not a pixel decoder oracle.
    @Test fun acceptsActualNativeProductionPng() {
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAH8AAABZCAYAAAAXSPs4AAAAAklEQVR4nGKkkSsAAAKZSURBVOXQgwEYAADAsM62bdu2bdu2bdu2" +
            "bdu2bdvGIT0hgSQl203ecTdQ8jIdp+1+EDRV+S4z9z0OkbZS9zkHn4XOULXX/CMvw2Wu0XfR8TcRs9UesPTU+yg56w1ecfZT9DwN" +
            "h62+8DVW/iYj113+EbdQ8zEbr/1OULTV+C03/yUu0XbS9jsBk5XuMHXX/SApy3WesfdR8DQVu80+8DRU+io95x1+ETZT9T4Lj72O" +
            "kLVW/yUn30XOUXfQ8jMfo+VuMHTV+S8x8zUesfbS9zgFm43ecPVX/CItx22+8TdR8TYTt90OkLRU+yk77wVOUbbT9D0Pg6Wu0HXW" +
            "/ich01XuMffQ8zAZq/VecPRV+Cw1+y0+8TZS9joDl53+EDVX/SErz32OkbfR8DUXv8Uu0HTU+is/4xVuMXbT9T8Ji7WesPWW2Y89" +
            "wOzHHmD2Yw8w+7EHmP3YA8x+7AFmP/YAsx97gNmPPcDsxx5g9mMPMPuxB5j92APMfuwBZj/2ALMfe4DZjz3A7MceYPZjDzD7sQeY" +
            "/dgDzH7sAWY/9gCzH3uA2Y89wOzHHmD2Yw8w+7EHmP3YA8x+7AFmP/YAsx97gNmPPcDsxx5g9mMPMPuxB5j92APMfuwBZj/2ALMf" +
            "e4DZjz3A7MceYPZjDzD7sQeY/dgDzH7sAWY/9gCzH3uA2Y89wOzHHmD2Yw8w+7EHmP3YA8x+7AFmP/YAsx97gNmPPcDsxx5g9mMP" +
            "MPuxB5j92APMfuwBZj/2ALVfHyD2Yw8w+7EHmP3YA8x+7AFmP/YAsx97gNmPPcDsxx5g9mMPMPuxB5j92APMfuwBZj/2ALMfe4DZ" +
            "jz3A7MceYPZjDzD7sQeY/dgDzH7sAWY/9gCzH3uA2Y89wOzHHmD2/wfVMPl8DgscPAAAAABJRU5ErkJggg=="
        )
        assertEquals("6629d57d5bf1d7c8064c38f297d838de28a576c7866c9cad028c4f6f407d73ad",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertEquals(127 to 89, PhotographicGlb.validatePng(bytes))
    }
}
