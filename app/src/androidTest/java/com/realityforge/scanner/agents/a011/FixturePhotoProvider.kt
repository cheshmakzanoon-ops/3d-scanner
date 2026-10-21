package com.realityforge.scanner.agents.a011

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Test APK only: a fixed synthetic corpus, with deliberately false MIME/size and revoked access. */
class FixturePhotoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val key = fixtureKey(uri)
        if (key == "revoked") throw SecurityException("Synthetic provider revoked this document")
        val folder = File(requireNotNull(context).cacheDir, "a011-provider-fixtures").apply { check(isDirectory || mkdir()) }
        val file = File(folder, "$key.fixture")
        if (!file.exists()) {
            if (key == "corrupt") file.writeText("This is not an encoded photograph.")
            else {
                val edge = if (key == "large-png") 1024 else 80
                val height = if (key == "large-png") 1024 else 64
                val bitmap = Bitmap.createBitmap(edge, height, Bitmap.Config.ARGB_8888)
                try {
                    var state = 0x13579bdf
                    val pixels = IntArray(edge * height) {
                        state = state xor (state shl 13)
                        state = state xor (state ushr 17)
                        state = state xor (state shl 5)
                        -0x1000000 or (state and 0x00ffffff)
                    }
                    bitmap.setPixels(pixels, 0, edge, 0, 0, edge, height)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String { fixtureKey(uri); return "image/jpeg" }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        fixtureKey(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> "misleading-name.jpg"
                OpenableColumns.SIZE -> Long.MAX_VALUE
                else -> null
            } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only fixture")

    private fun fixtureKey(uri: Uri): String {
        val key = uri.pathSegments.singleOrNull()
        if (uri.authority != AUTHORITY || key !in setOf("pattern-png", "large-png", "corrupt", "revoked")) {
            throw FileNotFoundException("Unknown synthetic fixture")
        }
        return requireNotNull(key)
    }

    companion object { const val AUTHORITY = "com.realityforge.scanner.test.a011.photo-fixture" }
}
