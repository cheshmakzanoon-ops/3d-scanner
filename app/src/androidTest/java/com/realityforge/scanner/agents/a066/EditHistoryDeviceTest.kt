package com.realityforge.scanner.agents.a066

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.studio.editing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Actual Android private-file roundtrip; this does not claim external process-kill recovery. */
@RunWith(AndroidJUnit4::class)
class EditHistoryDeviceTest {
    @Test fun privateRecipeRoundtripKeepsSourceAndRedo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "a066-${System.nanoTime()}").apply { mkdirs() }
        try {
            val master = File(directory, "synthetic-master.bin").apply { writeBytes(byteArrayOf(7, 1, 8, 2)) }
            val hash = MessageDigest.getInstance("SHA-256").digest(master.readBytes()).joinToString("") { "%02x".format(it) }
            val controller = EditController(EditRecipe("synthetic-master", hash))
            controller.apply(EditOperation.Crop(EditPoint(-1.0,-1.0,-1.0), EditPoint(1.0,1.0,1.0)))
            controller.apply(EditOperation.KeepComponents(setOf(0)))
            assertTrue(controller.undo())
            val recipe = File(directory, "recipe.rfe").apply { writeBytes(controller.save()) }
            val reopened = EditController.restore(recipe.readBytes())
            assertEquals(controller.recipe, reopened.recipe); assertTrue(reopened.redo())
            assertArrayEquals(byteArrayOf(7, 1, 8, 2), master.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
