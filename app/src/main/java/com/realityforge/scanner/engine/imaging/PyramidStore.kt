package com.realityforge.scanner.engine.imaging

import com.realityforge.scanner.engine.geometry.PixelTransform
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Dedicated derived-cache directory only; originals and current models must live elsewhere. */
class PyramidStore(private val directory: File, private val maxBytes: Long) {
    init { require(maxBytes in 1024..2_000_000_000L); require(directory.mkdirs() || directory.isDirectory) }
    @Synchronized fun load(identity: String, transform: PixelTransform): PyramidLevel? {
        require(identity.matches(Regex("[0-9a-f]{64}")))
        val file = File(directory, "$identity.rfp")
        return try {
            if (!file.isFile || file.length() > maxBytes || file.length() > 23_000_000 || file.length() < 56) return null
            val length = file.length()
            if (length !in 56..minOf(maxBytes,23_000_000L)) return null
            val bytes = ByteArray(length.toInt())
            DataInputStream(FileInputStream(file)).use { input -> input.readFully(bytes); if (input.read() != -1) return null }
            val payload = bytes.copyOfRange(32, bytes.size)
            if (!MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) return null
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != 0x52465059 || input.readInt() != PyramidLevel.SCHEMA || input.readUTF() != identity) return null
                val width = input.readInt(); val height = input.readInt()
                if (width != transform.width || height != transform.height || width <= 0 || height <= 0 || width.toLong() * height > PyramidLevel.MAX_PIXELS) return null
                if (input.readInt() != transform.sourceWidth || input.readInt() != transform.sourceHeight) return null
                val storedTransform = DoubleArray(6) { input.readDouble() }
                val expectedTransform = doubleArrayOf(transform.a, transform.b, transform.tx, transform.c, transform.d, transform.ty)
                if (!storedTransform.contentEquals(expectedTransform)) return null
                val count = width * height
                if (input.available().toLong() != count.toLong() * 5) return null
                val pixels = IntArray(count) { input.readInt() }; val mask = ByteArray(count); input.readFully(mask)
                file.setLastModified(System.currentTimeMillis())
                PyramidLevel(identity, width, height, transform, pixels, mask)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }
    @Synchronized fun store(level: PyramidLevel): Boolean { return try {
        FileOutputStream(File(directory, ".rfp.lock"), true).channel.use { channel ->
            val lock = channel.tryLock() ?: return false
            lock.use { storeLocked(level) }
        }
    } catch (_: IOException) { false } catch (_: java.nio.channels.OverlappingFileLockException) { false } }
    private fun storeLocked(level: PyramidLevel): Boolean {
        val expected = level.width.toLong() * level.height * 5 + 192
        if (expected > maxBytes) return false
        // The directory lock proves no other participating writer owns an incomplete level.
        try { Files.newDirectoryStream(directory.toPath()).use { entries ->
            var inspected=0
            for (entry in entries) {
                if (++inspected>8192) return false
                val file=entry.toFile()
                if (file.name.startsWith("pyramid-") && file.name.endsWith(".partial") && !file.delete()) return false
            }
        } } catch (_: IOException) { return false }
        val temp = try { File.createTempFile("pyramid-", ".partial", directory) } catch (_: IOException) { return false }
        return try {
            val payload = ByteArrayOutputStream(expected.toInt()).also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(0x52465059); output.writeInt(PyramidLevel.SCHEMA); output.writeUTF(level.identity)
                    output.writeInt(level.width); output.writeInt(level.height)
                    val t = level.originalToLevel
                    output.writeInt(t.sourceWidth); output.writeInt(t.sourceHeight)
                    for (v in doubleArrayOf(t.a,t.b,t.tx,t.c,t.d,t.ty)) output.writeDouble(v)
                    for (pixel in level.pixels()) output.writeInt(pixel)
                    output.write(level.validity())
                }
            }.toByteArray()
            evict(expected, level.identity)
            FileOutputStream(temp).use { out -> out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload); out.fd.sync() }
            Files.move(temp.toPath(), File(directory, "${level.identity}.rfp").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) { false } finally { temp.delete() }
    }
    private fun evict(needed: Long, retained: String) {
        val files = ArrayList<File>()
        var inspected = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (++inspected > 8192) throw IOException("Pyramid directory entry budget exceeded")
                val file = entry.toFile()
                if (file.name.matches(Regex("[0-9a-f]{64}\\.rfp")) && file.name != "$retained.rfp") {
                    if (files.size >= 4096) throw IOException("Pyramid cache record budget exceeded")
                    if (file.length() > maxBytes) { if (!file.delete()) throw IOException("Oversized cache cannot be evicted") }
                    else files.add(file)
                }
            }
        }
        files.sortBy { it.lastModified() }
        var bytes = 0L
        val lengths = files.associateWith { file ->
            val length=file.length(); if (length>maxBytes) throw IOException("Cache record changed during admission"); length
        }
        for (length in lengths.values) bytes+=length // <=4096 records *2GB; cannot overflow Long
        for (file in files) { if (bytes + needed <= maxBytes) break; val length = lengths.getValue(file); if (file.delete()) bytes -= length }
        if (bytes + needed > maxBytes) throw IOException("Pyramid cache budget unavailable")
    }
}
