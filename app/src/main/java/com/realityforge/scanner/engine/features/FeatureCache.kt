package com.realityforge.scanner.engine.features

import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed class FeatureCacheRead {
    data class Hit(val record:CachedFeatures):FeatureCacheRead()
    data class Miss(val reason:Reason):FeatureCacheRead()
    enum class Reason { ABSENT, STALE_OR_CORRUPT, IO_UNAVAILABLE }
}

/** Dedicated derived-cache root. Only hash-named .rffc entries are candidates for eviction.
 * Failure means recompute, never a completed scan or deletion of a photograph/model.
 */
class FeatureCache(private val directory:File,private val byteBudget:Long) {
    init {require(byteBudget in 0..4L*1024*1024*1024)}
    private fun key(identity:FeatureCacheIdentity):String {
        val text=listOf(identity.originalSha256,identity.decoderTransform,identity.extractorSettings,identity.openCvVersion).joinToString(""){"${it.toByteArray(Charsets.UTF_8).size}:$it"}
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}+".rffc"
    }
    private fun root():File {
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs()) { "Cache directory unavailable" }
        require(directory.canonicalFile.toPath()==directory.absoluteFile.toPath().normalize()) { "Symbolic cache ancestor" }
        return directory.canonicalFile
    }
    @Synchronized fun load(identity:FeatureCacheIdentity):FeatureCacheRead = try {
        val file=File(root(),key(identity))
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)) FeatureCacheRead.Miss(FeatureCacheRead.Reason.ABSENT)
        else if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) || file.length() !in 44..FeatureCacheFormat.MAX_BYTES.toLong()) FeatureCacheRead.Miss(FeatureCacheRead.Reason.STALE_OR_CORRUPT)
        else {
            val record=Files.newByteChannel(file.toPath(),setOf(java.nio.file.StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use{channel->
                val boundedSize=channel.size()
                require(boundedSize in 44..FeatureCacheFormat.MAX_BYTES.toLong())
                DataInputStream(java.nio.channels.Channels.newInputStream(channel).buffered()).use{input->
                    val bytes=ByteArray(boundedSize.toInt());input.readFully(bytes);require(input.read()==-1)
                    FeatureCacheFormat.decode(bytes,identity)
                }
            }
            file.setLastModified(System.currentTimeMillis())
            FeatureCacheRead.Hit(record)
        }
    }catch(_:IllegalArgumentException){FeatureCacheRead.Miss(FeatureCacheRead.Reason.STALE_OR_CORRUPT)}
    catch(_:java.io.EOFException){FeatureCacheRead.Miss(FeatureCacheRead.Reason.STALE_OR_CORRUPT)}
    catch(_:Exception){FeatureCacheRead.Miss(FeatureCacheRead.Reason.IO_UNAVAILABLE)}

    @Synchronized fun store(record:CachedFeatures):Boolean {
        val bytes=FeatureCacheFormat.encode(record)
        if(bytes.size>byteBudget)return false
        var temporary:File?=null
        return try {
            val root=root();val target=File(root,key(record.identity))
            require(!Files.isSymbolicLink(target.toPath()))
            temporary=File.createTempFile("feature-",".partial",root)
            FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            evict(root,target)
            true
        }catch(_:Exception){false}finally{temporary?.delete()}
    }
    @Synchronized fun invalidate(identity:FeatureCacheIdentity):Boolean = try {File(root(),key(identity)).delete()}catch(_:Exception){false}
    private fun evict(root:File,retained:File) {
        val files=root.listFiles()?.filter{it.name.matches(Regex("[a-f0-9]{64}\\.rffc")) && Files.isRegularFile(it.toPath(),LinkOption.NOFOLLOW_LINKS)}?:emptyList()
        var bytes=files.sumOf{it.length()}
        for(file in files.sortedWith(compareBy<File>{it.lastModified()}.thenBy{it.name})) {
            if(bytes<=byteBudget)break
            if(file!=retained){val size=file.length();check(file.delete()){ "Cache eviction failed" };bytes-=size}
        }
        check(bytes<=byteBudget)
    }
}
