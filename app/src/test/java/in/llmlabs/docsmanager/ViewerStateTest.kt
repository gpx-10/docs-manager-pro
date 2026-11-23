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
}
