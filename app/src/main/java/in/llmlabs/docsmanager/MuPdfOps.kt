package `in`.llmlabs.docsmanager

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
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

    override fun rotate(input: ByteArray, pages: Set<Int>, degrees: Int): ByteArray = withPdf(input) { pdf ->
        for (i in pages) {
            val page = pdf.findPage(i)
            val current = page.getInheritable("Rotate").let { if (it.isNull) 0 else it.asInteger() }
            page.put("Rotate", Math.floorMod(current + degrees, 360))
        }
        pdf.toBytes()
    }

    override fun organize(input: ByteArray, pages: List<Int?>): ByteArray = withPdf(input) { src ->
        require(pages.isNotEmpty()) { "Keep at least one page" }
        val out = PDFDocument()
        try {
            pages.forEachIndexed { n, index ->
                if (index != null) {
                    out.graftPage(-1, src, index)
                } else {
                    val size = if (n == 0) src.loadPage(0).use { it.bounds } else out.loadPage(n - 1).use { it.bounds }
                    out.insertPage(-1, out.addPage(size, 0, out.newDictionary(), ""))
                }
            }
            out.toBytes()
        } finally {
            out.destroy()
        }
    }

    override fun protect(input: ByteArray, password: String): ByteArray = withPdf(input) { pdf ->
        // MuPDF reads save options as a comma-separated list, so a comma would end the password early.
        require(password.isNotEmpty() && ',' !in password) { "Passwords can't be empty or contain commas" }
        pdf.toBytes("garbage,compress,encrypt=aes-256,user-password=$password,owner-password=$password")
    }

    override fun unlock(input: ByteArray, password: String): ByteArray {
        val doc = Document.openDocument(input, "application/pdf")
        try {
            if (doc.needsPassword() && !doc.authenticatePassword(password)) throw PasswordRequiredException(wrongPassword = true)
            val pdf = doc.asPDF() ?: throw IllegalArgumentException("Not a PDF")
            return pdf.toBytes("garbage,compress,encrypt=none")
        } finally {
            doc.destroy()
        }
    }

    override fun metadata(input: ByteArray): PdfInfo = withPdf(input) { pdf ->
        fun read(key: String) = pdf.getMetaData(key).orEmpty()
        PdfInfo(read(Document.META_INFO_TITLE), read(Document.META_INFO_AUTHOR), read(Document.META_INFO_SUBJECT), read(Document.META_INFO_KEYWORDS))
    }

    override fun setMetadata(input: ByteArray, info: PdfInfo): ByteArray = withPdf(input) { pdf ->
        val dict = pdf.trailer.get("Info").let { if (it.isNull) pdf.addObject(pdf.newDictionary()).also { d -> pdf.trailer.put("Info", d) } else it }
        listOf("Title" to info.title, "Author" to info.author, "Subject" to info.subject, "Keywords" to info.keywords).forEach { (key, value) ->
            if (value.isBlank()) dict.delete(key) else dict.put(key, pdf.newString(value.trim()))
        }
        pdf.toBytes()
    }

    override fun crop(input: ByteArray, margins: Margins): ByteArray = withPdf(input) { pdf ->
        for (i in 0 until pdf.countPages()) {
            (pdf.loadPage(i) as PDFPage).use { page ->
                val b = page.bounds // y grows downwards here, so y0 is the top edge
                val box = Rect(b.x0 + margins.left, b.y0 + margins.top, b.x1 - margins.right, b.y1 - margins.bottom)
                require(box.x1 - box.x0 >= 1 && box.y1 - box.y0 >= 1) { "The margins are bigger than page ${i + 1}" }
                (page as PDFPage).setCropBox(box)
            }
        }
        pdf.toBytes()
    }

    override fun addPageNumbers(input: ByteArray, format: String, position: NumberPosition, startAt: Int): ByteArray = withPdf(input) { pdf ->
        val stamper = PdfStamper(pdf)
        val count = pdf.countPages()
        val size = 11f
        val margin = 24f
        for (i in 0 until count) {
            val label = format.replace("{n}", (startAt + i).toString()).replace("{total}", (startAt + count - 1).toString())
            stamper.stamp(i) { w, h ->
                val width = stamper.textWidth(label, size)
                val x = when (position) {
                    NumberPosition.BottomLeft, NumberPosition.TopLeft -> margin
                    NumberPosition.BottomRight, NumberPosition.TopRight -> w - margin - width
                    else -> (w - width) / 2
                }
                val top = position == NumberPosition.TopLeft || position == NumberPosition.TopCenter || position == NumberPosition.TopRight
                text(label, x, if (top) h - margin - size else margin, size)
            }
        }
        pdf.toBytes()
    }

    override fun watermark(input: ByteArray, text: String, opacity: Float): ByteArray = withPdf(input) { pdf ->
        require(text.isNotBlank()) { "Enter the watermark text" }
        val stamper = PdfStamper(pdf)
        for (i in 0 until pdf.countPages()) {
            stamper.stamp(i, opacity.coerceIn(0.05f, 1f)) { w, h ->
                // Run corner to corner, sized so the text spans about 80% of the diagonal.
                val angle = Math.toDegrees(Math.atan2(h.toDouble(), w.toDouble())).toFloat()
                val diagonal = Math.hypot(w.toDouble(), h.toDouble()).toFloat()
                val size = (diagonal * 0.8f / stamper.textWidth(text, 1f)).coerceIn(12f, 160f)
                val width = stamper.textWidth(text, size)
                val r = Math.toRadians(angle.toDouble())
                // Start point so the text's centre lands on the page centre.
                val x = w / 2 - (width / 2) * Math.cos(r).toFloat() + (size * 0.35f) * Math.sin(r).toFloat()
                val y = h / 2 - (width / 2) * Math.sin(r).toFloat() - (size * 0.35f) * Math.cos(r).toFloat()
                text(text, x, y, size, angle, gray = 0.5f)
            }
        }
        pdf.toBytes()
    }

    override fun compress(input: ByteArray, level: CompressLevel): ByteArray = withPdf(input) { pdf ->
        val dpi = level.dpi
        if (dpi != null) shrinkImages(pdf, dpi, level.quality)
        pdf.toBytes("garbage=4,compress,compress-fonts")
    }

    /** Re-saves every large colour or grey image as a JPEG no sharper than [dpi] on the largest page. */
    private fun shrinkImages(pdf: PDFDocument, dpi: Int, quality: Int) {
        val largestPage = (0 until pdf.countPages()).maxOfOrNull { i -> pdf.loadPage(i).use { p -> p.bounds.let { maxOf(it.x1 - it.x0, it.y1 - it.y0) } } } ?: return
        val maxPx = (largestPage / 72f * dpi).toInt().coerceAtLeast(64)
        val images = (1 until pdf.countObjects()).map { pdf.newIndirect(it, 0) }.filter { it.isStream && it.get("Subtype").let { s -> s.isName && s.asName() == "Image" } }
        // Soft masks hold transparency and must stay exact; colour-keyed and 1-bit images don't survive JPEG.
        val masks = images.mapNotNull { it.get("SMask").takeIf { m -> m.isIndirect }?.asIndirect() }.toSet()
        for (obj in images) {
            if (obj.asIndirect() in masks) continue
            if (obj.get("ImageMask").let { !it.isNull && it.asBoolean() } || !obj.get("Mask").isNull) continue
            if (obj.get("BitsPerComponent").let { !it.isNull && it.asInteger() < 8 }) continue
            val image = try { pdf.loadImage(obj) } catch (e: Exception) { continue }
            try {
                val scale = minOf(1f, maxPx.toFloat() / maxOf(image.width, image.height))
                val isJpeg = obj.get("Filter").let { it.isName && it.asName() == "DCTDecode" }
                if (scale >= 1f && isJpeg) continue
                val w = maxOf(1, (image.width * scale).toInt())
                val h = maxOf(1, (image.height * scale).toInt())
                val pix = Pixmap(ColorSpace.DeviceRGB, w, h, false)
                try {
                    pix.clear(255)
                    val device = DrawDevice(pix)
                    try {
                        device.fillImage(image, Matrix(w.toFloat(), 0f, 0f, h.toFloat(), 0f, 0f), 1f, 0)
                        device.close()
                    } finally {
                        device.destroy()
                    }
                    val jpeg = pix.asJPEG(quality, false)
                    if (jpeg.length >= obj.readRawStream().size * 0.9) continue // not worth it
                    obj.writeRawStream(jpeg)
                    obj.put("Filter", pdf.newName("DCTDecode"))
                    obj.put("Width", w)
                    obj.put("Height", h)
                    obj.put("ColorSpace", pdf.newName("DeviceRGB"))
                    obj.put("BitsPerComponent", 8)
                    obj.delete("DecodeParms")
                    obj.delete("Decode")
                } finally {
                    pix.destroy()
                }
            } finally {
                image.destroy()
            }
        }
    }

    override fun repair(input: ByteArray): ByteArray = withPdf(input) { pdf ->
        // MuPDF rebuilds the structure while opening; loading each page proves it can be read.
        val count = pdf.countPages()
        require(count > 0) { "No pages could be recovered" }
        for (i in 0 until count) pdf.loadPage(i).use { }
        pdf.toBytes("garbage=4,compress")
    }

    override fun imagesToPdf(images: List<ByteArray>, page: ImagePage): ByteArray {
        require(images.isNotEmpty()) { "Choose at least one image" }
        val out = PDFDocument()
        try {
            images.forEachIndexed { n, original ->
                // MuPDF reads JPG, PNG and a few others; HEIC, WebP and the rest go through Android's
                // decoder first, which also applies the camera's turn.
                val bytes = if (AndroidImageOps.formatName(original) in setOf("JPG", "PNG")) original else try {
                    val b = AndroidImageOps.decode(original)
                    try { AndroidImageOps.encodeBytes(b, ImageFormat.Jpeg, 92) } finally { b.recycle() }
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Image ${n + 1}: ${e.message}")
                }
                val image = try { Image(bytes) } catch (e: Exception) { throw IllegalArgumentException("Image ${n + 1} can't be read") }
                try {
                    // MuPDF numbers orientations its own way, so read the camera's EXIF value directly.
                    val orientation = exifOrientation(bytes)
                    val turned = orientation in 5..8 // these swap width and height
                    var w = (if (turned) image.height else image.width).toFloat()
                    var h = (if (turned) image.width else image.height).toFloat()
                    // Very large photos would make huge pages; keep "own size" pages within A3.
                    if (page.width == null && maxOf(w, h) > 1191f) { val k = 1191f / maxOf(w, h); w *= k; h *= k }
                    val pw = page.width ?: w
                    val ph = page.height ?: h
                    val margin = if (page.width == null) 0f else 24f
                    val k = minOf((pw - 2 * margin) / w, (ph - 2 * margin) / h, if (page.width == null) 1f else Float.MAX_VALUE)
                    val dw = w * k; val dh = h * k
                    val x = (pw - dw) / 2; val y = (ph - dh) / 2
                    val resources = out.newDictionary()
                    val xobjects = out.newDictionary()
                    xobjects.put("Im0", out.addImage(image))
                    resources.put("XObject", xobjects)
                    val m = placeImage(orientation, x, y, dw, dh).joinToString(" ") { "%.3f".format(java.util.Locale.ROOT, it) }
                    out.insertPage(-1, out.addPage(Rect(0f, 0f, pw, ph), 0, resources, "q $m cm /Im0 Do Q"))
                } finally {
                    image.destroy()
                }
            }
            return out.toBytes()
        } finally {
            out.destroy()
        }
    }

    /**
     * The matrix that draws an image's unit square into the box ([x], [y], [w], [h]) as it should be
     * seen, for EXIF [orientation] 1 to 8 (0 = unknown, treated as 1).
     */
    private fun placeImage(orientation: Int, x: Float, y: Float, w: Float, h: Float): FloatArray = when (orientation) {
        2 -> floatArrayOf(-w, 0f, 0f, h, x + w, y)       // mirrored left to right
        3 -> floatArrayOf(-w, 0f, 0f, -h, x + w, y + h)  // upside down
        4 -> floatArrayOf(w, 0f, 0f, -h, x, y + h)       // mirrored top to bottom
        5 -> floatArrayOf(0f, -h, -w, 0f, x + w, y + h)  // mirrored and turned
        6 -> floatArrayOf(0f, -h, w, 0f, x, y + h)       // turn 90° clockwise to view
        7 -> floatArrayOf(0f, h, w, 0f, x, y)            // mirrored and turned
        8 -> floatArrayOf(0f, h, -w, 0f, x + w, y)       // turn 90° anticlockwise to view
        else -> floatArrayOf(w, 0f, 0f, h, x, y)
    }

    override fun pdfToImages(input: ByteArray, dpi: Int, format: ImageFormat): List<ByteArray> = withPdf(input) { pdf ->
        (0 until pdf.countPages()).map { i ->
            pdf.loadPage(i).use { page ->
                val bitmap = AndroidDrawDevice.drawPage(page, Matrix(dpi / 72f))
                try {
                    java.io.ByteArrayOutputStream().also { encode(bitmap, format, 90, it) }.toByteArray()
                } finally {
                    bitmap.recycle()
                }
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
internal fun PDFDocument.toBytes(options: String = "garbage,compress"): ByteArray = MemoryStream().also { save(it, options) }.toByteArray()

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

/** Runs [block] on a loaded page and frees it. */
internal inline fun <T> Page.use(block: (Page) -> T): T = try { block(this) } finally { destroy() }

/** Writes [bitmap] as [format]; JPEG has no transparency, so it is drawn on white first. */
internal fun encode(bitmap: android.graphics.Bitmap, format: ImageFormat, quality: Int, out: java.io.OutputStream) {
    val compress = when (format) {
        ImageFormat.Jpeg -> android.graphics.Bitmap.CompressFormat.JPEG
        ImageFormat.Png -> android.graphics.Bitmap.CompressFormat.PNG
        ImageFormat.Webp -> if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
    }
    if (format == ImageFormat.Jpeg && bitmap.hasAlpha()) {
        val flat = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        flat.compress(compress, quality, out)
        flat.recycle()
    } else {
        bitmap.compress(compress, quality, out)
    }
}

/** The EXIF orientation (1 to 8) saved by the camera, or 1 when there is none. */
internal fun exifOrientation(bytes: ByteArray): Int = runCatching {
    android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
}.getOrDefault(1)
