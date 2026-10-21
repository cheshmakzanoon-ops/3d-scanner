package com.realityforge.scanner.agents.a066

import com.realityforge.scanner.studio.editing.*
import org.junit.Assert.*
import org.junit.Test

class EditControllerTest {
    private fun controller() = EditController(EditRecipe("immutable-parent", "a".repeat(64)))
    private val crop = EditOperation.Crop(EditPoint(0.0, 0.0, 0.0), EditPoint(1.0, 2.0, 3.0))

    @Test fun undoRedoAndReopenPreserveExactHistory() {
        val c = controller(); c.apply(crop); c.apply(EditOperation.KeepComponents(setOf(2, 0)))
        assertTrue(c.undo()); val saved = c.save(); val restored = EditController.restore(saved)
        assertEquals(c.recipe, restored.recipe); assertTrue(restored.canRedo)
        assertArrayEquals(c.materializationBytes(), EditController(c.recipe).save())
        assertArrayEquals(c.materializationBytes(), restored.materializationBytes())
        assertTrue(restored.redo()); assertEquals(2, restored.recipe.operations.size)
        assertTrue(restored.undo()); restored.apply(crop); assertFalse(restored.canRedo)
        assertEquals("immutable-parent", restored.recipe.parentAssetId)
    }
    @Test fun corruptedUnsupportedAndTrailingHistoriesAreRejected() {
        val bytes = controller().save(); bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { EditController.restore(bytes) }
        assertThrows(IllegalArgumentException::class.java) { EditController(EditRecipe("p", "a".repeat(64), schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { EditController.restore(ByteArray(131073)) }
    }
    @Test fun metricEvidenceCannotUseZeroLengthOrInventedScale() {
        val c = controller(); val zero = EditPoint(0.0,0.0,0.0)
        assertThrows(IllegalArgumentException::class.java) { c.apply(EditOperation.MetricReference(zero, zero, 1.0, 0.0, "reference")) }
        c.apply(EditOperation.MetricReference(zero, EditPoint(2.0,0.0,0.0), .4, .002, "ruler endpoints"))
        assertEquals(c.recipe, EditController.restore(c.save()).recipe)
        val scale = listOf(2.0,0.0,0.0,0.0,0.0,2.0,0.0,0.0,0.0,0.0,2.0,0.0,0.0,0.0,0.0,1.0)
        assertThrows(IllegalArgumentException::class.java) { c.apply(EditOperation.Transform(scale)) }
    }
    @Test fun callerMutationDoesNotChangeAcceptedRecipe() {
        val ids = mutableSetOf(0, 1); val c = controller(); c.apply(EditOperation.KeepComponents(ids)); ids.clear()
        assertEquals(setOf(0, 1), (c.recipe.operations.single() as EditOperation.KeepComponents).componentIds)
    }
    @Test fun reflectionRoundTripsAndRejectedBudgetPreservesHistory() {
        val c = controller()
        val reflection = listOf(-1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0)
        c.apply(EditOperation.Transform(reflection)); assertEquals(c.recipe, EditController.restore(c.save()).recipe)
        val large = EditOperation.KeepComponents((0..1023).toSet())
        var refused = false
        for (attempt in 0..40) {
            val before = c.recipe
            try { c.apply(large) } catch (_: IllegalArgumentException) {
                assertEquals(before, c.recipe); refused = true; break
            }
        }
        assertTrue(refused)
        assertTrue(c.save().size <= 128 * 1024)
    }
}
