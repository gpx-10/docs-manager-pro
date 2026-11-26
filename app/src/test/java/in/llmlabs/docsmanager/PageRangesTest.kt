package `in`.llmlabs.docsmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageRangesTest {
    @Test
    fun readsSinglePagesAndRangesInTheOrderTyped() {
        assertEquals(listOf(0..2, 4..4, 6..8), parsePageRanges("1-3, 5, 7-9", pageCount = 10))
        assertEquals(listOf(9..9, 0..1), parsePageRanges(" 10 ,1 - 2 ", pageCount = 10))
    }

    @Test
    fun rejectsRangesThatAreNotPagesOfTheDocument() {
        fun error(spec: String) = assertThrows(IllegalArgumentException::class.java) { parsePageRanges(spec, pageCount = 10) }.message
        assertEquals("Range \"0\" is outside 1-10", error("0"))
        assertEquals("Range \"11\" is outside 1-10", error("11"))
        assertEquals("Range \"5-3\" is outside 1-10", error("5-3"))
        assertEquals("Invalid range \"abc\"", error("abc"))
        assertEquals("Invalid range \"2-\"", error("2-"))
        assertEquals("Enter at least one page range", error(" , "))
    }
}
