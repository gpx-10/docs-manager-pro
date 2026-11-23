package `in`.llmlabs.docsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentEngineTest {
    private val engine: DocumentEngine = MuPdfEngine()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun opensAPdfAndReportsItsPageCount() {
        engine.open(fixture("three-pages.pdf"), "three-pages.pdf").use { doc ->
            assertEquals(3, doc.pageCount)
        }
    }

    @Test
    fun rendersAPageAtTheRequestedWidthKeepingItsProportions() {
        engine.open(fixture("three-pages.pdf"), "three-pages.pdf").use { doc ->
            val bitmap = doc.renderPage(0, widthPx = 600)
            // The fixture is A4 (595 x 842 pt), so 600 px wide is about 849 px tall.
            assertEquals(600, bitmap.width)
            assertTrue("height was ${bitmap.height}", bitmap.height in 848..850)
            // "Page 1" is drawn near the top left; the bottom of the page is blank.
            assertTrue(hasDarkPixel(bitmap, 60..400, 60..140))
            assertTrue(!hasDarkPixel(bitmap, 0..599, 500..840))
        }
    }

    private fun hasDarkPixel(b: android.graphics.Bitmap, xs: IntRange, ys: IntRange): Boolean =
        xs.any { x -> ys.any { y -> android.graphics.Color.luminance(b.getPixel(x, y)) < 0.5f } }
}
