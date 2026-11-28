package `in`.llmlabs.docsmanager

/** Opens a Document and gives read access to its pages. */
interface DocumentEngine {
    /**
     * [name] is the file name; its extension tells the engine the format.
     * Throws [PasswordRequiredException] when the Document is locked and [password] is missing or wrong.
     */
    fun open(bytes: ByteArray, name: String, password: String? = null): OpenDocument
}

class PasswordRequiredException(val wrongPassword: Boolean) :
    Exception(if (wrongPassword) "Wrong password" else "This document needs a password")

interface OpenDocument : AutoCloseable {
    val pageCount: Int

    /** True for e-books (EPUB and similar), whose text re-flows into pages at any text size. */
    val isReflowable: Boolean

    /** Re-flows an e-book at [textSize] points; the page count changes. Does nothing for fixed-layout files. */
    fun setTextSize(textSize: Float)

    /** Size of page [index] (0-based) in points. */
    fun pageSize(index: Int): PageSize

    /** Draws page [index] (0-based) scaled to [widthPx] wide, keeping its proportions. */
    fun renderPage(index: Int, widthPx: Int): android.graphics.Bitmap

    /** The text on page [index] (0-based), in reading order. Empty for scans and images. */
    fun pageText(index: Int): String
}

/** A page's width and height in points (1/72 inch). */
data class PageSize(val width: Float, val height: Float)
