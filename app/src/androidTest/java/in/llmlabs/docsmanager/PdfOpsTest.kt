package `in`.llmlabs.docsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
