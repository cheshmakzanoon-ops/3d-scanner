package com.realityforge.scanner.hostintegration

import android.app.Application
import androidx.room.Room
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * HOST integration only: generated Room DAO + AOSP native SQLite + host filesystem.
 * Synthetic row metadata; no image decoder, app JNI, UI, GPU, or device qualification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoomHostSmokeTest {
    @Test(timeout = 120_000) fun expectedRevisionPublicationPersistsAcrossDatabaseReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "room-host-smoke-${UUID.randomUUID()}.db"
        val project = ScanProject(UUID.randomUUID().toString(), "Synthetic host smoke", 1L)
        val photoId = UUID.randomUUID().toString()
        val stalePhotoId = UUID.randomUUID().toString()
        val photo = ScanPhoto(
            id = photoId, projectId = project.id,
            filename = "$photoId.image", sha256 = "a".repeat(64), bytes = 4,
            width = 2, height = 2, focal35mm = null,
            sharpness = 0.0, darkFraction = 0.0, brightFraction = 0.0, addedAt = 1L,
        )
        try {
            val database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            try {
                val dao = database.dao()
                dao.insert(project)
                assertEquals(1L, dao.addPhotoExpected(photo, 0))
                try {
                    dao.addPhotoExpected(photo.copy(id = stalePhotoId, filename = "$stalePhotoId.image", sha256 = "b".repeat(64)), 0)
                    fail("Stale expected revision must not insert a photo")
                } catch (_: IllegalStateException) {
                    // Production @Transaction must leave the committed row and revision unchanged.
                }
                assertEquals(listOf(photo), dao.photos(project.id))
                assertEquals(1L, dao.project(project.id)?.revision)
            } finally {
                database.close()
            }
            assertNotNull(context.getDatabasePath(name).takeIf { it.isFile })
            val reopened = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            try {
                assertEquals(1L, reopened.dao().project(project.id)?.revision)
                assertEquals(listOf(photo), reopened.dao().photos(project.id))
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
