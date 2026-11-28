package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/**
 * DocumentEngine for PDF, EPUB, XPS, CBZ, images and Office files, backed by MuPDF. Word and Excel
 * open as reflowing text; PowerPoint decks are drawn slide by slide (MuPDF alone would run them together).
 */
class MuPdfEngine : DocumentEngine {
    override fun open(bytes: ByteArray, name: String, password: String?): OpenDocument {
        val doc = when (OfficeTypes.extension(name)) {
            in OfficeTypes.powerPoint -> Document.openDocument(MuPdfOfficeEngine().toPdf(bytes, name), "application/pdf")
            // Word reads best as clean HTML with headings, tables and lists; MuPDF's own reader is the fallback.
            in OfficeTypes.word -> runCatching { `in`.llmlabs.docsmanager.office.docxToHtml(bytes) }.getOrNull()
                ?.let { Document.openDocument(MuPdfOfficeEngine().styledHtml(it, PageSetup(margin = 18f)), "text/html") } ?: Document.openDocument(bytes, name)
            else -> Document.openDocument(bytes, name)
        }
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
