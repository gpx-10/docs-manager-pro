package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Document

/** DocumentEngine for PDF, EPUB, XPS, CBZ and images, backed by MuPDF. */
class MuPdfEngine : DocumentEngine {
    override fun open(bytes: ByteArray, name: String): OpenDocument =
        MuPdfDocument(Document.openDocument(bytes, name))
}

private class MuPdfDocument(private val doc: Document) : OpenDocument {
    override val pageCount: Int get() = doc.countPages()
    override fun close() = doc.destroy()
}
