package com.realityforge.scanner.agents.a012

import com.realityforge.scanner.imaging.ImageFormat
import com.realityforge.scanner.imaging.PhysicalOrientationPresence
import com.realityforge.scanner.imaging.PhysicalOrientationPresence.Evidence
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Metadata-only container fixtures built from TIFF/PNG/RIFF fields, never decoded photographs. */
class PhysicalOrientationPresenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val exif = byteArrayOf(69, 120, 105, 102, 0, 0)
    private fun probe(bytes: ByteArray, format: ImageFormat): Evidence {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val result = PhysicalOrientationPresence.read(file, format)
        assertArrayEquals("Metadata inspection must preserve original bytes", bytes, file.readBytes())
        return result
    }
    private fun tiff(type: Int? = null, value: Int = 0, little: Boolean = true): ByteArray {
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        return ByteBuffer.allocate(if (type == null) 14 else 26).order(order).apply {
            put(if (little) byteArrayOf(73, 73) else byteArrayOf(77, 77))
            putShort(42); putInt(8); putShort(if (type == null) 0 else 1)
            if (type != null) {
                putShort(0x0112); putShort(type.toShort()); putInt(if (type == 2) 2 else 1)
                if (type == 2) put(byteArrayOf(57, 0, 0, 0)) else { putShort(value.toShort()); putShort(0) }
            }
            putInt(0)
        }.array()
    }
    private fun jpeg(vararg profiles: ByteArray): ByteArray {
        var bytes = byteArrayOf(-1, -40)
        for (profile in profiles) {
            val payload = exif + profile
            val length = payload.size + 2
            bytes += byteArrayOf(-1, -31, (length ushr 8).toByte(), length.toByte()) + payload
        }
        return bytes + byteArrayOf(-1, -38, 0, 2)
    }
    private fun pngChunk(name: String, payload: ByteArray): ByteArray {
        val kind = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(kind); update(payload) }
        return ByteBuffer.allocate(payload.size + 12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(payload.size); put(kind); put(payload); putInt(crc.value.toInt())
        }.array()
    }
    private fun png(vararg profiles: ByteArray): ByteArray {
        var bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        for (profile in profiles) bytes += pngChunk("eXIf", profile)
        return bytes + pngChunk("IEND", byteArrayOf())
    }
    private fun webp(vararg profiles: ByteArray): ByteArray {
        var chunks = byteArrayOf()
        for (profile in profiles) chunks += ByteBuffer.allocate(8 + profile.size + (profile.size and 1))
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put("EXIF".toByteArray()); putInt(profile.size); put(profile)
                if (profile.size and 1 != 0) put(0)
            }.array()
        return ByteBuffer.allocate(chunks.size + 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(chunks.size + 4); put("WEBP".toByteArray()); put(chunks)
        }.array()
    }
    private fun all(profile: ByteArray, expected: Evidence) {
        assertEquals(expected, probe(jpeg(profile), ImageFormat.JPEG))
        assertEquals(expected, probe(png(profile), ImageFormat.PNG))
        assertEquals(expected, probe(webp(profile), ImageFormat.WEBP))
    }

    @Test fun missingAndEmptyMetadataProveAbsenceInAllSupportedContainers() {
        all(tiff(), Evidence.ABSENT)
        assertEquals(Evidence.ABSENT, probe(jpeg(), ImageFormat.JPEG))
        assertEquals(Evidence.ABSENT, probe(png(), ImageFormat.PNG))
        assertEquals(Evidence.ABSENT, probe(webp(), ImageFormat.WEBP))
    }
    @Test fun wrongTypeAndStoredUndefinedArePhysicalTagsInBothByteOrders() {
        for (little in listOf(true, false)) {
            all(tiff(type = 2, little = little), Evidence.PRESENT)
            for (value in 0..8) all(tiff(type = 3, value = value, little = little), Evidence.PRESENT)
        }
        assertEquals(Evidence.PRESENT, probe(webp(exif + tiff(type = 2)), ImageFormat.WEBP))
    }
    @Test fun truncatedAndOutOfBoundsDirectoriesStayUntrusted() {
        all(tiff().dropLast(1).toByteArray(), Evidence.UNTRUSTED)
        all(tiff().also { it[4] = -1; it[5] = -1; it[6] = -1; it[7] = -1 }, Evidence.UNTRUSTED)
        all(tiff(type = 3).also { it[8] = 2 }, Evidence.UNTRUSTED)
        all(tiff(type = 3).also {
            // Nonorientation ASCII field with data pointing outside its TIFF envelope.
            it[10] = 0x0f; it[11] = 1; it[12] = 2; it[14] = 6
            it[18] = -1; it[19] = -1; it[20] = -1; it[21] = -1
        }, Evidence.UNTRUSTED)
    }
    @Test fun allExifBlocksAreExaminedAndDuplicatesAreUntrusted() {
        assertEquals(Evidence.UNTRUSTED, probe(jpeg(tiff(), tiff(type = 2)), ImageFormat.JPEG))
        assertEquals(Evidence.UNTRUSTED, probe(png(tiff(), tiff(type = 2)), ImageFormat.PNG))
        assertEquals(Evidence.UNTRUSTED, probe(webp(tiff(), tiff(type = 2)), ImageFormat.WEBP))
        val nonExif = byteArrayOf(-1, -31, 0, 10) + "XMP-data".toByteArray()
        assertEquals(Evidence.PRESENT, probe(byteArrayOf(-1, -40) + nonExif + jpeg(tiff(type = 2)).drop(2), ImageFormat.JPEG))
    }
    @Test fun chunkAndEntryAndReadCapsReturnUntrusted() {
        val segments = ByteArray(1025 * 4) { byteArrayOf(-1, -32, 0, 2)[it % 4] }
        assertEquals(Evidence.UNTRUSTED, probe(byteArrayOf(-1, -40) + segments + byteArrayOf(-1, -38, 0, 2), ImageFormat.JPEG))
        val tooManyEntries = ByteBuffer.allocate(8 + 2 + 4097 * 12 + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(73, 73)); putShort(42); putInt(8); putShort(4097)
        }.array()
        all(tooManyEntries, Evidence.UNTRUSTED)
        assertEquals(Evidence.UNTRUSTED, probe(byteArrayOf(-1, -40) + ByteArray(131073) { -1 }, ImageFormat.JPEG))
    }
    @Test fun corruptChunkLengthsCrcAndPaddingNeverProveAbsence() {
        assertEquals(Evidence.UNTRUSTED, probe(png(tiff()).also { it[it.size - 13] = (it[it.size - 13].toInt() xor 1).toByte() }, ImageFormat.PNG))
        assertEquals(Evidence.UNTRUSTED, probe(jpeg(tiff()).also { it[4] = -1; it[5] = -1 }, ImageFormat.JPEG))
        assertEquals(Evidence.UNTRUSTED, probe(webp(tiff()) + 0, ImageFormat.WEBP))
        assertEquals(Evidence.UNTRUSTED, probe(png(tiff()) + 0, ImageFormat.PNG))
        val odd = webp(tiff() + 0).dropLast(1).toByteArray()
        assertEquals(Evidence.UNTRUSTED, probe(odd, ImageFormat.WEBP))
    }
    @Test fun malformedTiffHeadersEntriesAndLinksNeverProveAbsence() {
        all(tiff().also { it[0] = 0 }, Evidence.UNTRUSTED)
        all(tiff().also { it[2] = 43 }, Evidence.UNTRUSTED)
        all(tiff().also { it[4] = 0 }, Evidence.UNTRUSTED)
        all(tiff().also { it[10] = -1; it[11] = -1; it[12] = -1; it[13] = -1 }, Evidence.UNTRUSTED)
        all(tiff(type = 3).also { it[10] = 0x0f; it[12] = 0 }, Evidence.UNTRUSTED)
        // Even an invalid type or zero count still physically contains the tag.
        all(tiff(type = 3).also { it[12] = -1; it[13] = -1 }, Evidence.PRESENT)
        all(tiff(type = 3).also { it[14] = 0 }, Evidence.PRESENT)
    }
    @Test fun oversizedTiffAndMalformedContainerFramingStayUntrusted() {
        assertEquals(Evidence.UNTRUSTED, probe(png(ByteArray(65537)), ImageFormat.PNG))
        assertEquals(Evidence.UNTRUSTED, probe(webp(ByteArray(65537)), ImageFormat.WEBP))
        assertEquals(Evidence.UNTRUSTED, probe(jpeg().dropLast(1).toByteArray(), ImageFormat.JPEG))
        assertEquals(Evidence.UNTRUSTED, probe(byteArrayOf(-1, -40, -1, -31, 0, 2, -1, -38, 0, 2), ImageFormat.JPEG))
        assertEquals(Evidence.UNTRUSTED, probe(webp(tiff()).also { it[4] = 0 }, ImageFormat.WEBP))
        assertEquals(Evidence.UNTRUSTED, probe(webp(tiff() + 0).also { it[it.lastIndex] = 1 }, ImageFormat.WEBP))
        assertEquals(Evidence.UNTRUSTED, probe(jpeg(), ImageFormat.PNG))
        assertEquals(Evidence.UNTRUSTED, probe(png(), ImageFormat.WEBP))
        assertEquals(Evidence.UNTRUSTED, probe(webp(), ImageFormat.JPEG))
    }

    @Test fun unsupportedAbsenceAndIoFailureStayUntrusted() {
        assertEquals(Evidence.UNTRUSTED, PhysicalOrientationPresence.read(File(temporary.root, "absent"), ImageFormat.JPEG))
        assertEquals(Evidence.UNTRUSTED, PhysicalOrientationPresence.read(File(temporary.root, "not-opened"), ImageFormat.HEIF_HEIC))
    }
}
