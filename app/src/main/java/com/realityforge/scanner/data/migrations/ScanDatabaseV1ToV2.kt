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

        db.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) {
                val table = cursor.getString(0)
                val rowId = cursor.getLong(1)
                "Foreign-key violation after Room migration in $table row $rowId."
            }
        }
    }
}
