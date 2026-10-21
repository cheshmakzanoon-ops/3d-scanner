package com.realityforge.scanner.agents.a072
import com.realityforge.scanner.ui.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
class LibraryQueryTest {
    private val projects = listOf(
        LibraryProject("b","İstanbul",1,0,"INTERRUPTED","",false),
        LibraryProject("a","Alpha",2,1,"READY","",true),
        LibraryProject("c","alpha",2,1,"FAILED","",false))
    @Test fun stableOrderingAndFilteringPreserveIdentity() {
        assertEquals(listOf("a","c","b"), LibraryQuery.apply(projects,"",LibrarySort.NEWEST,LibraryFilter.ALL).map { it.id })
        assertEquals(listOf("a"), LibraryQuery.apply(projects,"ALPHA",LibrarySort.NAME,LibraryFilter.RESULTS).map { it.id })
        assertEquals(listOf("c","b"), LibraryQuery.apply(projects,"",LibrarySort.NEWEST,LibraryFilter.NEEDS_ATTENTION).map { it.id })
        assertEquals(3, projects.size)
    }
    @Test fun needsAttentionIncludesRecoveryAndExistingFailuresButNotOtherStatesOrTombstones() {
        val states = listOf("INTERRUPTED", "FAILED", "ERROR", "RECOVERY_REQUIRED", "NEW", "READY",
            "PROCESSING", "COMPLETE", "DELETING", "DELETED", "DELETING_PHOTO")
        val rows = states.mapIndexed { index, state ->
            LibraryProject(state, "Item $index", index.toLong(), 0, state, "", false)
        }
        val selected = LibraryQuery.apply(rows, "", LibrarySort.NEWEST, LibraryFilter.NEEDS_ATTENTION)
        assertEquals(listOf("RECOVERY_REQUIRED", "ERROR", "FAILED", "INTERRUPTED"), selected.map { it.state })
    }
    @Test fun queryIsIndependentOfTurkishDeviceLocale() {
        val old=Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals(2,LibraryQuery.apply(projects,"ALPHA",LibrarySort.NAME,LibraryFilter.ALL).size) }
        finally { Locale.setDefault(old) }
    }
    @Test fun boundedNamesRejectEmptyControlsAndOverlongInputs() {
        assertTrue(LibraryQuery.validName("  shoes "))
        assertTrue(LibraryQuery.validName("📷".repeat(80))); assertFalse(LibraryQuery.validName("📷".repeat(81)))
        assertFalse(LibraryQuery.validName("  ")); assertFalse(LibraryQuery.validName("a\nb")); assertFalse(LibraryQuery.validName("a".repeat(81)))
    }
    @Test fun tenThousandRowsHaveStableBoundedLazyListKeys() {
        val values=(0 until 10000).map { LibraryProject(it.toString(),"Item $it",it.toLong(),0,"NEW","",false) }
        val sorted=LibraryQuery.apply(values,"Item 99",LibrarySort.NEWEST,LibraryFilter.ALL)
        assertEquals(111,sorted.size); assertEquals("9999",sorted.first().id); assertEquals(sorted.size,sorted.map { it.id }.toSet().size)
    }
}
