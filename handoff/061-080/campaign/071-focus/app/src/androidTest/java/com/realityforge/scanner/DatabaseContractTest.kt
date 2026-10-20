package com.realityforge.scanner

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseContractTest {
    @Test fun revisionInvalidationAndCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insert(ScanProject("test", "Fixture", 0))
            dao.addPhoto(ScanPhoto("p1", "test", "photo.image", "abc", 10, 10, 10, null, 1.0, 0.0, 0.0, 0))
            assertEquals(1L, dao.project("test")!!.revision)
            assertEquals(0, dao.complete("test", 0, "key", "stale result"))
            assertEquals(1, dao.complete("test", 1, "key", "current result"))
            dao.addPhoto(ScanPhoto("p2", "test", "photo2.image", "def", 10, 10, 10, null, 1.0, 0.0, 0.0, 0))
            assertNull(dao.project("test")!!.resultRevision)
            dao.deleteProject("test")
            assertTrue(dao.photos("test").isEmpty())
        } finally { db.close() }
    }
    @Test fun interruptedSessionCannotStayProcessing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insert(ScanProject("test", "Fixture", 0, state = "PROCESSING"))
            dao.recoverInterrupted()
            assertEquals("INTERRUPTED", dao.project("test")!!.state)
        } finally { db.close() }
    }
}
