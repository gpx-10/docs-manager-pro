package `in`.llmlabs.docsmanager

/** Where the reader is in an open Document: current page and zoom. Immutable. */
data class ViewerState(val pageCount: Int, val currentPage: Int = 0, val zoom: Float = 1f) {
    fun goTo(page: Int): ViewerState = copy(currentPage = page.coerceIn(0, pageCount - 1))
    fun zoomBy(factor: Float): ViewerState = copy(zoom = (zoom * factor).coerceIn(1f, 5f))
    /**
     * The page being read after a scroll: the last page once the end is reached, otherwise
     * the top page, or the next one when more than half of the top page has scrolled away.
     */
    fun scrolledTo(firstVisible: Int, firstHiddenFraction: Float, atEnd: Boolean): ViewerState =
        goTo(if (atEnd) pageCount - 1 else if (firstHiddenFraction > 0.5f) firstVisible + 1 else firstVisible)
    /** The 0-based page for a page number the user typed (1 = first page), or null if there is no such page. */
    fun pageIndexFor(typed: String): Int? = typed.trim().toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)
    val pageLabel: String get() = "${currentPage + 1} / $pageCount"
}
