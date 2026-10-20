package com.realityforge.scanner.engine

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Pure-JVM identity for M18 CPU depth checkpoints; no photo leaves this device. */
internal object DepthCheckpointIdentity {
    private val HASH = Regex("[0-9a-f]{64}")

    fun fingerprint(
        artifactKey: String, rgba: ByteArray, cameraRows: DoubleArray,
        width: Int, height: Int, near: Double, far: Double, planes: Int, stride: Int,
    ): String {
        require(artifactKey.matches(Regex("sparse-m[0-9]{2}-r[0-9]+-[0-9a-f]{16}")))
        require(width in 32..640 && height in 32..640 && width.toLong()*height <= 320000)
        require(cameraRows.size in 32..128 && cameraRows.size % 16 == 0)
        require(rgba.size.toLong() == width.toLong()*height*4*(cameraRows.size/16))
        require(cameraRows.all { it.isFinite() } && near.isFinite() && far.isFinite() &&
            near > 0 && far > near && planes in 12..128 && stride in 2..8)
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update("realityforge-m18-native-reference-depth-v1\u0000".toByteArray(StandardCharsets.UTF_8))
        digest.update(artifactKey.toByteArray(StandardCharsets.UTF_8))
        fun long(value: Long) {
            for(i in 0 until 8) digest.update((value ushr (8*i)).toByte())
        }
        long(width.toLong());long(height.toLong());long(cameraRows.size.toLong())
        long(planes.toLong());long(stride.toLong())
        long(near.toBits());long(far.toBits())
        cameraRows.forEach { long(it.toBits()) }
        digest.update(rgba)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Bound disk cache to two identities per project; only validated hash directories. */
    fun directory(projectDirectory: File, identity: String): File {
        require(identity.matches(HASH)) { "Invalid depth checkpoint identity" }
        val root=File(projectDirectory,".depth-checkpoints-m18")
        check(root.isDirectory || root.mkdirs()) { "Cannot create private depth-cache directory" }
        val chosen=File(root,identity)
        check(chosen.isDirectory || chosen.mkdirs()) { "Cannot create scan-specific checkpoint directory" }
        chosen.setLastModified(System.currentTimeMillis())
        // Android process death can strand incomplete write siblings. None may
        // be loaded as a completed reference; discard them on the next launch.
        chosen.listFiles().orEmpty().filter { it.isFile &&
            it.name.matches(Regex("ref-[0-7]\\.bin\\.partial-[0-9]+"))
        }.forEach { it.delete() }
        val stale=root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(HASH) && it != chosen }
            .sortedByDescending { it.lastModified() }
        // Existing active scans cannot be deleted via ViewModel's exclusive operation lease.
        stale.drop(1).forEach { old -> old.deleteRecursively() }
        return chosen
    }
}
