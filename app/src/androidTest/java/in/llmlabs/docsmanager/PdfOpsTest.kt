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
}
