package `in`.llmlabs.docsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfOpsTest {
    private val ops: PdfOps = MuPdfOps()
    private val engine: DocumentEngine = MuPdfEngine()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** Page widths of a Result, read back through the public DocumentEngine. Fixtures give each page a known width. */
    private fun widths(pdf: ByteArray): List<Int> = engine.open(pdf, "result.pdf").use { doc ->
        (0 until doc.pageCount).map { doc.pageSize(it).width.toInt() }
    }

    @Test
    fun mergeJoinsDocumentsInOrder() {
        val result = ops.merge(listOf(fixture("mixed-sizes.pdf"), fixture("letter-two.pdf")))
        assertEquals(listOf(300, 400, 500, 612, 612), widths(result))
    }

    @Test
    fun mergeNamesTheFirstLockedInput() {
        val e = assertThrows(LockedPdfException::class.java) {
            ops.merge(listOf(fixture("three-pages.pdf"), fixture("locked-secret.pdf")))
        }
        assertEquals(1, e.index)
    }

    @Test
    fun splitMakesOneDocumentPerRange() {
        val parts = ops.split(fixture("mixed-sizes.pdf"), listOf(0..0, 1..2))
        assertEquals(listOf(listOf(300), listOf(400, 500)), parts.map(::widths))
    }

    /** Page sizes as "w x h", so a turned page shows up as swapped numbers. */
    private fun sizes(pdf: ByteArray): List<String> = engine.open(pdf, "result.pdf").use { doc ->
        (0 until doc.pageCount).map { doc.pageSize(it).let { s -> "${s.width.toInt()}x${s.height.toInt()}" } }
    }

    @Test
    fun rotateTurnsOnlyTheChosenPages() {
        val result = ops.rotate(fixture("mixed-sizes.pdf"), pages = setOf(1), degrees = 90)
        assertEquals(listOf("300x600", "600x400", "500x600"), sizes(result))
    }

    @Test
    fun organizeReordersDropsAndInsertsBlankPages() {
        // Page 3 first, then a blank page (sized like the page before it), then page 1; page 2 is dropped.
        val result = ops.organize(fixture("mixed-sizes.pdf"), listOf(2, null, 0))
        assertEquals(listOf("500x600", "500x600", "300x600"), sizes(result))
    }

    @Test
    fun protectLocksTheDocumentWithThePassword() {
        val locked = ops.protect(fixture("three-pages.pdf"), "s3cret!")
        assertThrows(PasswordRequiredException::class.java) { engine.open(locked, "locked.pdf") }
        assertEquals(3, engine.open(locked, "locked.pdf", "s3cret!").use { it.pageCount })
    }

    @Test
    fun unlockRemovesAPasswordYouKnow() {
        val open = ops.unlock(fixture("locked-secret.pdf"), "secret")
        assertEquals(3, engine.open(open, "open.pdf").use { it.pageCount })
    }

    @Test
    fun unlockRefusesTheWrongPassword() {
        val e = assertThrows(PasswordRequiredException::class.java) { ops.unlock(fixture("locked-secret.pdf"), "nope") }
        assertEquals(true, e.wrongPassword)
    }

    @Test
    fun metadataIsSavedAndReadBack() {
        val info = PdfInfo(title = "Quarterly Report", author = "Ana Rao", subject = "Q3 results", keywords = "sales, 2026")
        val result = ops.setMetadata(fixture("three-pages.pdf"), info)
        assertEquals(info, ops.metadata(result))
    }

    @Test
    fun cropTrimsTheMarginsFromEveryPage() {
        val result = ops.crop(fixture("mixed-sizes.pdf"), Margins(top = 100f, right = 20f, bottom = 50f, left = 30f))
        assertEquals(listOf("250x450", "350x450", "450x450"), sizes(result))
    }

    private fun texts(pdf: ByteArray): List<String> = engine.open(pdf, "result.pdf").use { doc ->
        (0 until doc.pageCount).map { doc.pageText(it) }
    }

    @Test
    fun pageNumbersAreWrittenOnEveryPage() {
        val result = ops.addPageNumbers(fixture("mixed-sizes.pdf"), format = "Page {n} of {total}", position = NumberPosition.BottomCenter)
        val pages = texts(result)
        assertEquals(listOf(true, true, true), pages.mapIndexed { i, text -> "Page ${i + 1} of 3" in text })
        assertEquals(true, "Width 400" in pages[1]) // the page keeps what it had
    }

    @Test
    fun watermarkIsStampedOnEveryPage() {
        val result = ops.watermark(fixture("mixed-sizes.pdf"), "CONFIDENTIAL", opacity = 0.3f)
        assertEquals(listOf(true, true, true), texts(result).map { "CONFIDENTIAL" in it })
    }

    @Test
    fun compressShrinksALargePhotoAndKeepsThePage() {
        val input = fixture("big-photo.pdf")
        val result = ops.compress(input, CompressLevel.Recommended)
        assertTrue("got ${result.size} bytes from ${input.size}", result.size < input.size / 4)
        assertEquals(listOf("400x400"), sizes(result))
    }

    /** Colour near each corner of page 1 (top-left, top-right, bottom-left, bottom-right), as RGB triples. */
    private fun corners(pdf: ByteArray): List<List<Int>> = engine.open(pdf, "result.pdf").use { doc ->
        val b = doc.renderPage(0, 200)
        listOf(10 to 10, b.width - 10 to 10, 10 to b.height - 10, b.width - 10 to b.height - 10).map { (x, y) ->
            val c = b.getPixel(x, y)
            listOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
        }
    }

    @Test
    fun compressKeepsThePictureTheRightWayRound() {
        val input = fixture("big-photo.pdf")
        val before = corners(input)
        val after = corners(ops.compress(input, CompressLevel.Strong))
        before.zip(after).forEach { (a, b) ->
            a.zip(b).forEach { (x, y) -> assertTrue("corner colours moved: $before -> $after", Math.abs(x - y) < 40) }
        }
    }

    @Test
    fun repairRebuildsADamagedFile() {
        val result = ops.repair(fixture("damaged.pdf"))
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), texts(result).map { it.trim() })
    }

    @Test
    fun imagesToPdfMakesOnePagePerImageAtItsOwnSize() {
        val result = ops.imagesToPdf(listOf(fixture("wide.png"), fixture("wide.jpg")), ImagePage.FitImage)
        assertEquals(listOf("600x400", "600x400"), sizes(result))
    }

    @Test
    fun imagesToPdfTurnsPhonePhotosTheWayTheCameraMeantThem() {
        // wide-exif6.jpg is stored 600x400 but its EXIF says "turn 90° clockwise to view".
        val result = ops.imagesToPdf(listOf(fixture("wide-exif6.jpg")), ImagePage.FitImage)
        assertEquals(listOf("400x600"), sizes(result))
        // Turned clockwise, the stored bottom-left corner (no red, full green) lands top-left.
        val (r, g, _) = corners(result)[0]
        assertTrue("top-left was r=$r g=$g", g > 150 && r < 80)
    }

    @Test
    fun imagesToPdfTakesIphonePhotos() {
        val result = ops.imagesToPdf(listOf(fixture("wide.heic")), ImagePage.FitImage)
        assertEquals(listOf("600x400"), sizes(result))
    }

    @Test
    fun imagesToPdfCanCentreImagesOnA4Pages() {
        val result = ops.imagesToPdf(listOf(fixture("wide.png")), ImagePage.A4)
        assertEquals(listOf("595x842"), sizes(result))
    }

    @Test
    fun pdfToImagesDrawsEachPageAtTheChosenSharpness() {
        val images = ops.pdfToImages(fixture("mixed-sizes.pdf"), dpi = 144, format = ImageFormat.Png)
        val dims = images.map { bytes: ByteArray -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).let { b -> "${b.width}x${b.height}" } }
        assertEquals(listOf("600x1200", "800x1200", "1000x1200"), dims)
        assertEquals(true, images.all { it[1] == 'P'.code.toByte() && it[2] == 'N'.code.toByte() })
    }

    /** Share of inked (not white) pixels in an area of page 1, drawn 300 px wide; the area is in points from the top-left. */
    private fun inkIn(pdf: ByteArray, x: Float, y: Float, w: Float, h: Float): Float = engine.open(pdf, "r.pdf").use { doc ->
        val size = doc.pageSize(0)
        val b = doc.renderPage(0, 300)
        val k = 300f / size.width
        var dark = 0; var all = 0
        for (py in (y * k).toInt() until ((y + h) * k).toInt()) for (px in (x * k).toInt() until ((x + w) * k).toInt()) {
            val c = b.getPixel(px, py)
            all++
            if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < 600) dark++ // anything not white
        }
        dark.toFloat() / maxOf(1, all)
    }

    @Test
    fun aSignatureImageIsPlacedWhereChosen() {
        // A solid black "signature" placed in the lower right of page 1 (300 x 600 pt).
        val ink = android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        val png = java.io.ByteArrayOutputStream().also { ink.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val result = ops.addImage(fixture("mixed-sizes.pdf"), page = 0, image = png, x = 180f, y = 500f, width = 100f, height = 50f)
        assertTrue(inkIn(result, 190f, 510f, 80f, 30f) > 0.9f)
        assertTrue(inkIn(result, 20f, 300f, 80f, 30f) < 0.05f) // and nowhere else
        assertEquals(3, engine.open(result, "r.pdf").use { it.pageCount })
    }

    @Test
    fun redactRemovesTheTextForGood() {
        val result = ops.redact(fixture("mixed-sizes.pdf"), listOf("400"))
        assertEquals(1, result.count)
        val pages = texts(result.pdf)
        assertEquals(false, pages.any { "400" in it })
        assertEquals(true, "Width" in pages[1]) // the rest of the line stays
    }

    @Test
    fun replaceTextChangesWordsOnAPage() {
        val result = ops.replaceText(fixture("mixed-sizes.pdf"), page = 1, old = "Width 400", new = "Height 900")
        val text = texts(result)[1]
        assertEquals(true, "Height 900" in text)
        assertEquals(false, "Width 400" in text)
    }

    @Test
    fun highlightsAndNotesAreSavedAsAnnotations() {
        val marked = ops.highlight(fixture("mixed-sizes.pdf"), listOf("Width"))
        assertEquals(3, marked.count)
        val noted = ops.addNote(marked.pdf, page = 2, x = 40f, y = 40f, text = "Check this figure")
        val notes = ops.annotations(noted)
        assertEquals(3, notes.count { it.type == "Highlight" })
        assertEquals(listOf(PdfAnnotation(2, "Text", "Check this figure")), notes.filter { it.type == "Text" })
    }

    @Test
    fun compareFindsTheChangedPagesOnly() {
        val original = fixture("mixed-sizes.pdf")
        val changed = ops.replaceText(original, page = 1, old = "Width 400", new = "Width 401")
        val same = ops.compare(original, original)
        assertEquals(listOf(0f, 0f, 0f), same.changed)
        val diff = ops.compare(original, changed)
        assertEquals(listOf(false, true, false), diff.changed.map { it > 0f })
        assertEquals(3, engine.open(diff.report, "report.pdf").use { it.pageCount })
    }

    @Test
    fun ocrMakesAScannedPageSearchable() {
        val scan = fixture("scanned.pdf")
        assertEquals("", texts(scan)[0].trim()) // a picture of text has no text
        val result = MuPdfOps(InstrumentationRegistry.getInstrumentation().targetContext).ocr(scan)
        val text = texts(result)[0]
        assertTrue(text, "Invoice 2048" in text && "Northwind" in text)
        assertEquals(listOf("595x842"), sizes(result)) // the page itself is unchanged
    }

    /* ---------- The page editor ---------- */

    @Test
    fun textLinesSayWhereEachLineIs() {
        val lines = ops.textLines(fixture("mixed-sizes.pdf"), page = 1)
        val line = lines.single { it.text == "Width 400" }
        // pdf-lib drew it at x 20, baseline 560 from the bottom of a 600 pt page: near the top-left.
        assertTrue("$line", line.x0 in 15f..25f && line.y0 in 15f..40f && line.x1 > line.x0 + 50)
    }

    @Test
    fun addTextWritesNewWordsWhereTapped() {
        val result = ops.addText(fixture("mixed-sizes.pdf"), page = 0, x = 40f, y = 300f, text = "Approved", size = 20f, color = 0xFF1B3A8C.toInt())
        assertTrue("Approved" in texts(result)[0])
        assertTrue(inkIn(result, 40f, 300f, 90f, 20f) > 0.05f)
    }

    @Test
    fun whiteoutCoversWhatIsUnderIt() {
        // "Width 300" sits near the top-left of page 1; a white box over it hides it.
        assertTrue(inkIn(fixture("mixed-sizes.pdf"), 18f, 20f, 100f, 25f) > 0.02f)
        val result = ops.addRect(fixture("mixed-sizes.pdf"), page = 0, x = 10f, y = 10f, width = 200f, height = 50f, fill = 0xFFFFFFFF.toInt(), stroke = null)
        assertTrue(inkIn(result, 18f, 20f, 100f, 25f) < 0.005f)
    }

    @Test
    fun drawingsAndAreaHighlightsBecomeAnnotations() {
        val drawn = ops.addInk(fixture("mixed-sizes.pdf"), page = 0, strokes = listOf(listOf(20f to 200f, 120f to 260f, 200f to 210f)), color = 0xFFE5484D.toInt(), width = 3f)
        val marked = ops.highlightArea(drawn, page = 1, x = 15f, y = 15f, width = 120f, height = 30f)
        val types = ops.annotations(marked).map { it.page to it.type }
        assertTrue(types.toString(), (0 to "Ink") in types && (1 to "Highlight") in types)
    }

    @Test
    fun redactAreaRemovesTheTextUnderIt() {
        val result = ops.redactArea(fixture("mixed-sizes.pdf"), page = 1, x = 10f, y = 10f, width = 200f, height = 50f)
        assertEquals(false, "Width 400" in texts(result)[1])
    }

    @Test
    fun replaceAreaChangesOneLineInPlace() {
        val line = ops.textLines(fixture("mixed-sizes.pdf"), page = 2).single { it.text == "Width 500" }
        val result = ops.replaceArea(fixture("mixed-sizes.pdf"), page = 2, x = line.x0, y = line.y0, width = line.x1 - line.x0, height = line.y1 - line.y0, text = "Width 555")
        val text = texts(result)[2]
        assertTrue(text, "Width 555" in text && "Width 500" !in text)
    }

    @Test
    fun editingABoldLineKeepsItBold() {
        val pdf = MuPdfOfficeEngine().toPdf("<h1>Big Title</h1><p>Plain words here.</p>".toByteArray(), "t.html")
        val lines = ops.textLines(pdf, 0)
        assertEquals(true, lines.single { it.text == "Big Title" }.bold)
        assertEquals(false, lines.single { it.text == "Plain words here." }.bold)
        val title = lines.single { it.text == "Big Title" }
        val result = ops.replaceArea(pdf, 0, title.x0, title.y0, title.x1 - title.x0, title.y1 - title.y0, "New Title", bold = title.bold)
        assertEquals(true, ops.textLines(result, 0).single { it.text == "New Title" }.bold)
    }
}
