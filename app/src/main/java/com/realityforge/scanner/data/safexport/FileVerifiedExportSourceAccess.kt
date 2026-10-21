package com.realityforge.scanner.data.safexport

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ResolvedExportFile(
    val identity: ExportSourceIdentity,
    val file: File,
)

fun interface ExportSourceFileResolver {
    suspend fun resolve(identity: ExportSourceIdentity): ResolvedExportFile?
}

class FileVerifiedExportSourceAccess(
    private val resolver: ExportSourceFileResolver,
    private val bufferSize: Int = 64 * 1024,
) : VerifiedExportSourceAccess {
    init {
        require(bufferSize in 4 * 1024..1024 * 1024) { "bufferSize is outside the supported range" }
    }

    override suspend fun verify(source: VerifiedExportSource): SourceVerification = withContext(Dispatchers.IO) {
        val resolved = resolver.resolve(source.identity)
            ?: return@withContext SourceVerification.Missing("No staged artifact exists for this identity.")
        if (resolved.identity != source.identity) {
            return@withContext SourceVerification.Changed(
                currentIdentity = resolved.identity,
                detail = "The resolver returned a different project revision or artifact.",
            )
        }
        val file = resolved.file
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return@withContext SourceVerification.Missing("The staged source file is missing.")
        }
        if (file.length() != source.expectedByteCount) {
            return@withContext SourceVerification.Invalid("The staged source length changed.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var observed = 0L
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) return@withContext SourceVerification.Invalid("The staged source stopped making progress.")
                    if (count.toLong() > source.expectedByteCount - observed)
                        return@withContext SourceVerification.Invalid("The staged source grew during verification.")
                    digest.update(buffer, 0, count)
                    observed = Math.addExact(observed, count.toLong())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withContext SourceVerification.Invalid(
                error.message ?: "The staged source could not be read.",
            )
        }
        SourceVerification.Verified(
            identity = resolved.identity,
            byteCount = observed,
            sha256 = digest.digest().toHex(),
        )
    }

    override suspend fun open(source: VerifiedExportSource): InputStream = withContext(Dispatchers.IO) {
        val resolved = resolver.resolve(source.identity)
            ?: throw SourceUnavailableException("The staged source no longer exists.")
        if (resolved.identity != source.identity) {
            throw SourceChangedException("The staged source now belongs to another identity.")
        }
        if (!Files.isRegularFile(resolved.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SourceUnavailableException("The staged source file is missing.")
        }
        FileInputStream(resolved.file)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
