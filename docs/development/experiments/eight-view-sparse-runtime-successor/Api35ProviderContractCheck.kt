package com.realityforge.scanner.hostintegration

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure frozen metadata and synthetic ZIP bound controls, not actual framework loading. */
fun main() {
    var count=0
    fun accepted(block:()->Unit) { block();count++ }
    fun rejected(block:()->Unit) { check(runCatching(block).isFailure);count++ }
    val correct=Api35ProviderContract.FRAMEWORK_SHA256
    val entry=Api35ProviderContract.ENTRY
    val bytes=Api35ProviderContract.ENTRY_BYTES
    accepted { Api35ProviderContract.validateArchive(correct,entry,bytes) }
    rejected { Api35ProviderContract.validateArchive(Api35ProviderContract.COMPAT_SHA256,entry,bytes) }
    rejected { Api35ProviderContract.validateArchive(correct,"native/linux/x86_64/librobolectric-nativeruntime.so",bytes) }
    rejected { Api35ProviderContract.validateArchive(correct,entry,bytes-1) }
    rejected { Api35ProviderContract.validateArchive(correct,entry,bytes+1) }
    rejected { Api35ProviderContract.validateArchive(correct,entry,0) }
    rejected { Api35ProviderContract.validateArchive(correct,entry,Api35ProviderContract.MAXIMUM_BYTES+1) }
    val root=Files.createTempDirectory("provider-archive-bound-").toFile()
    try {
        val jar=File(root,"synthetic.jar")
        ZipOutputStream(jar.outputStream()).use { z -> z.putNextEntry(ZipEntry("entry"));z.write(ByteArray(16));z.closeEntry() }
        val hash=RuntimeProviderIdentity.fileHash(jar)
        accepted { check(RuntimeProviderIdentity.archiveEntry(jar,hash,"entry",16).second==16L) }
        rejected { RuntimeProviderIdentity.archiveEntry(jar,hash,"entry",15) }
        rejected { RuntimeProviderIdentity.archiveEntry(jar,hash,"entry",0) }
        println("PASS: $count pure API35 metadata/archive bound controls; actual framework/native NOT_RUN")
    } finally { check(root.deleteRecursively()) }
}
