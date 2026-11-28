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
    override val isReflowable: Boolean get() = doc.isReflowable

    override fun setTextSize(textSize: Float) {
        // A phone-shaped page; MuPDF flows the book's text into as many of these as it needs.
        if (doc.isReflowable) doc.layout(EBOOK_PAGE_WIDTH, EBOOK_PAGE_HEIGHT, textSize)
    }
    override fun pageSize(index: Int): PageSize {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            return PageSize(b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            page.destroy()
        }
    }
    override fun renderPage(index: Int, widthPx: Int): android.graphics.Bitmap {
        val page = doc.loadPage(index)
        try {
            return AndroidDrawDevice.drawPageFitWidth(page, widthPx)
        } finally {
            page.destroy()
        }
    }
    override fun pageText(index: Int): String {
        val page = doc.loadPage(index)
        try {
            val text = page.toStructuredText()
            try { return text.asText() } finally { text.destroy() }
        } finally {
            page.destroy()
        }
    }

    override fun close() = doc.destroy()
}

private const val EBOOK_PAGE_WIDTH = 360f
private const val EBOOK_PAGE_HEIGHT = 640f
