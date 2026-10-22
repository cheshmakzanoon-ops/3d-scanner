package com.realityforge.scanner.imaging

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Bounded metadata-only proof used when AndroidX exposes its synthetic undefined orientation.
 * PRESENT means a primary orientation entry physically exists, regardless of its TIFF type.
 * UNTRUSTED includes unsupported absence proofs and caps; it does not diagnose damaged pixels.
 */
internal object PhysicalOrientationPresence {
    enum class Evidence { ABSENT, PRESENT, UNTRUSTED }
    private const val MAX_CHUNKS = 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_READ_BYTES = 131072
    private const val MAX_TIFF_BYTES = 65536L
    private val exifHeader = byteArrayOf(69, 120, 105, 102, 0, 0)
    private val pngHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val formatBytes = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

    fun read(file: File, format: ImageFormat): Evidence {
        // HEIF item/extent location is not proven by this small container reader.
        // Valid rotations derived by AndroidX are accepted before this fallback.
        if (format == ImageFormat.HEIF_HEIC) return Evidence.UNTRUSTED
        return try {
            RandomAccessFile(file, "r").use { input ->
                val reader = Reader(input)
                val result = when (format) {
                    ImageFormat.JPEG -> jpeg(reader)
                    ImageFormat.PNG -> png(reader)
                    ImageFormat.WEBP -> webp(reader)
                    ImageFormat.HEIF_HEIC -> Evidence.UNTRUSTED
                }
                if (input.length() == reader.length) result else Evidence.UNTRUSTED
            }
        } catch (_: IOException) {
            Evidence.UNTRUSTED
        } catch (_: SecurityException) {
            Evidence.UNTRUSTED
        }
    }

    private class Reader(private val input: RandomAccessFile) {
        val length = input.length()
        private var readBytes = 0
        fun bytes(position: Long, count: Int): ByteArray {
            requireTrusted(position >= 0 && count >= 0 && position <= length - count.toLong())
            requireTrusted(count <= MAX_READ_BYTES - readBytes)
            readBytes += count
            return ByteArray(count).also { input.seek(position); input.readFully(it) }
        }
        fun byte(position: Long) = bytes(position, 1)[0].toInt() and 255
    }

    private fun requireTrusted(condition: Boolean) {
        if (!condition) throw IOException("Orientation metadata absence cannot be proven")
    }
    private fun ushort(bytes: ByteArray, offset: Int, little: Boolean): Int {
        val a = bytes[offset].toInt() and 255
        val b = bytes[offset + 1].toInt() and 255
        return if (little) a or (b shl 8) else (a shl 8) or b
    }
    private fun uint(bytes: ByteArray, offset: Int, little: Boolean): Long {
        var value = 0L
        for (index in 0..3) {
            val shift = if (little) index * 8 else (3 - index) * 8
            value = value or ((bytes[offset + index].toLong() and 255L) shl shift)
        }
        return value
    }
    private fun fourcc(bytes: ByteArray, offset: Int, name: String) =
        name.indices.all { bytes[offset + it].toInt() and 255 == name[it].code }

    private fun tiff(reader: Reader, start: Long, end: Long): Evidence {
        requireTrusted(start >= 0 && end <= reader.length && end - start in 8..MAX_TIFF_BYTES)
        val header = reader.bytes(start, 8)
        val little = header[0].toInt() == 0x49 && header[1].toInt() == 0x49
        requireTrusted(little || (header[0].toInt() == 0x4d && header[1].toInt() == 0x4d))
        requireTrusted(ushort(header, 2, little) == 42)
        val relative = uint(header, 4, little)
        requireTrusted(relative >= 8 && relative <= end - start - 2)
        val directory = start + relative
        val count = ushort(reader.bytes(directory, 2), 0, little)
        requireTrusted(count <= MAX_ENTRIES && 2L + count * 12L + 4 <= end - directory)
        var present = false
        for (index in 0 until count) {
            val entry = reader.bytes(directory + 2L + index * 12L, 12)
            if (ushort(entry, 0, little) == 0x0112) {
                // Presence is independent of a parser accepting type/count/value.
                present = true
                continue
            }
            val format = ushort(entry, 2, little)
            requireTrusted(format in 1 until formatBytes.size)
            val size = uint(entry, 4, little) * formatBytes[format]
            if (size > 4) {
                val offset = uint(entry, 8, little)
                requireTrusted(offset >= 8 && offset <= end - start && size <= end - start - offset)
            }
        }
        val next = uint(reader.bytes(directory + 2L + count * 12L, 4), 0, little)
        requireTrusted(next == 0L || (next >= 8 && next <= end - start - 2))
        return if (present) Evidence.PRESENT else Evidence.ABSENT
    }

    private fun jpeg(reader: Reader): Evidence {
        requireTrusted(reader.bytes(0, 2).contentEquals(byteArrayOf(-1, -40)))
        var position = 2L
        var exifBlocks = 0
        var evidence = Evidence.ABSENT
        repeat(MAX_CHUNKS) {
            requireTrusted(reader.byte(position++) == 0xff)
            var marker = reader.byte(position++)
            while (marker == 0xff) marker = reader.byte(position++)
            if (marker in 0xd0..0xd7 || marker == 0x01) return@repeat
            requireTrusted(marker != 0xd8 && marker != 0xd9 && marker != 0)
            val length = ushort(reader.bytes(position, 2), 0, false)
            requireTrusted(length >= 2 && length.toLong() <= reader.length - position)
            if (marker == 0xda) return evidence // All relevant EXIF APP1 blocks precede image scans.
            if (marker == 0xe1) {
                requireTrusted(length >= 8)
                if (reader.bytes(position + 2, 6).contentEquals(exifHeader)) {
                    requireTrusted(++exifBlocks == 1)
                    evidence = tiff(reader, position + 8, position + length)
                }
            }
            position += length
        }
        return Evidence.UNTRUSTED
    }

    private fun png(reader: Reader): Evidence {
        requireTrusted(reader.bytes(0, 8).contentEquals(pngHeader))
        var position = 8L
        var exifBlocks = 0
        var evidence = Evidence.ABSENT
        repeat(MAX_CHUNKS) {
            val header = reader.bytes(position, 8)
            val length = uint(header, 0, false)
            val start = position + 8
            requireTrusted(length <= reader.length - start - 4)
            val end = start + length
            if (fourcc(header, 4, "eXIf")) {
                requireTrusted(++exifBlocks == 1 && length <= MAX_TIFF_BYTES)
                val crc = CRC32().apply { update(header, 4, 4) }
                var cursor = start
                while (cursor < end) {
                    val bytes = reader.bytes(cursor, minOf(4096L, end - cursor).toInt())
                    crc.update(bytes)
                    cursor += bytes.size
                }
                requireTrusted(crc.value == uint(reader.bytes(end, 4), 0, false))
                evidence = tiff(reader, start, end)
            }
            if (fourcc(header, 4, "IEND")) {
                requireTrusted(length == 0L && end + 4 == reader.length)
                return evidence
            }
            position = end + 4
        }
        return Evidence.UNTRUSTED
    }

    private fun webp(reader: Reader): Evidence {
        val header = reader.bytes(0, 12)
        requireTrusted(fourcc(header, 0, "RIFF") && fourcc(header, 8, "WEBP"))
        val endOfFile = uint(header, 4, true) + 8
        requireTrusted(endOfFile == reader.length)
        var position = 12L
        var exifBlocks = 0
        var evidence = Evidence.ABSENT
        repeat(MAX_CHUNKS) {
            if (position == endOfFile) return evidence
            val chunk = reader.bytes(position, 8)
            val length = uint(chunk, 4, true)
            val start = position + 8
            requireTrusted(length <= endOfFile - start)
            val end = start + length
            requireTrusted((length and 1L) <= endOfFile - end)
            if (length and 1L != 0L) requireTrusted(reader.byte(end) == 0)
            if (fourcc(chunk, 0, "EXIF")) {
                requireTrusted(++exifBlocks == 1)
                val hasPrefix = length >= 6 && reader.bytes(start, 6).contentEquals(exifHeader)
                evidence = tiff(reader, start + if (hasPrefix) 6 else 0, end)
            }
            position = end + (length and 1L)
        }
        return Evidence.UNTRUSTED
    }
}
