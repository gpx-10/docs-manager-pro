package `in`.llmlabs.docsmanager

/** Opens a Document and gives read access to its pages. */
interface DocumentEngine {
    /** [name] is the file name; its extension tells the engine the format. */
    fun open(bytes: ByteArray, name: String): OpenDocument
}

interface OpenDocument : AutoCloseable {
    val pageCount: Int

    /** Draws page [index] (0-based) scaled to [widthPx] wide, keeping its proportions. */
    fun renderPage(index: Int, widthPx: Int): android.graphics.Bitmap
}
