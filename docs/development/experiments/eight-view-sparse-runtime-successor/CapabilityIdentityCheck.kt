package com.realityforge.scanner.hostintegration

import java.io.File
import java.nio.file.Files
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure identity regressions only; synthetic bytes are not app classes or native artifacts. */
fun main() {
    var controls=0
    fun accepted(block:()->Unit) { block();controls++ }
    fun rejected(block:()->Unit) { check(runCatching(block).isFailure);controls++ }
    val root=Files.createTempDirectory("capability-identity-").toFile()
    try {
        val empty=File(root,"compiler.raw.log").apply { writeBytes(byteArrayOf()) }
        accepted { check(CapabilityIdentity.logHash(empty)=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") }
        rejected { RuntimeProviderIdentity.fileHash(empty) }
        val expected=CapabilityIdentity.Producer("a".repeat(32),root.canonicalPath,"b".repeat(40))
        accepted { CapabilityIdentity.producer(expected,expected) }
        rejected { CapabilityIdentity.producer(expected.copy(runOwner="c".repeat(32)),expected) }
        rejected { CapabilityIdentity.producer(expected.copy(workDir=File(root,"other").path),expected) }
        rejected { CapabilityIdentity.producer(expected.copy(sourceSha="d".repeat(40)),expected) }
        accepted { CapabilityIdentity.command(expected.runOwner,expected.runOwner,17,18,"INHERITED_RUNTIME") }
        rejected { CapabilityIdentity.command("c".repeat(32),expected.runOwner,17,18,"INHERITED_RUNTIME") }
        rejected { CapabilityIdentity.command(expected.runOwner,expected.runOwner,0,18,"INHERITED_RUNTIME") }
        rejected { CapabilityIdentity.command(expected.runOwner,expected.runOwner,17,0,"INHERITED_RUNTIME") }
        rejected { CapabilityIdentity.command(expected.runOwner,expected.runOwner,17,18,"FAKE") }
        val bytes="synthetic original class bytes".toByteArray();val resource="example/Owner.class"
        fun jar(name:String):File=File(root,name).also { f -> ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry(resource));z.write(bytes);z.closeEntry() } }
        val first=jar("first.jar");val second=jar("second.jar")
        fun url(file:File)=URL("jar:"+file.toURI()+"!/"+resource)
        val hash=bytes.inputStream().use { RuntimeProviderIdentity.digest(it,1024) }.first
        accepted { CapabilityIdentity.originalResources(listOf(url(first),url(first)),resource,hash) }
        rejected { CapabilityIdentity.originalResources(listOf(url(first),url(second)),resource,hash) }
        rejected { CapabilityIdentity.originalResources(listOf(url(first)),resource,"0".repeat(64)) }
        rejected { CapabilityIdentity.originalResources(listOf(url(first)),"wrong.class",hash) }
        rejected { CapabilityIdentity.originalResources(emptyList(),resource,hash) }
        println("PASS: $controls pure capability identity controls; pipeline/native NOT_RUN")
    } finally { check(root.deleteRecursively()) }
}
