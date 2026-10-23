package com.realityforge.scanner.hostintegration

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic parser/identity negative controls only; no Robolectric, JNI or reconstruction. */
fun main() {
    fun rejected(block: () -> Unit) { check(runCatching(block).isFailure) }
    val root = Files.createTempDirectory("provider-identity-check-").toFile()
    try {
        val lib = File(root, "libandroid_runtime.so").apply { writeText("synthetic fixture, NOT a shared library") }
        val hash = RuntimeProviderIdentity.fileHash(lib)
        val inode = (Files.getAttribute(lib.toPath(), "unix:ino") as Number).toLong()
        val map = RuntimeProviderIdentity.Mapping(lib.path, inode)
        check(RuntimeProviderIdentity.matchProvider(listOf(map), root, hash, lib.length()).sha256 == hash)
        rejected { RuntimeProviderIdentity.matchProvider(emptyList(), root, hash, lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map, map.copy(inode=inode+1)), root, hash, lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map.copy(inode=inode+1)), root, hash, lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map.copy(path=map.path+" (deleted)")), root, hash, lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map), File(root,"wrong"), hash, lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map), root, "0".repeat(64), lib.length()) }
        rejected { RuntimeProviderIdentity.matchProvider(listOf(map), root, hash, lib.length()+1) }
        rejected { RuntimeProviderIdentity.parseMaps("malformed") }
        check(RuntimeProviderIdentity.parseMaps("a-b r-xp 0 00:01 $inode ${lib.path}\n").single() == map)
        val jar = File(root,"provider.jar")
        ZipOutputStream(jar.outputStream()).use { z -> z.putNextEntry(ZipEntry("native/linux/x86_64/libandroid_runtime.so")); z.write(lib.readBytes()); z.closeEntry() }
        val entry=RuntimeProviderIdentity.archiveEntry(jar,RuntimeProviderIdentity.fileHash(jar),"native/linux/x86_64/libandroid_runtime.so")
        check(entry.first==hash && entry.second==lib.length())
        rejected { RuntimeProviderIdentity.archiveEntry(jar,"0".repeat(64),"native/linux/x86_64/libandroid_runtime.so") }
        rejected { RuntimeProviderIdentity.archiveEntry(jar,RuntimeProviderIdentity.fileHash(jar),"absent") }
        println("PASS: 13 synthetic provider/parser controls; actual providers NOT_RUN")
    } finally { check(root.deleteRecursively()) }
}
