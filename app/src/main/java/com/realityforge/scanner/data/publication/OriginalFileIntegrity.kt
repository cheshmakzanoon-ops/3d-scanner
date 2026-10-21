package com.realityforge.scanner.data.publication

import java.io.File
import java.security.MessageDigest

/** Bounded byte identity only; caller must additionally enforce project path ownership. */
internal object OriginalFileIntegrity {
    fun matches(file: File, expectedHash: String, expectedBytes: Long, maxBytes: Long): Boolean {
        if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            expectedBytes !in 1..maxBytes || file.length() != expectedBytes) return false
        val actual = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            var observed = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (count <= 0 || count > expectedBytes - observed) return false
                observed += count; digest.update(buffer, 0, count)
            }
            if (observed != expectedBytes) return false
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return actual == expectedHash
    }
}
