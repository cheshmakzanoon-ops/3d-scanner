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
    /** Location for the owning pipeline to bind an already-verified cache to its checkpoint. */
    internal fun artifactFile(identity: FeatureCacheIdentity): File = File(root(), key(identity))
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
            withMutationLock(root) {
                require(!Files.isSymbolicLink(target.toPath()))
                temporary=File.createTempFile("feature-",".partial",root)
                FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
                Files.move(requireNotNull(temporary).toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                try { evict(root,target) } catch(failure:Exception) {
                    // A failed cache transaction cannot keep a new over-budget publication.
                    target.delete()
                    throw failure
                }
                true
            }
        }catch(_:Exception){false}finally{temporary?.delete()}
    }
    @Synchronized fun invalidate(identity:FeatureCacheIdentity):Boolean = try {
        val root=root();withMutationLock(root){File(root,key(identity)).delete()}
    }catch(_:Exception){false}
    private fun <T> withMutationLock(root:File,block:()->T):T {
        val stripe=mutationStripes[(root.path.hashCode() and Int.MAX_VALUE)%mutationStripes.size]
        val locallyAcquired=try { stripe.tryLock(500,java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch(interrupted:InterruptedException){Thread.currentThread().interrupt();throw interrupted}
        if(!locallyAcquired)throw java.io.IOException("Feature cache writer busy")
        try {
            val path=File(root,".feature-cache.lock").toPath()
            require(!Files.isSymbolicLink(path))
            return java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{channel->
                var lease:java.nio.channels.FileLock?=null
                for(attempt in 0 until 50){
                    lease=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
                    if(lease!=null)break
                    Thread.sleep(10)
                }
                val acquired=lease?:throw java.io.IOException("Feature cache process writer busy")
                acquired.use { block() }
            }
        }catch(interrupted:InterruptedException){Thread.currentThread().interrupt();throw interrupted}
        finally{stripe.unlock()}
    }
    companion object {
        // Bounded same-JVM coordination avoids OverlappingFileLockException across instances;
        // the reserved NOFOLLOW file lease also serializes cooperating Android processes.
        private val mutationStripes=Array(64){java.util.concurrent.locks.ReentrantLock()}
    }
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
