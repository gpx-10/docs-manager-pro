package `in`.llmlabs.docsmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerStateTest {
    @Test
    fun goingToAPageStaysWithinTheDocument() {
        val state = ViewerState(pageCount = 12)
        assertEquals("5 / 12", state.goTo(4).pageLabel)
        assertEquals("12 / 12", state.goTo(40).pageLabel)
        assertEquals("1 / 12", state.goTo(-3).pageLabel)
    }

    @Test
    fun zoomStaysBetweenFitWidthAndFiveTimes() {
        val state = ViewerState(pageCount = 3)
        assertEquals(2f, state.zoomBy(2f).zoom, 0.001f)
        assertEquals(5f, state.zoomBy(3f).zoomBy(3f).zoom, 0.001f)
        assertEquals(1f, state.zoomBy(0.2f).zoom, 0.001f)
    }

    @Test
    fun pageCounterFollowsWhatIsMostlyOnScreenAndReachesTheLastPage() {
        val state = ViewerState(pageCount = 31)
        // Scrolled to the very end: the last page is showing, even if the one before is at the top.
        assertEquals("31 / 31", state.scrolledTo(firstVisible = 29, firstHiddenFraction = 0.2f, atEnd = true).pageLabel)
        // More than half of the top page is scrolled away: the next page is the one being read.
        assertEquals("6 / 31", state.scrolledTo(firstVisible = 4, firstHiddenFraction = 0.6f, atEnd = false).pageLabel)
        assertEquals("5 / 31", state.scrolledTo(firstVisible = 4, firstHiddenFraction = 0.3f, atEnd = false).pageLabel)
    }

    @Test
    fun typedPageNumbersOutsideTheDocumentAreRejected() {
        val state = ViewerState(pageCount = 31)
        assertEquals(6, state.pageIndexFor("7"))
        assertEquals(30, state.pageIndexFor("31"))
        assertEquals(null, state.pageIndexFor("0"))
        assertEquals(null, state.pageIndexFor("32"))
        assertEquals(null, state.pageIndexFor(""))
        assertEquals(null, state.pageIndexFor("abc"))
    }
}
