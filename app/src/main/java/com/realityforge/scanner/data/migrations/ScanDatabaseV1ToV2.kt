package com.realityforge.scanner.data.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive production-state migration. Room executes this inside the same migration
 * transaction as the generated DDL, so a failed integrity check cannot publish a
 * partially upgraded schema.
 */
class ScanDatabaseV1ToV2 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // Preserve version-1 ordering deterministically while leaving future capture
        // batches free to assign their own explicit order.
        db.execSQL(
            """
            UPDATE photos
            SET selectionOrder = (
                SELECT COUNT(*) - 1
                FROM photos AS ordered
                WHERE ordered.projectId = photos.projectId
                  AND (
                    ordered.addedAt < photos.addedAt OR
                    (ordered.addedAt = photos.addedAt AND ordered.id <= photos.id)
                  )
            )
            WHERE selectionOrder IS NULL
            """.trimIndent(),
        )

        // SQLite lower() handles ASCII only. Backfill the exact Unicode search/order
        // projection with the same locale-independent Kotlin operation as new projects.
        var afterId = ""
        db.compileStatement("UPDATE projects SET libraryNameKey = ? WHERE id = ?").use { update ->
            while (true) {
                val batch = db.query("SELECT id, name FROM projects WHERE id > ? ORDER BY id LIMIT 128",
                    arrayOf<Any>(afterId)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }
                if (batch.isEmpty()) break
                for ((id, name) in batch) {
                    update.clearBindings()
                    update.bindString(1, name.lowercase(java.util.Locale.ROOT))
                    update.bindString(2, id)
                    check(update.executeUpdateDelete() == 1)
                }
                afterId = batch.last().first
            }
        }

        db.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) {
                val table = cursor.getString(0)
                val rowId = cursor.getLong(1)
                "Foreign-key violation after Room migration in $table row $rowId."
            }
        }
    }
}
