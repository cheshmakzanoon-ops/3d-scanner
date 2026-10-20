package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactDescriptor
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCleanupCancellation
import com.realityforge.scanner.data.cachegc.CacheReuseRejectionCode
import com.realityforge.scanner.data.cachegc.CacheReuseRequest
import com.realityforge.scanner.data.cachegc.CacheReuseValidation
import com.realityforge.scanner.data.cachegc.CacheReuseValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class CacheReuseValidatorTest {
    @Test
    fun exactHashAndSizeAreRequiredForReuse() {
        val base = Files.createTempDirectory("rf-agent-020-reuse")
        val root = Files.createDirectories(base.resolve("project"))
        try {
            val path = root.resolve("depth/identity/ref-0.bin")
            Files.createDirectories(path.parent)
            val bytes = ByteArray(8192) { index -> (index * 31).toByte() }
            Files.write(path, bytes)
            val descriptor = CacheArtifactDescriptor(
                relativePath = "depth/identity/ref-0.bin",
                category = CacheArtifactCategory.DEPTH_CHECKPOINT,
                state = CacheArtifactState.REUSABLE,
                identity = "depth-identity",
                revision = 7,
                expectedBytes = bytes.size.toLong(),
                expectedSha256 = sha256(bytes),
            )
            val validator = CacheReuseValidator()
            val request = CacheReuseRequest("project-020", "manifest-reuse", descriptor)
            val valid = validator.validate(root, request)
            assertTrue(valid is CacheReuseValidation.Valid)

            Files.write(path, bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
            val corrupt = validator.validate(root, request) as CacheReuseValidation.Rejected
            assertEquals(CacheReuseRejectionCode.HASH_MISMATCH, corrupt.code)

            Files.write(path, bytes.copyOf(bytes.size - 1))
            val short = validator.validate(root, request) as CacheReuseValidation.Rejected
            assertEquals(CacheReuseRejectionCode.SIZE_MISMATCH, short.code)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationAndResourceLimitAreTypedRejections() {
        val base = Files.createTempDirectory("rf-agent-020-reuse-bounds")
        val root = Files.createDirectories(base.resolve("project"))
        try {
            val path = root.resolve("features/current.bin")
            Files.createDirectories(path.parent)
            val bytes = ByteArray(4096) { 7 }
            Files.write(path, bytes)
            val descriptor = CacheArtifactDescriptor(
                relativePath = "features/current.bin",
                category = CacheArtifactCategory.FEATURE_CACHE,
                state = CacheArtifactState.CURRENT,
                identity = "feature-current",
                expectedBytes = bytes.size.toLong(),
                expectedSha256 = sha256(bytes),
            )
            val request = CacheReuseRequest("project-020", "manifest-reuse", descriptor)
            val cancelled = CacheReuseValidator().validate(root, request, CacheCleanupCancellation { true })
                as CacheReuseValidation.Rejected
            assertEquals(CacheReuseRejectionCode.CANCELLED, cancelled.code)

            val tooLarge = CacheReuseValidator(maxBytes = 100).validate(root, request)
                as CacheReuseValidation.Rejected
            assertEquals(CacheReuseRejectionCode.RESOURCE_LIMIT, tooLarge.code)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
