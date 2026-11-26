package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.SeekableInputOutputStream
import com.artifex.mupdf.fitz.SeekableStream

/** PdfOps backed by MuPDF. */
class MuPdfOps : PdfOps {
    override fun merge(inputs: List<ByteArray>): ByteArray {
        val out = PDFDocument()
        try {
            inputs.forEachIndexed { index, bytes ->
                withPdf(bytes, index) { src ->
                    for (i in 0 until src.countPages()) out.graftPage(-1, src, i) // -1 appends at the end
                }
            }
            return out.toBytes()
        } finally {
            out.destroy()
        }
    }

    override fun split(input: ByteArray, ranges: List<IntRange>): List<ByteArray> = withPdf(input) { src ->
        ranges.map { range ->
            val out = PDFDocument()
            try {
                for (i in range) out.graftPage(-1, src, i)
                out.toBytes()
            } finally {
                out.destroy()
            }
        }
    }

    private inline fun <T> withPdf(bytes: ByteArray, index: Int = 0, block: (PDFDocument) -> T): T {
        val doc = Document.openDocument(bytes, "application/pdf")
        try {
            if (doc.needsPassword()) throw LockedPdfException(index)
            val pdf = doc.asPDF() ?: throw IllegalArgumentException("Not a PDF")
            return block(pdf)
        } finally {
            doc.destroy()
        }
    }
}

/** Saves the document to a byte array; "garbage" drops objects no page uses any more. */
internal fun PDFDocument.toBytes(): ByteArray = MemoryStream().also { save(it, "garbage,compress") }.toByteArray()

/** A growable in-memory file MuPDF can write to and read back. */
internal class MemoryStream : SeekableInputOutputStream {
    private var data = ByteArray(64 * 1024)
    private var size = 0
    private var pos = 0L

    fun toByteArray(): ByteArray = data.copyOf(size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val end = pos.toInt() + len
        if (end > data.size) data = data.copyOf(maxOf(end, data.size * 2))
        System.arraycopy(b, off, data, pos.toInt(), len)
        pos = end.toLong()
        if (end > size) size = end
    }

    override fun read(b: ByteArray): Int {
        if (pos >= size) return -1
        val n = minOf(b.size, size - pos.toInt())
        System.arraycopy(data, pos.toInt(), b, 0, n)
        pos += n
        return n
    }

    override fun truncate() { size = pos.toInt() }

    override fun seek(offset: Long, whence: Int): Long {
        pos = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> pos + offset
            else -> size + offset
        }.coerceAtLeast(0)
        return pos
    }

    override fun position(): Long = pos
}
