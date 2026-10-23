package com.realityforge.scanner.hostintegration

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Own-JVM mapping metadata only; hashes only explicitly admitted library/provider files. */
internal object RuntimeProviderIdentity {
    const val LIBRARY_LIMIT = 256L * 1024 * 1024
    data class Mapping(val path: String, val inode: Long)
    data class Provider(val path: String, val sha256: String, val bytes: Long, val inode: Long)
    fun digest(input: InputStream, limit: Long): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(n.toLong() <= limit - total) { "Input exceeded bounded identity read" }
            total += n; md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to total
    }
    fun fileHash(file: File, limit: Long = LIBRARY_LIMIT): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..limit)
        val size = file.length()
        val (hash, count) = file.inputStream().use { digest(it, limit) }
        require(count == size && file.length() == size) { "Identity file changed during read" }
        return hash
    }
    fun parseMaps(text: String): List<Mapping> {
        require(text.toByteArray().size <= 4 * 1024 * 1024)
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size <= 32768)
        return lines.mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"), limit = 6)
            require(columns.size >= 5 && columns[4].toLongOrNull() != null) { "Malformed own-JVM map" }
            if (columns.size < 6 || !columns[5].startsWith('/')) null
            else Mapping(columns[5], columns[4].toLong())
        }.distinct()
    }
    fun ownMaps(): List<Mapping> {
        val file = File("/proc/self/maps")
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 4 * 1024 * 1024)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        return parseMaps(bytes.toString(Charsets.UTF_8))
    }
    fun archiveEntry(jar: File, expectedJarHash: String, entryName: String, maximumBytes: Long = LIBRARY_LIMIT): Pair<String, Long> {
        require(maximumBytes in 1..LIBRARY_LIMIT)
        require(fileHash(jar, 512L * 1024 * 1024) == expectedJarHash)
        return ZipFile(jar).use { zip ->
            val matches = zip.entries().asSequence().filter { it.name == entryName }.toList()
            require(matches.size == 1 && !matches.single().isDirectory) { "Ambiguous/missing provider entry" }
            val entry = matches.single()
            require(entry.size in 1..maximumBytes)
            zip.getInputStream(entry).use { digest(it, maximumBytes) }.also { require(it.second == entry.size) }
        }
    }
    fun matchProvider(mappings: List<Mapping>, tmp: File, expectedHash: String, expectedBytes: Long, maximumBytes: Long = LIBRARY_LIMIT): Provider {
        require(maximumBytes in 1..LIBRARY_LIMIT && expectedBytes in 1..maximumBytes)
        val candidates = mappings.filter { File(it.path.removeSuffix(" (deleted)")).name == "libandroid_runtime.so" }
        require(candidates.size == 1) { "UNQUALIFIED: expected one actual mapped Android native provider, got ${candidates.size}" }
        val mapped = candidates.single()
        require(!mapped.path.endsWith(" (deleted)")) { "Mapped provider was deleted" }
        val file = File(mapped.path)
        require(file.isAbsolute && file.canonicalFile.toPath().startsWith(tmp.canonicalFile.toPath())) { "Provider outside dedicated JVM tmp" }
        require(!Files.isSymbolicLink(file.toPath()))
        val inode = (Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
        require(inode == mapped.inode && inode > 0) { "Mapped inode differs from provider bytes" }
        require(file.length() == expectedBytes) { "Mapped provider byte count differs" }
        val hash = fileHash(file, maximumBytes)
        require(hash == expectedHash && file.length() == expectedBytes) { "UNQUALIFIED: mapped provider differs from pinned archive entry" }
        return Provider(file.canonicalPath, hash, file.length(), inode)
    }
}

/** Exact checksum-pinned API35 framework provider metadata, not the compat distribution. */
internal object Api35ProviderContract {
    const val FRAMEWORK_SHA256 = "6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b"
    const val COMPAT_SHA256 = "2f4e879b00eed634d0e43353ecff80db4d5ce24b3b213d1e6053cb21b0ced10f"
    const val FRAMEWORK_NAME = "android-all-15-robolectric-12650502.jar"
    const val ENTRY = "native/linux/x86_64/libandroid_runtime.so"
    const val ENTRY_BYTES = 15526048L
    const val MAXIMUM_BYTES = 33554432L
    fun validateArchive(hash: String, entry: String, bytes: Long) {
        require(hash == FRAMEWORK_SHA256 && entry == ENTRY)
        require(bytes == ENTRY_BYTES && bytes in 1..MAXIMUM_BYTES)
    }
}
