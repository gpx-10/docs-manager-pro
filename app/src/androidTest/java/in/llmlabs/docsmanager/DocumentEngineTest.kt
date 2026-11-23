package `in`.llmlabs.docsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
}
