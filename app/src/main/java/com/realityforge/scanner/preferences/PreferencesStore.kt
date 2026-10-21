package com.realityforge.scanner.preferences

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

enum class DisplayTheme { SYSTEM, LIGHT, DARK }
data class UserPreferences(val fallbackFocal35mm:Double=26.0,val theme:DisplayTheme=DisplayTheme.SYSTEM,
    val onboardingCompletedVersion:Int=0) {
    init { require(fallbackFocal35mm.isFinite() && fallbackFocal35mm in 10.0..200.0); require(onboardingCompletedVersion in 0..1000000) }
}
data class PreferenceRead(val preferences:UserPreferences,val issue:PreferenceIssue?=null)
enum class PreferenceIssue { CORRUPT, UNSUPPORTED_VERSION, UNREADABLE }
object PreferenceCodec {
    const val VERSION=1
    const val MAX_BYTES=4096
    fun encode(value:UserPreferences):String="schema=1\nfocal=${value.fallbackFocal35mm}\ntheme=${value.theme.name}\nonboarding=${value.onboardingCompletedVersion}\n"
    fun decode(text:String):UserPreferences {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Preference file exceeds budget" }
        val pairs=text.lineSequence().filter { it.isNotEmpty() }.map { row ->
            require(row.count { it=='=' }==1); val parts=row.split('=',limit=2); parts[0] to parts[1]
        }.toList()
        require(pairs.map { it.first }.distinct().size==pairs.size) { "Duplicate preference field" }
        val fields=pairs.toMap()
        val version=fields["schema"]?.toIntOrNull()?:error("Missing preference schema")
        if(version!=VERSION) throw UnsupportedPreferenceVersion()
        require(fields.keys==setOf("schema","focal","theme","onboarding")) { "Unknown or missing preference field" }
        return UserPreferences(fields.getValue("focal").toDouble(),DisplayTheme.valueOf(fields.getValue("theme")),fields.getValue("onboarding").toInt())
    }
}
class UnsupportedPreferenceVersion:IllegalArgumentException("Unsupported preference schema")

/** Private local defaults only. Saving never changes a project/job's recorded input settings. */
class PreferencesStore(directory:File) {
    constructor(context:Context):this(File(context.filesDir,"user-preferences"))
    private val directory=directory.canonicalFile
    private val file=File(this.directory,"preferences.v1")
    private val guard=locks.computeIfAbsent(file.path) { Any() }
    fun read():PreferenceRead=synchronized(guard) {
        if(!file.exists()) return@synchronized PreferenceRead(UserPreferences())
        try {
            require(file.length()<=PreferenceCodec.MAX_BYTES)
            PreferenceRead(PreferenceCodec.decode(file.readText(Charsets.UTF_8)))
        } catch(_:UnsupportedPreferenceVersion) { PreferenceRead(UserPreferences(),PreferenceIssue.UNSUPPORTED_VERSION) }
        catch(_:IllegalArgumentException) { PreferenceRead(UserPreferences(),PreferenceIssue.CORRUPT) }
        catch(_:IllegalStateException) { PreferenceRead(UserPreferences(),PreferenceIssue.CORRUPT) }
        catch(_:java.io.IOException) { PreferenceRead(UserPreferences(),PreferenceIssue.UNREADABLE) }
    }
    fun save(value:UserPreferences) = synchronized(guard) {
        check(read().issue==null) { "Resolve unreadable or unsupported settings before saving" }
        write(value)
    }
    /** Explicit recovery preserves the rejected bytes for diagnosis, without sending them anywhere. */
    fun reset() = synchronized(guard) {
        if(file.exists()) {
            val backup=File(directory,"preferences.rejected.${System.currentTimeMillis()}")
            Files.copy(file.toPath(),backup.toPath())
        }
        write(UserPreferences())
    }
    private fun write(value:UserPreferences) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create private preferences directory" }
        val temporary=File.createTempFile("preferences-",".partial",directory)
        try {
            FileOutputStream(temporary).use { it.write(PreferenceCodec.encode(value).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}
