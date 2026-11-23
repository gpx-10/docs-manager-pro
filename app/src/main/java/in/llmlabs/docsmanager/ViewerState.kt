package `in`.llmlabs.docsmanager

/** Where the reader is in an open Document: current page and zoom. Immutable. */
data class ViewerState(val pageCount: Int, val currentPage: Int = 0, val zoom: Float = 1f) {
    fun goTo(page: Int): ViewerState = copy(currentPage = page.coerceIn(0, pageCount - 1))
    fun zoomBy(factor: Float): ViewerState = copy(zoom = (zoom * factor).coerceIn(1f, 5f))
    val pageLabel: String get() = "${currentPage + 1} / $pageCount"
}
