package com.realityforge.scanner.hostintegration

import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files

/** Pure identity checks shared by the loader and synthetic negative controls. */
internal object CapabilityIdentity {
    data class Producer(val runOwner: String, val workDir: String, val sourceSha: String)
    fun producer(actual: Producer, expected: Producer) {
        require(expected.runOwner.matches(Regex("[a-f0-9]{32}")))
        require(expected.sourceSha.matches(Regex("[a-f0-9]{40}")))
        val root=File(expected.workDir)
        require(root.isAbsolute && root.path==root.canonicalPath)
        require(actual==expected) { "Foreign/stale producer identity" }
    }
    fun command(owner:String,expectedOwner:String,starttime:Long,pid:Long,boundary:String) {
        require(owner==expectedOwner && starttime>0 && pid>0)
        require(boundary in setOf("INHERITED_RUNTIME","EXPLICIT_COMPILATION_TOOL"))
    }
    /** Empty stdout/stderr is valid only for an explicitly owned successful command log. */
    fun logHash(file:File):String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 0..(8L*1024*1024))
        val size=file.length()
        val result=file.inputStream().use { RuntimeProviderIdentity.digest(it,8L*1024*1024) }
        require(result.second==size && file.length()==size)
        return result.first
    }
    fun location(url:URL,resource:String):String = when(url.protocol) {
        "jar" -> {
            val connection=url.openConnection() as JarURLConnection
            connection.useCaches=false
            require(connection.jarFileURL.protocol=="file" && connection.entryName==resource)
            "jar:"+File(connection.jarFileURL.toURI()).canonicalFile.toURI()+"!/"+resource
        }
        "file" -> File(url.toURI()).canonicalFile.toURI().toString()
        else -> error("Nonlocal class resource provider")
    }
    fun originalResources(urls:List<URL>,resource:String,expectedHash:String) {
        require(urls.size in 1..32 && expectedHash.matches(Regex("[a-f0-9]{64}")))
        require(urls.map { location(it,resource) }.toSet().size==1) { "Genuinely different class resource providers" }
        for(url in urls) {
            val connection=url.openConnection();connection.useCaches=false
            val actual=connection.getInputStream().use { RuntimeProviderIdentity.digest(it,2L*1024*1024) }
            require(actual.second>0 && actual.first==expectedHash) { "Original class bytes differ" }
        }
    }
}
