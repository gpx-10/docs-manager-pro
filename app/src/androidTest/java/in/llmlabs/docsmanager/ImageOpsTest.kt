package `in`.llmlabs.docsmanager

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageOpsTest {
    private val ops: ImageOps = AndroidImageOps()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** "WxH" of a Result, decoded independently of ImageOps. */
    private fun dims(image: ByteArray): String =
        BitmapFactory.decodeByteArray(image, 0, image.size).let { "${it.width}x${it.height}" }

    @Test
    fun infoReadsSizeAndFormatTheWayThePhotoIsSeen() {
        assertEquals(ImageInfo(600, 400, "PNG"), ops.info(fixture("wide.png")))
        assertEquals(ImageInfo(600, 400, "HEIC"), ops.info(fixture("wide.heic")))
        // Stored 600x400, but the camera's EXIF says to turn it, so people see it as 400x600.
        assertEquals(ImageInfo(400, 600, "JPG"), ops.info(fixture("wide-exif6.jpg")))
    }

    @Test
    fun resizeMakesTheImageExactlyTheChosenSize() {
        assertEquals("300x200", dims(ops.resize(fixture("wide.png"), 300, 200)))
    }

    /** Red and green near the top-left corner. wide.png: red grows to the right, green grows downwards. */
    private fun topLeft(image: ByteArray): Pair<Int, Int> =
        BitmapFactory.decodeByteArray(image, 0, image.size).getPixel(3, 3).let { Color.red(it) to Color.green(it) }

    @Test
    fun cropKeepsOnlyTheChosenArea() {
        // The bottom-right quarter: its top-left corner is half red, half green.
        val result = ops.crop(fixture("wide.png"), left = 300, top = 200, width = 300, height = 200)
        assertEquals("300x200", dims(result))
        val (r, g) = topLeft(result)
        assertTrue("r=$r g=$g", r in 90..150 && g in 90..150)
    }

    @Test
    fun rotateTurnsClockwise() {
        val result = ops.rotate(fixture("wide.png"), degrees = 90)
        assertEquals("400x600", dims(result))
        val (r, g) = topLeft(result) // the old bottom-left: no red, full green
        assertTrue("r=$r g=$g", r < 70 && g > 160)
    }

    @Test
    fun flipMirrorsLeftToRight() {
        val result = ops.rotate(fixture("wide.png"), degrees = 0, flipHorizontal = true)
        assertEquals("600x400", dims(result))
        val (r, g) = topLeft(result) // the old top-right: full red, no green
        assertTrue("r=$r g=$g", r > 160 && g < 70)
    }

    @Test
    fun compressMakesAJpgSmallerAtTheSameSize() {
        val input = fixture("wide.jpg")
        val result = ops.compress(input, quality = 40)
        assertTrue("${result.size} vs ${input.size}", result.size < input.size * 0.7)
        assertEquals("600x400", dims(result))
        assertEquals("JPG", ops.info(result).format)
    }

    @Test
    fun compressSavesAPngPhotoAsAMuchSmallerJpg() {
        val input = fixture("wide.png")
        val result = ops.compress(input, quality = 70)
        assertTrue("${result.size} vs ${input.size}", result.size < input.size / 3)
        assertEquals("JPG", ops.info(result).format)
    }

    @Test
    fun convertWritesTheChosenFormat() {
        assertEquals("JPG", ops.info(ops.convert(fixture("wide.png"), ImageFormat.Jpeg)).format)
        assertEquals("WEBP", ops.info(ops.convert(fixture("wide.png"), ImageFormat.Webp)).format)
        assertEquals("PNG", ops.info(ops.convert(fixture("wide.jpg"), ImageFormat.Png)).format)
    }

    @Test
    fun heicPhotosBecomeJpgsThatOpenAnywhere() {
        val result = ops.convert(fixture("wide.heic"), ImageFormat.Jpeg)
        assertEquals("600x400", dims(result))
        assertEquals("JPG", ops.info(result).format)
    }

    @Test
    fun watermarkChangesThePictureButNotItsSize() {
        val input = fixture("wide.png")
        val result = ops.watermark(input, "SAMPLE", opacity = 0.6f)
        assertEquals("600x400", dims(result))
        val before = BitmapFactory.decodeByteArray(input, 0, input.size)
        val after = BitmapFactory.decodeByteArray(result, 0, result.size)
        // Count changed pixels along the middle row, where the text runs.
        val changed = (0 until 600 step 2).count { x ->
            val a = before.getPixel(x, 200); val b = after.getPixel(x, 200)
            Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)) > 60
        }
        assertTrue("only $changed pixels changed", changed > 15)
    }

    @Test
    fun aPhotographedSignatureLosesItsPaperAndIsTrimmedToTheInk() {
        // A 400x200 "photo": off-white paper with a dark blue stroke in the middle.
        val photo = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(photo)
        c.drawColor(Color.rgb(240, 238, 230))
        c.drawRect(100f, 90f, 300f, 110f, android.graphics.Paint().apply { color = Color.rgb(20, 40, 120) })
        val jpg = java.io.ByteArrayOutputStream().also { photo.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

        val png = ops.cutOutSignature(jpg)
        val out = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals("PNG", ops.info(png).format)
        assertTrue("${out.width}x${out.height}", out.width in 200..240 && out.height in 20..50) // trimmed to the stroke
        assertEquals(0, Color.alpha(out.getPixel(1, 1)))                                         // paper is see-through
        assertTrue(Color.alpha(out.getPixel(out.width / 2, out.height / 2)) > 200)              // ink stays
    }
}
