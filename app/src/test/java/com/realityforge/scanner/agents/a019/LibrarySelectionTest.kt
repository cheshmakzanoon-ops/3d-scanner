package com.realityforge.scanner.agents.a019

import com.realityforge.scanner.data.library.*
import org.junit.Assert.*
import org.junit.Test

class LibrarySelectionTest {
    private fun record(id: String, name: String, created: Long, state: String = "READY") =
        ProjectSummary(id, name, created, 0, state, 0, false)

    @Test fun unicodeNamesAndInvalidNames() {
        assertEquals("作品 🏝️", LibrarySelection.validatedName(" 作品 🏝️ "))
        assertEquals("é", LibrarySelection.validatedName("é"))
        for (value in listOf("", "  ", "\n", "x".repeat(81), "bad\u0000name", "bad\uD800name")) {
            try { LibrarySelection.validatedName(value); fail("Accepted invalid name") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("😀".repeat(80), LibrarySelection.validatedName("😀".repeat(80)))
    }

    @Test fun unicodeCodePointOrderingMatchesDurableUtf8KeysAcrossSupplementaryCharacters() {
        val fixture = listOf(record("emoji", "😀", 0), record("fullwidth", "\uFF21", 0),
            record("greek-upper", "Ω", 0), record("greek-lower", "ω", 0), record("latin", "É", 0))
        assertEquals(listOf("latin", "greek-lower", "greek-upper", "fullwidth", "emoji"),
            LibrarySelection.page(fixture, LibraryQuery(order = LibraryOrder.NAME_ASC)).map { it.id })
        assertEquals(listOf("emoji", "fullwidth", "greek-lower", "greek-upper", "latin"),
            LibrarySelection.page(fixture, LibraryQuery(order = LibraryOrder.NAME_DESC)).map { it.id })
    }

    @Test fun deterministicPagingWhenTimestampsOrNamesTie() {
        val fixture = listOf(record("b", "Beta", 5), record("a", "beta", 5),
            record("c", "alpha", 7), record("d", "Beta", 1))
        assertEquals(listOf("c", "a", "b", "d"),
            LibrarySelection.page(fixture, LibraryQuery()).map { it.id })
        assertEquals(listOf("a", "b"), LibrarySelection.page(fixture,
            LibraryQuery(order = LibraryOrder.NAME_ASC, offset = 1, limit = 2)).map { it.id })
        assertEquals(listOf("d", "a", "b"), LibrarySelection.page(fixture,
            LibraryQuery(search = "BE", order = LibraryOrder.OLDEST)).map { it.id })
        assertEquals(listOf("c"), LibrarySelection.page(fixture,
            LibraryQuery(states = setOf("READY"), search = "ALP")).map { it.id })
    }

    @Test fun boundedPagesForLargeFixture() {
        val fixture = (0 until 10000).map { record(it.toString().padStart(6,'0'), "Item $it", it.toLong()) }
        val first = LibrarySelection.page(fixture, LibraryQuery(limit = 100))
        val second = LibrarySelection.page(fixture, LibraryQuery(offset = 100, limit = 100))
        assertEquals(100, first.size)
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        assertEquals("009999", first.first().id)
        try { LibraryQuery(limit = 101); fail("Unbounded limit accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
