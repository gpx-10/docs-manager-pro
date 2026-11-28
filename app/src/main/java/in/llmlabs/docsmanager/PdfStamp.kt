package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject

/**
 * Draws extra content (page numbers, watermarks) on top of existing pages without touching
 * what is already there. Positions are in the page's visible space: (0,0) is the bottom-left
 * corner as the reader sees it, even on turned pages.
 */
internal class PdfStamper(private val pdf: PDFDocument) {
    private val font = Font("Helvetica")
    private val fontRef: PDFObject = pdf.addSimpleFont(font, Font.SIMPLE_ENCODING_LATIN)

    /** Width of [text] at [size] points in this font. */
    fun textWidth(text: String, size: Float): Float =
        latin1(text).sumOf { font.advanceGlyph(font.encodeCharacter(it.code)).toDouble() }.toFloat() * size

    /** Visible width and height of page [index], as the reader sees it. */
    fun visibleSize(index: Int): Pair<Float, Float> {
        val box = Box.of(pdf.findPage(index))
        return if (box.rotate % 180 == 0) box.w to box.h else box.h to box.w
    }

    /**
     * Adds content to page [index]. [draw] gets a [Canvas] and the visible page size and
     * writes PDF drawing commands in visible-space coordinates.
     */
    fun stamp(index: Int, opacity: Float = 1f, draw: Canvas.(w: Float, h: Float) -> Unit) {
        val page = pdf.findPage(index)
        val box = Box.of(page)
        val (w, h) = visibleSize(index)
        val canvas = Canvas().apply { draw(w, h) }

        val resources = page.getInheritable("Resources").let { if (it.isNull) pdf.newDictionary() else it }
        page.put("Resources", resources)
        dictIn(resources, "Font").put(FONT, fontRef)
        if (opacity < 1f) {
            val gs = pdf.newDictionary().apply {
                put("Type", pdf.newName("ExtGState"))
                put("ca", pdf.newReal(opacity))
                put("CA", pdf.newReal(opacity))
            }
            dictIn(resources, "ExtGState").put(GSTATE, pdf.addObject(gs))
        }

        // Wrap the old content in q/Q so its graphics state can't leak into ours.
        val body = buildString {
            append("Q\nq ").append(box.matrix()).append(" cm\n")
            if (opacity < 1f) append("/$GSTATE gs\n")
            append(canvas.ops)
            append("Q\n")
        }
        val contents = pdf.newArray()
        contents.push(pdf.addStream("q\n"))
        val old = page.get("Contents")
        when {
            old.isArray -> for (i in 0 until old.size()) contents.push(old.get(i))
            !old.isNull -> contents.push(old)
        }
        contents.push(pdf.addStream(body))
        page.put("Contents", contents)
    }

    private fun dictIn(parent: PDFObject, key: String): PDFObject {
        val existing = parent.get(key)
        if (!existing.isNull) return existing
        return pdf.newDictionary().also { parent.put(key, it) }
    }

    /** Collects drawing commands. */
    inner class Canvas {
        internal val ops = StringBuilder()

        /** Writes [text] with its baseline starting at ([x], [y]), turned [angle] degrees anticlockwise. */
        fun text(text: String, x: Float, y: Float, size: Float, angle: Float = 0f, gray: Float = 0.25f) {
            val r = Math.toRadians(angle.toDouble())
            val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
            val hex = latin1(text).map { "%02X".format(it.code) }.joinToString("")
            ops.append("BT /$FONT ").append(n(size)).append(" Tf ").append(n(gray)).append(" g ")
                .append("${n(c)} ${n(s)} ${n(-s)} ${n(c)} ${n(x)} ${n(y)} Tm <").append(hex).append("> Tj ET\n")
        }
    }

    /** A page's visible box in PDF user space and its turn. */
    private class Box(val x0: Float, val y0: Float, val w: Float, val h: Float, val rotate: Int) {
        /** Maps visible-space coordinates to the page's own coordinates. */
        fun matrix(): String = when (rotate) {
            90 -> "0 1 -1 0 ${n(x0 + w)} ${n(y0)}"
            180 -> "-1 0 0 -1 ${n(x0 + w)} ${n(y0 + h)}"
            270 -> "0 -1 1 0 ${n(x0)} ${n(y0 + h)}"
            else -> "1 0 0 1 ${n(x0)} ${n(y0)}"
        }

        companion object {
            fun of(page: PDFObject): Box {
                val crop = page.getInheritable("CropBox")
                val r = if (!crop.isNull && crop.isArray) crop else page.getInheritable("MediaBox")
                val a = (0 until 4).map { r.get(it).asFloat() }
                val rot = page.getInheritable("Rotate").let { if (it.isNull) 0 else Math.floorMod(it.asInteger(), 360) }
                return Box(minOf(a[0], a[2]), minOf(a[1], a[3]), Math.abs(a[2] - a[0]), Math.abs(a[3] - a[1]), rot)
            }
        }
    }

    private companion object {
        const val FONT = "DMPHelv"
        const val GSTATE = "DMPgs"

        fun n(v: Float) = "%.3f".format(java.util.Locale.ROOT, v)

        /** The built-in font covers Latin-1; other characters become "?". */
        fun latin1(text: String) = text.map { if (it.code in 32..255) it else '?' }.joinToString("")
    }
}
