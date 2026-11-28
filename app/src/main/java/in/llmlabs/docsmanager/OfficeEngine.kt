package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import `in`.llmlabs.docsmanager.office.DocBlock
import `in`.llmlabs.docsmanager.office.Run
import `in`.llmlabs.docsmanager.office.Shape
import `in`.llmlabs.docsmanager.office.Sheet
import `in`.llmlabs.docsmanager.office.SlideContent
import `in`.llmlabs.docsmanager.office.readPptx
import `in`.llmlabs.docsmanager.office.writeDocx
import `in`.llmlabs.docsmanager.office.writePptx
import `in`.llmlabs.docsmanager.office.writeXlsx
import java.util.Locale

/**
 * Conversions between PDF and Office files (Word, Excel, PowerPoint) and HTML, all on the phone,
 * following llmlabs.in. Throws IllegalArgumentException with a message to show the user.
 */
interface OfficeEngine {
    /** Makes a PDF from a .docx, .xlsx, .pptx, .html or .txt file; [name] tells the format. */
    fun toPdf(input: ByteArray, name: String, setup: PageSetup = PageSetup()): ByteArray

    /** Editable text (headings, bold, pictures), or with [exactLook] each page as a picture. */
    fun pdfToWord(pdf: ByteArray, exactLook: Boolean): ByteArray

    /** Text placed in rows and columns by its position, one sheet per page or all on one. */
    fun pdfToExcel(pdf: ByteArray, oneSheet: Boolean): ByteArray

    /** One slide per page, showing the page exactly. */
    fun pdfToPowerPoint(pdf: ByteArray): ByteArray

    /** A PDF/A-2b file for long-term archiving: every page kept exactly, as a high-resolution picture. */
    fun pdfToPdfA(pdf: ByteArray, title: String): ByteArray
}

/** Page size for documents laid out from HTML, Word and Excel. [margin] in points. */
data class PageSetup(val size: ImagePage = ImagePage.A4, val landscape: Boolean = false, val margin: Float = 36f)

/** Office file types the app opens and converts, by extension. */
object OfficeTypes {
    val word = setOf("docx")
    val excel = setOf("xlsx")
    val powerPoint = setOf("pptx")
    val html = setOf("html", "htm", "xhtml")
    val all = word + excel + powerPoint
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

class MuPdfOfficeEngine : OfficeEngine {
    override fun toPdf(input: ByteArray, name: String, setup: PageSetup): ByteArray = when (val ext = OfficeTypes.extension(name)) {
        in OfficeTypes.powerPoint -> pptxToPdf(input)
        in OfficeTypes.html -> layout(styledHtml(input.toString(Charsets.UTF_8), setup), "text/html", setup)
        "txt" -> layout(styledHtml("<pre style=\"white-space:pre-wrap;font-family:sans-serif\">" + escape(input.toString(Charsets.UTF_8)) + "</pre>", setup), "text/html", setup)
        // Word goes through clean HTML (headings, tables, lists keep their shape); MuPDF's own reader is the fallback.
        in OfficeTypes.word -> runCatching { `in`.llmlabs.docsmanager.office.docxToHtml(input) }.getOrNull()
            ?.let { layout(styledHtml(it, setup), "text/html", setup) } ?: layout(input, name, setup)
        in OfficeTypes.excel -> layout(input, name, setup)
        else -> throw IllegalArgumentException("Files of type .$ext can't be converted to PDF")
    }

    /** Lays out a reflowable document (HTML, Word, Excel) with MuPDF and writes it as a PDF with real text. */
    private fun layout(input: ByteArray, magic: String, setup: PageSetup): ByteArray {
        val (w, h) = pageSize(setup)
        val doc = try { Document.openDocument(input, magic) } catch (e: Exception) { throw IllegalArgumentException("This file can't be read. It may be damaged.") }
        try {
            if (doc.isReflowable) doc.layout(w, h, 11f)
            val n = doc.countPages()
            require(n > 0) { "This file has no content" }
            val buf = Buffer()
            val writer = DocumentWriter(buf, "pdf", "compress")
            try {
                for (i in 0 until n) {
                    val page = doc.loadPage(i)
                    try {
                        val dev = writer.beginPage(page.bounds)
                        page.run(dev, Matrix())
                        writer.endPage()
                    } finally {
                        page.destroy()
                    }
                }
                writer.close()
            } finally {
                writer.destroy()
            }
            return buf.asByteArray()
        } finally {
            doc.destroy()
        }
    }

    private fun pageSize(setup: PageSetup): Pair<Float, Float> {
        val w0 = setup.size.width ?: 595.28f
        val h0 = setup.size.height ?: 841.89f
        return if (setup.landscape) maxOf(w0, h0) to minOf(w0, h0) else minOf(w0, h0) to maxOf(w0, h0)
    }

    /** Our defaults go first in <head>, so the document's own styles still win; MuPDF reads the @page margin. */
    internal fun styledHtml(html: String, setup: PageSetup): ByteArray {
        val style = "<style>@page { margin: ${setup.margin}pt } body { font-family: sans-serif; font-size: 11pt; line-height: 1.35; } " +
            "h1 { font-size: 20pt; } h2 { font-size: 16pt; } h3 { font-size: 13pt; } table { border-collapse: collapse; } " +
            "td, th { border: 0.5pt solid #999; padding: 2pt 4pt; vertical-align: top; } img { max-width: 100%; }</style>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val styled = if (head.containsMatchIn(html)) head.replaceFirst(html, "$0$style") else "<!doctype html><html><head><meta charset=\"utf-8\">$style</head><body>$html</body></html>"
        return styled.toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /* ---------- PowerPoint to PDF ---------- */

    private fun pptxToPdf(input: ByteArray): ByteArray {
        val deck = readPptx(input)
        val out = PDFDocument()
        try {
            val faces = listOf("Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique").map { Font(it) }
            val fontRefs = faces.map { out.addSimpleFont(it, Font.SIMPLE_ENCODING_LATIN) }
            fun face(bold: Boolean, italic: Boolean) = (if (bold) 1 else 0) + (if (italic) 2 else 0)
            fun width(text: String, f: Int, size: Float) = latin1(text).sumOf { faces[f].advanceGlyph(faces[f].encodeCharacter(it.code)).toDouble() }.toFloat() * size
            val H = deck.height
            for (slide in deck.slides) {
                val res = out.newDictionary()
                val fonts = out.newDictionary().also { d -> fontRefs.forEachIndexed { i, r -> d.put("F$i", r) } }
                res.put("Font", fonts)
                val xobjects = out.newDictionary()
                res.put("XObject", xobjects)
                val c = StringBuilder()
                fun rgb(hex: String) = runCatching { (0 until 3).map { hex.substring(it * 2, it * 2 + 2).toInt(16) / 255f } }.getOrDefault(listOf(1f, 1f, 1f)).joinToString(" ") { n(it) }
                c.append(rgb(slide.background ?: "FFFFFF")).append(" rg 0 0 ${n(deck.width)} ${n(H)} re f\n")
                var images = 0
                for (s in slide.shapes) {
                    val b = s.box
                    when (s) {
                        is Shape.Rect -> c.append(rgb(s.fill)).append(" rg ${n(b.x)} ${n(H - b.y - b.h)} ${n(b.w)} ${n(b.h)} re f\n")
                        is Shape.Picture -> runCatching {
                            val img = Image(s.bytes)
                            val name = "Im${images++}"
                            xobjects.put(name, out.addImage(img))
                            img.destroy()
                            c.append("q ${n(b.w)} 0 0 ${n(b.h)} ${n(b.x)} ${n(H - b.y - b.h)} cm /$name Do Q\n")
                        }
                        is Shape.Text -> {
                            s.fill?.let { c.append(rgb(it)).append(" rg ${n(b.x)} ${n(H - b.y - b.h)} ${n(b.w)} ${n(b.h)} re f\n") }
                            val inset = 7.2f
                            // Wrap each paragraph's runs into lines that fit the box.
                            class Piece(val text: String, val f: Int, val size: Float, val color: String, val w: Float)
                            class Line(val pieces: List<Piece>, val width: Float, val height: Float, val align: String)
                            val lines = mutableListOf<Line>()
                            for (p in s.paragraphs) {
                                var cur = mutableListOf<Piece>(); var curW = 0f; var curH = 0f
                                fun flush() { lines += Line(cur, curW, if (curH > 0) curH else (p.runs.firstOrNull()?.size ?: 18f), p.align); cur = mutableListOf(); curW = 0f; curH = 0f }
                                val runs = if (p.bullet && p.runs.isNotEmpty()) listOf(p.runs[0].let { `in`.llmlabs.docsmanager.office.TextRun("• ", it.size, it.bold, it.italic, it.color) }) + p.runs else p.runs
                                for (r in runs) {
                                    val f = face(r.bold, r.italic)
                                    for (word in Regex("(?<=\\s)").split(r.text)) {
                                        if (word.isEmpty()) continue
                                        val w = width(word, f, r.size)
                                        if (curW + w > b.w - inset * 2 && cur.isNotEmpty() && word.isNotBlank()) flush()
                                        cur += Piece(word, f, r.size, r.color, w); curW += w; curH = maxOf(curH, r.size)
                                    }
                                }
                                if (cur.isNotEmpty() || runs.isEmpty()) flush()
                            }
                            val total = lines.sumOf { (it.height * 1.2f).toDouble() }.toFloat()
                            var cursor = when (s.anchor) { "ctr" -> b.y + (b.h - total) / 2; "b" -> b.y + b.h - total - inset; else -> b.y + inset }
                            for (l in lines) {
                                cursor += l.height * 1.2f
                                var lx = when (l.align) { "ctr" -> b.x + (b.w - l.width) / 2; "r" -> b.x + b.w - inset - l.width; else -> b.x + inset }
                                for (pc in l.pieces) {
                                    if (pc.text.isNotBlank()) {
                                        val hex = latin1(pc.text).map { "%02X".format(it.code) }.joinToString("")
                                        c.append("BT ").append(rgb(pc.color)).append(" rg /F${pc.f} ${n(pc.size)} Tf ${n(lx)} ${n(H - cursor + l.height * 0.2f)} Td <").append(hex).append("> Tj ET\n")
                                    }
                                    lx += pc.w
                                }
                            }
                        }
                    }
                }
                out.insertPage(-1, out.addPage(Rect(0f, 0f, deck.width, H), 0, res, c.toString()))
            }
            return out.toBytes()
        } finally {
            out.destroy()
        }
    }

    /* ---------- PDF to Word ---------- */

    private class Span(var text: String, val bold: Boolean, val italic: Boolean, val size: Float)
    private class TLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val spans: MutableList<Span> = mutableListOf())
    private sealed interface Block {
        val y: Float
        class Text(override val y: Float, val lines: MutableList<TLine> = mutableListOf()) : Block
        class Picture(override val y: Float, val width: Float, val height: Float, val png: ByteArray) : Block
    }
    private class PageText(val width: Float, val height: Float, val blocks: List<Block>)

    private fun openPdf(input: ByteArray): Document {
        val doc = try { Document.openDocument(input, "application/pdf") } catch (e: Exception) { throw IllegalArgumentException("This PDF can't be read") }
        if (doc.needsPassword()) { doc.destroy(); throw LockedPdfException(0) }
        return doc
    }

    private val boldName = Regex("bold|black|heavy|semibold|demi", RegexOption.IGNORE_CASE)
    private val italicName = Regex("italic|oblique", RegexOption.IGNORE_CASE)

    private fun readPages(input: ByteArray, withImages: Boolean): List<PageText> {
        val doc = openPdf(input)
        try {
            return (0 until doc.countPages()).map { i ->
                val page = doc.loadPage(i)
                try {
                    val bounds = page.bounds
                    val st = page.toStructuredText(if (withImages) "preserve-whitespace,preserve-images" else "preserve-whitespace")
                    val blocks = mutableListOf<Block>()
                    var block: Block.Text? = null
                    var line: TLine? = null
                    st.walk(object : StructuredTextWalker {
                        override fun onImageBlock(bbox: Rect, transform: Matrix, image: Image) {
                            val w = bbox.x1 - bbox.x0; val h = bbox.y1 - bbox.y0
                            if (w < 8 || h < 8) return
                            runCatching {
                                var pix = image.toPixmap()
                                if (pix.colorSpace?.isRGB != true || pix.alpha) pix = pix.convertToColorSpace(ColorSpace.DeviceRGB, null, null, 0, false)
                                blocks += Block.Picture(bbox.y0, w, h, pix.asPNG().asByteArray())
                            }
                        }
                        override fun beginTextBlock(bbox: Rect, flags: Int) { block = Block.Text(bbox.y0).also { blocks += it } }
                        override fun endTextBlock() { block = null }
                        override fun beginLine(bbox: Rect, wmode: Int, dir: Point) { line = TLine(bbox.x0, bbox.y0, bbox.x1, bbox.y1).also { block?.lines?.add(it) } }
                        override fun endLine() { line = null }
                        override fun onChar(c: Int, origin: Point, font: Font, size: Float, quad: Quad, argb: Int, flags: Int, bidi: Int) {
                            val l = line ?: return
                            val bold = font.isBold || boldName.containsMatchIn(font.name)
                            val italic = font.isItalic || italicName.containsMatchIn(font.name)
                            val ch = String(Character.toChars(c))
                            val last = l.spans.lastOrNull()
                            if (last != null && last.bold == bold && last.italic == italic && Math.abs(last.size - size) < 0.5f) last.text += ch
                            else l.spans += Span(ch, bold, italic, Math.round(size * 2) / 2f)
                        }
                        override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                        override fun endStruct() {}
                        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
                    })
                    st.destroy()
                    PageText(bounds.x1 - bounds.x0, bounds.y1 - bounds.y0, blocks)
                } finally {
                    page.destroy()
                }
            }
        } finally {
            doc.destroy()
        }
    }

    /** The most common text size, taken as the body size. */
    private fun bodySize(pages: List<PageText>): Float {
        val count = mutableMapOf<Float, Int>()
        for (p in pages) for (b in p.blocks) if (b is Block.Text) for (l in b.lines) for (s in l.spans) count[s.size] = (count[s.size] ?: 0) + s.text.length
        return count.maxByOrNull { it.value }?.key ?: 11f
    }

    private fun blocksToDoc(pages: List<PageText>): List<DocBlock> {
        val body = bodySize(pages)
        val out = mutableListOf<DocBlock>()
        pages.forEachIndexed { pi, page ->
            if (pi > 0) out += DocBlock.PageBreak
            for (b in page.blocks) {
                if (b is Block.Picture) { out += DocBlock.Picture(b.png, true, b.width, b.height); continue }
                b as Block.Text
                // Join a block's lines into one paragraph, mending words hyphenated across lines.
                val runs = mutableListOf<Run>()
                b.lines.forEachIndexed { li, l ->
                    l.spans.forEachIndexed { si, s ->
                        var text = s.text
                        if (si == l.spans.lastIndex && li < b.lines.lastIndex) text = if (Regex("[A-Za-z]-$").containsMatchIn(text)) text.dropLast(1) else text.trimEnd() + " "
                        val prev = runs.lastOrNull()
                        if (prev != null && prev.bold == s.bold && prev.italic == s.italic && prev.size == s.size) runs[runs.lastIndex] = Run(prev.text + text, prev.bold, prev.italic, prev.size)
                        else runs += Run(text, s.bold, s.italic, s.size)
                    }
                }
                val text = runs.joinToString("") { it.text }.trim()
                if (text.isEmpty()) continue
                val maxSize = runs.maxOf { it.size ?: body }
                val allBold = runs.all { it.bold || it.text.isBlank() }
                val heading = if (text.length >= 120) null else when {
                    maxSize >= body * 1.6f -> 1
                    maxSize >= body * 1.3f -> 2
                    allBold && maxSize > body * 1.05f -> 3
                    else -> null
                }
                val first = b.lines.first()
                val mid = (first.x0 + first.x1) / 2
                val centred = b.lines.size <= 2 && Math.abs(mid - page.width / 2) < page.width * 0.04f && first.x1 - first.x0 < page.width * 0.7f
                out += DocBlock.Paragraph(if (heading != null) runs.map { Run(it.text) } else runs, heading, if (centred) "center" else null)
            }
        }
        return out
    }

    override fun pdfToWord(pdf: ByteArray, exactLook: Boolean): ByteArray {
        if (exactLook) {
            val out = mutableListOf<DocBlock>()
            renderPages(pdf, 2f) { jpeg, w, h, i -> if (i > 0) out += DocBlock.PageBreak; out += DocBlock.Picture(jpeg, false, w, h) }
            return writeDocx(out)
        }
        val pages = readPages(pdf, true)
        require(pages.any { p -> p.blocks.any { it is Block.Text } }) { "No text was found. If this is a scan, run OCR PDF first, or choose \"Exact look\"." }
        return writeDocx(blocksToDoc(pages))
    }

    /* ---------- PDF to Excel ---------- */

    /** Groups a page's text into rows (by line position) and columns (by gaps between pieces of text). */
    private fun pageToRows(page: PageText): List<List<Any>> {
        val lines = page.blocks.flatMap { if (it is Block.Text) it.lines else emptyList() }
        if (lines.isEmpty()) return emptyList()
        class Piece(val x0: Float, val y: Float, val text: String)
        val pieces = mutableListOf<Piece>()
        for (l in lines) {
            val text = l.spans.joinToString("") { it.text }
            val charW = (l.x1 - l.x0) / maxOf(1, text.length)
            var x = l.x0
            // Runs of 2+ spaces, or a tab, separate cells.
            for (part in splitKeep(text)) {
                val w = part.length * charW
                if (part.isNotBlank()) pieces += Piece(x, (l.y0 + l.y1) / 2, part.trim())
                x += w
            }
        }
        pieces.sortWith(compareBy<Piece> { it.y }.thenBy { it.x0 })
        val rows = mutableListOf<MutableList<Piece>>()
        for (p in pieces) { val row = rows.lastOrNull(); if (row != null && Math.abs(row[0].y - p.y) < 3) row += p else rows += mutableListOf(p) }
        val columns = mutableListOf<Float>()
        for (x in pieces.map { it.x0 }.sorted()) if (columns.isEmpty() || x - columns.last() > 12) columns += x
        return rows.map { row ->
            val cells = MutableList<Any>(columns.size) { "" }
            for (p in row.sortedBy { it.x0 }) {
                var col = 0
                for (i in columns.indices) if (columns[i] <= p.x0 + 6) col = i
                val value: Any = if (Regex("^-?\\d+(\\.\\d+)?$").matches(p.text) && !Regex("^0\\d").containsMatchIn(p.text)) p.text.toDouble() else p.text
                cells[col] = if (cells[col] == "") value else "${cells[col]} ${p.text}"
            }
            while (cells.isNotEmpty() && cells.last() == "") cells.removeAt(cells.lastIndex)
            cells
        }
    }

    /** Splits text into cell texts and the wide gaps between them, keeping both (like JS split with a capture group). */
    private fun splitKeep(text: String): List<String> {
        val out = mutableListOf<String>()
        var last = 0
        for (m in Regex("\\s{2,}|\\t").findAll(text)) { out += text.substring(last, m.range.first); out += m.value; last = m.range.last + 1 }
        out += text.substring(last)
        return out
    }

    override fun pdfToExcel(pdf: ByteArray, oneSheet: Boolean): ByteArray {
        val perPage = readPages(pdf, false).map(::pageToRows)
        require(perPage.any { it.isNotEmpty() }) { "No text was found. If this is a scan, run OCR PDF first." }
        val sheets = if (oneSheet) listOf(Sheet("PDF", perPage.flatMapIndexed { i, rows -> if (i > 0) listOf(emptyList<Any>()) + rows else rows }))
        else perPage.mapIndexed { i, rows -> Sheet("Page ${i + 1}", rows) }
        return writeXlsx(sheets)
    }

    /* ---------- PDF to PowerPoint, PDF/A ---------- */

    private fun renderPages(input: ByteArray, scale: Float, each: (jpeg: ByteArray, wPt: Float, hPt: Float, index: Int) -> Unit) {
        val doc = openPdf(input)
        try {
            for (i in 0 until doc.countPages()) {
                val page = doc.loadPage(i)
                try {
                    val b = page.bounds
                    val bmp = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(page, Matrix(scale))
                    val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                    bmp.recycle()
                    each(jpeg, b.x1 - b.x0, b.y1 - b.y0, i)
                } finally {
                    page.destroy()
                }
            }
        } finally {
            doc.destroy()
        }
    }

    override fun pdfToPowerPoint(pdf: ByteArray): ByteArray {
        val slides = mutableListOf<SlideContent>()
        renderPages(pdf, 2f) { jpeg, w, h, _ -> slides += SlideContent.Picture(jpeg, false, w, h) }
        return writePptx(slides)
    }

    override fun pdfToPdfA(pdf: ByteArray, title: String): ByteArray {
        val out = PDFDocument()
        try {
            renderPages(pdf, 2.5f) { jpeg, w, h, _ ->
                val img = Image(jpeg)
                val res = out.newDictionary()
                res.put("XObject", out.newDictionary().also { it.put("Im0", out.addImage(img)) })
                img.destroy()
                out.insertPage(-1, out.addPage(Rect(0f, 0f, w, h), 0, res, "q ${n(w)} 0 0 ${n(h)} 0 0 cm /Im0 Do Q"))
            }
            val root = out.trailer.get("Root")
            val icc = out.addStream(Buffer().apply { writeBytes(srgbProfile()) }, out.newDictionary().also { it.put("N", 3) })
            val intent = out.newDictionary().apply {
                put("Type", out.newName("OutputIntent")); put("S", out.newName("GTS_PDFA1"))
                put("OutputConditionIdentifier", out.newString("sRGB")); put("Info", out.newString("sRGB IEC61966-2.1"))
                put("DestOutputProfile", icc)
            }
            root.put("OutputIntents", out.newArray().also { it.push(intent) })
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val pdfDate = "D:" + now.replace(Regex("[-:T]"), "").removeSuffix("Z") + "Z"
            val safeTitle = `in`.llmlabs.docsmanager.office.esc(title)
            val xmp = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n" +
                "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>\n" +
                "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">$safeTitle</rdf:li></rdf:Alt></dc:title>\n" +
                "<xmp:CreateDate>$now</xmp:CreateDate><xmp:ModifyDate>$now</xmp:ModifyDate><xmp:CreatorTool>Docs Manager Pro</xmp:CreatorTool><pdf:Producer>Docs Manager Pro</pdf:Producer>\n" +
                "</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>"
            // PDF/A wants the metadata readable without decompression.
            val meta = out.addRawStream(Buffer().apply { writeBytes(xmp.toByteArray(Charsets.UTF_8)) }, out.newDictionary().also {
                it.put("Type", out.newName("Metadata")); it.put("Subtype", out.newName("XML"))
            })
            root.put("Metadata", meta)
            // The Info dictionary must agree with the XMP metadata.
            val info = out.addObject(out.newDictionary().apply {
                put("Title", out.newString(title)); put("Producer", out.newString("Docs Manager Pro")); put("Creator", out.newString("Docs Manager Pro"))
                put("CreationDate", out.newString(pdfDate)); put("ModDate", out.newString(pdfDate))
            })
            out.trailer.put("Info", info)
            // PDF/A requires a file identifier.
            val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            out.trailer.put("ID", out.newArray().also { a -> a.push(out.newByteString(id)); a.push(out.newByteString(id)) })
            return out.toBytes("garbage")
        } finally {
            out.destroy()
        }
    }

    /** A small sRGB ICC v2 display profile (D50, gamma 2.2), as llmlabs.in builds it; PDF/A needs one embedded. */
    private fun srgbProfile(): ByteArray {
        fun ascii(b: ByteArray, at: Int, s: String) { for (i in s.indices) b[at + i] = s[i].code.toByte() }
        fun s15(bb: java.nio.ByteBuffer, at: Int, v: Double) { bb.putInt(at, Math.round(v * 65536).toInt()) }
        fun xyz(x: Double, y: Double, z: Double) = ByteArray(20).also { b -> ascii(b, 0, "XYZ "); java.nio.ByteBuffer.wrap(b).let { s15(it, 8, x); s15(it, 12, y); s15(it, 16, z) } }
        fun gamma(g: Double) = ByteArray(14).also { b -> ascii(b, 0, "curv"); java.nio.ByteBuffer.wrap(b).apply { putInt(8, 1); putShort(12, Math.round(g * 256).toInt().toShort()) } }
        fun text(s: String) = ByteArray(8 + s.length + 1).also { ascii(it, 0, "text"); ascii(it, 8, s) }
        fun desc(s: String) = ByteArray(12 + s.length + 1 + 4 + 4 + 2 + 1 + 67).also { b -> ascii(b, 0, "desc"); java.nio.ByteBuffer.wrap(b).putInt(8, s.length + 1); ascii(b, 12, s) }
        val tags = listOf(
            "desc" to desc("sRGB (Docs Manager Pro)"), "cprt" to text("No copyright, use freely"),
            "wtpt" to xyz(0.9642, 1.0, 0.8249), "rXYZ" to xyz(0.4361, 0.2225, 0.0139), "gXYZ" to xyz(0.3851, 0.7169, 0.0971), "bXYZ" to xyz(0.1431, 0.0606, 0.7141),
            "rTRC" to gamma(2.2), "gTRC" to gamma(2.2), "bTRC" to gamma(2.2),
        )
        var offset = 128 + 4 + tags.size * 12
        val placed = tags.map { (sig, data) -> val at = offset; offset += data.size + (4 - data.size % 4) % 4; Triple(sig, data, at) }
        val out = ByteArray(offset)
        val v = java.nio.ByteBuffer.wrap(out)
        v.putInt(0, offset); ascii(out, 4, "none"); v.putInt(8, 0x02100000); ascii(out, 12, "mntr"); ascii(out, 16, "RGB "); ascii(out, 20, "XYZ "); ascii(out, 36, "acsp")
        s15(v, 68, 0.9642); s15(v, 72, 1.0); s15(v, 76, 0.8249)
        v.putInt(128, tags.size)
        placed.forEachIndexed { i, (sig, data, at) -> ascii(out, 132 + i * 12, sig); v.putInt(136 + i * 12, at); v.putInt(140 + i * 12, data.size); System.arraycopy(data, 0, out, at, data.size) }
        return out
    }

    private companion object {
        fun n(v: Float) = "%.3f".format(Locale.ROOT, v)
        /** The built-in fonts cover Latin-1; other characters become "?". */
        fun latin1(text: String) = text.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"').replace('–', '-').replace('—', '-')
            .map { if (it.code in 32..255) it else '?' }.joinToString("")
    }
}
