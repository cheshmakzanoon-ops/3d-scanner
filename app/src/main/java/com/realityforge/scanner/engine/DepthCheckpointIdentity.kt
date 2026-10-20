package com.realityforge.scanner.engine

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Pure-JVM identity for the existing M18 CPU depth checkpoints; no photo leaves this device. */
internal object DepthCheckpointIdentity {
    private val HASH = Regex("[0-9a-f]{64}")
    private const val M18_DOMAIN = "realityforge-m18-native-reference-depth-v1\u0000"

    /**
     * The native binary layout remains owned by the depth-checkpoint implementation. Unknown
     * compatibility versions are rejected instead of silently reusing M18 bytes under a new name.
     */
    data class Compatibility(
        val schemaVersion: Int,
        val algorithmDomain: String,
    ) {
        init {
            require(schemaVersion > 0) { "Depth checkpoint schema must be positive" }
            require(algorithmDomain.toByteArray(StandardCharsets.UTF_8).size in 1..128) {
                "Depth checkpoint algorithm domain is out of bounds"
            }
            require(algorithmDomain.endsWith('\u0000')) { "Depth checkpoint algorithm domain must be separated" }
        }
    }

    val M18_COMPATIBILITY = Compatibility(18, M18_DOMAIN)

    fun supports(compatibility: Compatibility): Boolean = compatibility == M18_COMPATIBILITY

    /**
     * Production geometry is content-addressed independently of the owning project revision.
     * The versioned key intentionally misses older revision-bound entries once; native M18
     * layout validation remains unchanged. Exact sparse/C8 and pixel/camera digests still gate reuse.
     */
    fun contentGeometryKey(sparseIdentitySha256: String): String {
        require(sparseIdentitySha256.matches(HASH)) { "Invalid sparse geometry identity" }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            ("realityforge-depth-content-key-v1\u0000" + sparseIdentitySha256).toByteArray(StandardCharsets.US_ASCII),
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "sparse-m16-r0-${digest.take(16)}"
    }

    fun fingerprint(
        artifactKey: String,
        rgba: ByteArray,
        cameraRows: DoubleArray,
        width: Int,
        height: Int,
        near: Double,
        far: Double,
        planes: Int,
        stride: Int,
        compatibility: Compatibility = M18_COMPATIBILITY,
        resourceSelectionIdentity: String? = null,
    ): String {
        require(supports(compatibility)) { "Unsupported depth checkpoint compatibility version" }
        require(resourceSelectionIdentity == null || resourceSelectionIdentity.matches(HASH)) {
            "Invalid resource-selection identity"
        }
        require(artifactKey.matches(Regex("sparse-m[0-9]{2}-r[0-9]+-[0-9a-f]{16}")))
        require(width in 32..640 && height in 32..640 && width.toLong() * height <= 320000)
        require(cameraRows.size in 32..128 && cameraRows.size % 16 == 0)
        require(rgba.size.toLong() == width.toLong() * height * 4 * (cameraRows.size / 16))
        require(
            cameraRows.all { it.isFinite() } && near.isFinite() && far.isFinite() &&
                near > 0 && far > near && planes in 12..128 && stride in 2..8,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(compatibility.algorithmDomain.toByteArray(StandardCharsets.UTF_8))
        digest.update(artifactKey.toByteArray(StandardCharsets.UTF_8))
        fun long(value: Long) {
            for (i in 0 until 8) digest.update((value ushr (8 * i)).toByte())
        }
        long(width.toLong())
        long(height.toLong())
        long(cameraRows.size.toLong())
        long(planes.toLong())
        long(stride.toLong())
        long(near.toBits())
        long(far.toBits())
        cameraRows.forEach { long(it.toBits()) }
        digest.update(rgba)
        if (resourceSelectionIdentity != null) {
            digest.update("\u0000realityforge-resource-selection-v1\u0000".toByteArray(StandardCharsets.UTF_8))
            digest.update(resourceSelectionIdentity.toByteArray(StandardCharsets.US_ASCII))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Resolve only this validated identity. Retention belongs to the manifest-aware collector. */
    fun directory(
        projectDirectory: File,
        identity: String,
        compatibility: Compatibility = M18_COMPATIBILITY,
    ): File {
        require(supports(compatibility)) { "Unsupported depth checkpoint compatibility version" }
        require(identity.matches(HASH)) { "Invalid depth checkpoint identity" }
        check(Files.isDirectory(projectDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Invalid private project directory"
        }
        val project = projectDirectory.canonicalFile
        val root = File(project, ".depth-checkpoints-m18")
        check(root.exists() || root.mkdir()) { "Cannot create private depth-cache directory" }
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) && root.canonicalFile.parentFile == project) {
            "Invalid private depth-cache directory"
        }
        val chosen = File(root, identity)
        check(chosen.exists() || chosen.mkdir()) { "Cannot create scan-specific checkpoint directory" }
        check(Files.isDirectory(chosen.toPath(), LinkOption.NOFOLLOW_LINKS) && chosen.canonicalFile.parentFile == root) {
            "Invalid scan-specific checkpoint directory"
        }
        // A timestamp or .partial filename cannot establish a writer token or prove that a
        // resumable job has released its bytes. Owner020 reclaims only verified unprotected paths.
        return chosen
    }
}
