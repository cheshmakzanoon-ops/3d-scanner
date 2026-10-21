package com.realityforge.scanner.data.safexport

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun interface OwnedDocumentCleaner {
    fun delete(uri: Uri): Boolean
}

class AndroidSafDestinationStore(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver,
    private val cleaner: OwnedDocumentCleaner = OwnedDocumentCleaner { uri ->
        if (!DocumentsContract.isDocumentUri(context, uri)) {
            false
        } else {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    },
) : DestinationDocumentStore {
    override suspend fun prepare(request: BoundDestinationRequest): PreparedDestination {
        val uri = Uri.parse(request.destinationUri)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw DestinationUnavailableException("Only content:// document destinations are supported.")
        }
        return AndroidPreparedDestination(request, uri)
    }

    private inner class AndroidPreparedDestination(
        override val request: BoundDestinationRequest,
        private val uri: Uri,
    ) : PreparedDestination {
        override val writeUri: String = uri.toString()
        override val atomicity: ProviderAtomicity = ProviderAtomicity.DIRECT_NON_ATOMIC
        override val usesTemporaryDocument: Boolean = false

        override fun openOutput(): OutputStream = translateAccess("open destination for writing") {
            val stream = resolver.openOutputStream(uri, "wt")
                ?: throw DestinationUnavailableException("The provider returned no writable stream.")
            // Opening a descriptor can succeed before a later write/flush/close reports ENOSPC or revocation.
            object : OutputStream() {
                override fun write(value: Int) = translateAccess("write destination") { stream.write(value) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) =
                    translateAccess("write destination") { stream.write(bytes, offset, count) }
                override fun flush() = translateAccess("flush destination") { stream.flush() }
                override fun close() = translateAccess("close destination") { stream.close() }
            }
        }

        override fun openReadback(): InputStream? {
            if (!request.capabilities.canReadBack) return null
            return translateAccess("open destination for verification") {
                resolver.openInputStream(uri)
                    ?: throw DestinationUnavailableException("The provider returned no readable stream.")
            }
        }

        override fun querySize(): Long? {
            if (!request.capabilities.canQuerySize) return null
            return translateAccess("query destination size") {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                    CancellationSignal(),
                ).useSizeColumn()
            }
        }

        override suspend fun commit(): DestinationCommitResult = DestinationCommitResult.Committed(
            finalUri = uri.toString(),
            atomicity = atomicity,
        )

        override suspend fun abort(deleteOwnedIncomplete: Boolean): CleanupResult {
            if (request.ownership == DestinationOwnership.PRE_EXISTING) {
                return CleanupResult(
                    disposition = CleanupDisposition.PRE_EXISTING_RETAINED,
                    detail = "Pre-existing user documents are never deleted by transfer cleanup.",
                )
            }
            if (!deleteOwnedIncomplete || !request.capabilities.canDeleteOwnedIncomplete) {
                return CleanupResult(
                    disposition = CleanupDisposition.OWNED_DELETE_UNSUPPORTED,
                    detail = "The provider does not support safe deletion of the newly created incomplete document.",
                )
            }
            return try {
                if (cleaner.delete(uri)) {
                    CleanupResult(CleanupDisposition.OWNED_PARTIAL_DELETED)
                } else {
                    CleanupResult(
                        CleanupDisposition.OWNED_DELETE_FAILED,
                        "The provider did not delete the newly created incomplete document.",
                    )
                }
            } catch (error: Exception) {
                CleanupResult(CleanupDisposition.OWNED_DELETE_FAILED, error.message)
            }
        }
    }

    private inline fun <T> translateAccess(operation: String, block: () -> T): T {
        try {
            return block()
        } catch (error: DestinationAccessException) {
            throw error
        } catch (error: SecurityException) {
            throw DestinationPermissionException("Cannot $operation: permission was denied or revoked.", error)
        } catch (error: FileNotFoundException) {
            throw DestinationUnavailableException("Cannot $operation: the document is unavailable.", error)
        } catch (error: IOException) {
            if (error.hasNoSpaceCause()) {
                throw DestinationStorageFullException("Cannot $operation: the provider reported insufficient space.", error)
            }
            throw DestinationWriteException("Cannot $operation: ${error.message ?: "provider I/O failure"}.", error)
        }
    }

    private fun Cursor?.useSizeColumn(): Long? {
        if (this == null) return null
        use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) return null
            return cursor.getLong(index).takeIf { it >= 0 }
        }
    }

    private fun Throwable.hasNoSpaceCause(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth++ < 16) {
            val errno = (current as? ErrnoException)?.errno
            if (errno == OsConstants.ENOSPC || errno == OsConstants.EDQUOT) return true
            current = current.cause
        }
        return false
    }
}
