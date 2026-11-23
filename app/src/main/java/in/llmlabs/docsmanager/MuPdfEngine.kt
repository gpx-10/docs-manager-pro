package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/** DocumentEngine for PDF, EPUB, XPS, CBZ and images, backed by MuPDF. */
class MuPdfEngine : DocumentEngine {
    override fun open(bytes: ByteArray, name: String, password: String?): OpenDocument {
        val doc = Document.openDocument(bytes, name)
        if (doc.needsPassword() && (password == null || !doc.authenticatePassword(password))) {
            doc.destroy()
            throw PasswordRequiredException(wrongPassword = password != null)
        }
        return MuPdfDocument(doc)
    }
}

private class MuPdfDocument(private val doc: Document) : OpenDocument {
    override val pageCount: Int get() = doc.countPages()
    override fun renderPage(index: Int, widthPx: Int): android.graphics.Bitmap {
        val page = doc.loadPage(index)
        try {
            return AndroidDrawDevice.drawPageFitWidth(page, widthPx)
        } finally {
            page.destroy()
        }
    }
    override fun close() = doc.destroy()
}
