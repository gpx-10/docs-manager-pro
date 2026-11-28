package `in`.llmlabs.docsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.llmlabs.docsmanager.office.SlideContent
import `in`.llmlabs.docsmanager.office.Sheet
import `in`.llmlabs.docsmanager.office.readPptx
import `in`.llmlabs.docsmanager.office.writePptx
import `in`.llmlabs.docsmanager.office.writeXlsx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeEngineTest {
    private val office: OfficeEngine = MuPdfOfficeEngine()
    private val engine: DocumentEngine = MuPdfEngine()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** All the text of a document, read back through the DocumentEngine (which opens PDF and Office files). */
    private fun text(bytes: ByteArray, name: String): String = engine.open(bytes, name).use { d -> (0 until d.pageCount).joinToString("\n") { d.pageText(it) } }

    private fun sizes(pdf: ByteArray): List<String> = engine.open(pdf, "r.pdf").use { d ->
        (0 until d.pageCount).map { d.pageSize(it).let { s -> "${s.width.toInt()}x${s.height.toInt()}" } }
    }

    @Test
    fun wordBecomesAPdfWithRealText() {
        val pdf = office.toPdf(fixture("report.docx"), "report.docx")
        val t = text(pdf, "r.pdf")
        assertTrue(t, "Annual Report" in t && "North" in t && "twelve percent" in t)
        assertEquals("595x842", sizes(pdf).first()) // A4
    }

    @Test
    fun htmlBecomesAPdf() {
        val pdf = office.toPdf("<h1>Price list</h1><p>Tea costs 3 dollars.</p>".toByteArray(), "list.html", PageSetup(landscape = true))
        assertTrue("Tea costs 3 dollars" in text(pdf, "r.pdf"))
        assertEquals("842x595", sizes(pdf).first()) // A4 landscape
    }

    @Test
    fun excelBecomesAPdfTable() {
        val xlsx = writeXlsx(listOf(Sheet("Sales", listOf(listOf("Region", "Sales"), listOf("North", 120.0)))))
        val t = text(office.toPdf(xlsx, "sales.xlsx"), "r.pdf")
        assertTrue(t, "Region" in t && "North" in t && "120" in t)
    }

    @Test
    fun powerPointSlidesBecomePagesOfTheSlideSize() {
        val pptx = writePptx(listOf(SlideContent.Text("Plan for 2027", listOf("Grow sales")), SlideContent.Text("Team", listOf("Hire two people"))))
        val pdf = office.toPdf(pptx, "plan.pptx")
        assertEquals(listOf("960x540", "960x540"), sizes(pdf))
        assertTrue("Plan for 2027" in engine.open(pdf, "r.pdf").use { it.pageText(0) })
        assertTrue("Hire two people" in engine.open(pdf, "r.pdf").use { it.pageText(1) })
    }

    @Test
    fun pdfBecomesAnEditableWordFile() {
        val docx = office.pdfToWord(fixture("mixed-sizes.pdf"), exactLook = false)
        val t = text(docx, "out.docx")
        assertTrue(t, "Width 300" in t && "Width 500" in t)
    }

    @Test
    fun pdfTablesBecomeExcelCells() {
        val table = office.toPdf("<table><tr><td>Item</td><td>Price</td></tr><tr><td>Tea</td><td>3</td></tr><tr><td>Cake</td><td>5</td></tr></table>".toByteArray(), "t.html")
        val xlsx = office.pdfToExcel(table, oneSheet = true)
        val t = text(xlsx, "out.xlsx")
        assertTrue(t, "Item" in t && "Cake" in t && "5" in t)
    }

    @Test
    fun pdfPagesBecomeSlides() {
        val deck = readPptx(office.pdfToPowerPoint(fixture("mixed-sizes.pdf")))
        assertEquals(3, deck.slides.size)
        assertTrue(deck.slides.all { s -> s.shapes.any { it is `in`.llmlabs.docsmanager.office.Shape.Picture } })
    }

    @Test
    fun pdfaKeepsEveryPageAndDeclaresTheStandard() {
        val pdfa = office.pdfToPdfA(fixture("three-pages.pdf"), "Archived")
        assertEquals(3, engine.open(pdfa, "a.pdf").use { it.pageCount })
        val raw = String(pdfa, Charsets.ISO_8859_1)
        assertTrue("pdfaid:part>2" in raw && "/OutputIntents" in raw && "GTS_PDFA1" in raw)
    }
}
