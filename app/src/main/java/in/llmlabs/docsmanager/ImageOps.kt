package `in`.llmlabs.docsmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The image tools from llmlabs.in: image bytes in, new image bytes out, all on the phone.
 * Results keep the input's format (JPG, PNG or WebP); other formats such as HEIC become JPG.
 * Throws IllegalArgumentException with a message to show when an image can't be read.
 */
interface ImageOps {
    /** Size as people see it (after the camera's EXIF turn) and the file format, like "JPG". */
    fun info(input: ByteArray): ImageInfo

    /** Stretches or shrinks the image to exactly [width] x [height] pixels. */
    fun resize(input: ByteArray, width: Int, height: Int): ByteArray

    /** Keeps the [width] x [height] area whose top-left corner is at ([left], [top]), in pixels as seen. */
    fun crop(input: ByteArray, left: Int, top: Int, width: Int, height: Int): ByteArray

    /** Turns the image clockwise by [degrees] (a multiple of 90), then mirrors it if asked. */
    fun rotate(input: ByteArray, degrees: Int, flipHorizontal: Boolean = false, flipVertical: Boolean = false): ByteArray

    /**
     * Makes the file smaller at the same pixel size. JPG and WebP are saved again at [quality] (1-100).
     * PNG can't lose detail, so a PNG is saved as JPG, or as WebP if it has transparent parts.
     */
    fun compress(input: ByteArray, quality: Int): ByteArray

    /** Saves the image as [format]. Transparent parts become white in JPG. */
    fun convert(input: ByteArray, format: ImageFormat, quality: Int = 92): ByteArray

    /** Writes [text] across the middle of the image, see-through by [opacity] (0 to 1); [tiled] repeats it all over. */
    fun watermark(input: ByteArray, text: String, opacity: Float = 0.5f, tiled: Boolean = false): ByteArray
}

data class ImageInfo(val width: Int, val height: Int, val format: String)

/** ImageOps using Android's image decoders and encoders. */
class AndroidImageOps : ImageOps {
    override fun info(input: ByteArray): ImageInfo {
        val b = decode(input)
        try {
            return ImageInfo(b.width, b.height, formatName(input))
        } finally {
            b.recycle()
        }
    }

    override fun resize(input: ByteArray, width: Int, height: Int): ByteArray {
        require(width in 1..12000 && height in 1..12000) { "Choose a size between 1 and 12000 pixels" }
        return transform(input) { Bitmap.createScaledBitmap(it, width, height, true) }
    }

    override fun crop(input: ByteArray, left: Int, top: Int, width: Int, height: Int): ByteArray = transform(input) { b ->
        require(left >= 0 && top >= 0 && width >= 1 && height >= 1 && left + width <= b.width && top + height <= b.height) {
            "The crop area must fit inside the ${b.width} x ${b.height} image"
        }
        Bitmap.createBitmap(b, left, top, width, height)
    }

    override fun rotate(input: ByteArray, degrees: Int, flipHorizontal: Boolean, flipVertical: Boolean): ByteArray = transform(input) { b ->
        val m = android.graphics.Matrix().apply {
            postRotate(Math.floorMod(degrees, 360).toFloat())
            postScale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f)
        }
        if (m.isIdentity) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    override fun compress(input: ByteArray, quality: Int): ByteArray {
        val b = decode(input)
        try {
            val format = when (formatName(input)) {
                "WEBP" -> ImageFormat.Webp
                "PNG" -> if (hasTransparency(b)) ImageFormat.Webp else ImageFormat.Jpeg
                else -> ImageFormat.Jpeg
            }
            return encodeBytes(b, format, quality.coerceIn(1, 100))
        } finally {
            b.recycle()
        }
    }

    override fun convert(input: ByteArray, format: ImageFormat, quality: Int): ByteArray {
        val b = decode(input)
        try {
            return encodeBytes(b, format, quality)
        } finally {
            b.recycle()
        }
    }

    override fun watermark(input: ByteArray, text: String, opacity: Float, tiled: Boolean): ByteArray = transform(input) { b ->
        require(text.isNotBlank()) { "Enter the watermark text" }
        val canvas = android.graphics.Canvas(b)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = (opacity.coerceIn(0.05f, 1f) * 255).toInt()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(maxOf(2f, b.width / 300f), 0f, 0f, android.graphics.Color.argb((opacity * 200).toInt(), 0, 0, 0))
        }
        // Size the text to about 60% of the width (or a smaller repeating size when tiled).
        paint.textSize = 100f
        val target = if (tiled) b.width * 0.3f else b.width * 0.6f
        paint.textSize = (100f * target / paint.measureText(text)).coerceIn(10f, b.height * 0.5f)
        val baselineShift = (paint.descent() + paint.ascent()) / 2
        if (tiled) {
            val stepX = paint.measureText(text) * 1.6f
            val stepY = paint.textSize * 3f
            canvas.save()
            canvas.rotate(-30f, b.width / 2f, b.height / 2f)
            var y = -b.height.toFloat()
            var row = 0
            while (y < b.height * 2) {
                var x = -b.width.toFloat() + if (row % 2 == 0) 0f else stepX / 2
                while (x < b.width * 2) { canvas.drawText(text, x, y - baselineShift, paint); x += stepX }
                y += stepY; row++
            }
            canvas.restore()
        } else {
            canvas.drawText(text, b.width / 2f, b.height / 2f - baselineShift, paint)
        }
        b
    }

    private fun hasTransparency(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val step = maxOf(1, minOf(b.width, b.height) / 64)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) if (android.graphics.Color.alpha(b.getPixel(x, y)) < 250) return true
        return false
    }

    /** Decodes [input], applies [change], and encodes the result in the same format. */
    private inline fun transform(input: ByteArray, quality: Int = 92, change: (Bitmap) -> Bitmap): ByteArray {
        val src = decode(input)
        val out = change(src)
        try {
            return encodeBytes(out, outputFormat(input), quality)
        } finally {
            if (out !== src) out.recycle()
            src.recycle()
        }
    }

    companion object {
        /** Decodes any image Android can read, turned the way the camera meant (EXIF). */
        fun decode(input: ByteArray): Bitmap = try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.isMutableRequired = true
                }
            } else {
                val raw = BitmapFactory.decodeByteArray(input, 0, input.size) ?: throw IllegalArgumentException()
                applyExif(raw, exifOrientation(input))
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(if (formatName(input) == "HEIC") "HEIC photos need Android 9 or newer" else "This image can't be read")
        } catch (e: Exception) {
            throw IllegalArgumentException("This image can't be read")
        }

        /** The file format, read from the first bytes. */
        fun formatName(b: ByteArray): String = when {
            b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "PNG"
            b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "JPG"
            b.size > 11 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "WEBP"
            b.size > 11 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
                String(b, 8, 4, Charsets.ISO_8859_1) in setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1") -> "HEIC"
            b.size > 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "GIF"
            b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "BMP"
            else -> "Image"
        }

        /** Results keep JPG, PNG and WebP; everything else is saved as JPG. */
        fun outputFormat(input: ByteArray): ImageFormat = when (formatName(input)) {
            "PNG" -> ImageFormat.Png
            "WEBP" -> ImageFormat.Webp
            else -> ImageFormat.Jpeg
        }

        fun encodeBytes(bitmap: Bitmap, format: ImageFormat, quality: Int): ByteArray =
            ByteArrayOutputStream().also { encode(bitmap, format, quality, it) }.toByteArray()

        private fun applyExif(b: Bitmap, orientation: Int): Bitmap {
            if (orientation <= 1) return b
            val m = android.graphics.Matrix()
            when (orientation) {
                2 -> m.setScale(-1f, 1f)
                3 -> m.setRotate(180f)
                4 -> m.setScale(1f, -1f)
                5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.setRotate(90f)
                7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.setRotate(-90f)
            }
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
        }
    }
}
