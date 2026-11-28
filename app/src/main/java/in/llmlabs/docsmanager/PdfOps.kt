package `in`.llmlabs.docsmanager

/** Tools that turn PDFs into new PDFs, entirely on the phone. Inputs and Results are whole files in memory. */
interface PdfOps {
    /**
     * Joins [inputs] into one PDF, keeping their order and the order of pages inside each.
     * Throws [LockedPdfException] naming the first password-protected input.
     */
    fun merge(inputs: List<ByteArray>): ByteArray

    /** Makes one new PDF per range of 0-based page indexes (see [parsePageRanges]). Throws [LockedPdfException] if [input] is locked. */
    fun split(input: ByteArray, ranges: List<IntRange>): List<ByteArray>

    /** Turns the 0-based [pages] clockwise by [degrees] (a multiple of 90), on top of any turn they already have. */
    fun rotate(input: ByteArray, pages: Set<Int>, degrees: Int): ByteArray

    /**
     * Builds a new PDF from [pages]: each entry is a 0-based page of [input] (repeats allowed),
     * or null for a blank page the size of the page before it. Pages left out are dropped,
     * which is also how Remove pages and Extract pages work.
     */
    fun organize(input: ByteArray, pages: List<Int?>): ByteArray

    /** Locks [input] with [password] (AES-256): it is needed to open the Result. */
    fun protect(input: ByteArray, password: String): ByteArray

    /** Removes the password from [input]. Throws [PasswordRequiredException] (wrongPassword = true) if [password] is wrong. */
    fun unlock(input: ByteArray, password: String): ByteArray

    /** The document's title, author, subject and keywords (empty when not set). */
    fun metadata(input: ByteArray): PdfInfo

    /** Replaces the title, author, subject and keywords; an empty value removes that field. */
    fun setMetadata(input: ByteArray, info: PdfInfo): ByteArray

    /** Trims [margins] (in points, 72 per inch) from every page. Throws IllegalArgumentException if nothing of a page would be left. */
    fun crop(input: ByteArray, margins: Margins): ByteArray

    /**
     * Writes a number on every page. In [format], {n} is the page number (counting from
     * [startAt]) and {total} the number of pages, for example "Page {n} of {total}".
     */
    fun addPageNumbers(input: ByteArray, format: String, position: NumberPosition, startAt: Int = 1): ByteArray

    /** Writes [text] large and at an angle across the middle of every page, see-through by [opacity] (0 to 1). */
    fun watermark(input: ByteArray, text: String, opacity: Float = 0.3f): ByteArray

    /** Makes the file smaller: drops unused data, and at [CompressLevel.Recommended] and above, re-saves large images at a lower resolution. */
    fun compress(input: ByteArray, level: CompressLevel): ByteArray

    /** Rebuilds a damaged file's structure, recovering every page that can be read. Throws if nothing can be recovered. */
    fun repair(input: ByteArray): ByteArray

    /**
     * Makes a PDF with one page per image (JPEG, PNG and other formats MuPDF reads), turned the way
     * the camera meant (EXIF orientation). [page] sets the page size; JPEGs are kept as they are.
     */
    fun imagesToPdf(images: List<ByteArray>, page: ImagePage): ByteArray

    /** Draws every page as an image at [dpi] (72 = actual size). Throws [LockedPdfException] if locked. */
    fun pdfToImages(input: ByteArray, dpi: Int, format: ImageFormat): List<ByteArray>

    /**
     * Places an image (a signature, a stamp) on page [page] at ([x], [y]) from the top-left corner,
     * [width] x [height] points, as the reader sees the page.
     */
    fun addImage(input: ByteArray, page: Int, image: ByteArray, x: Float, y: Float, width: Float, height: Float): ByteArray

    /** Permanently removes every occurrence of [terms] (text and what's under it), leaving black boxes. */
    fun redact(input: ByteArray, terms: List<String>): Changed

    /** Replaces [old] with [new] on page [page], in a standard font. Throws IllegalArgumentException if [old] isn't there. */
    fun replaceText(input: ByteArray, page: Int, old: String, new: String): ByteArray

    /** Highlights every occurrence of [terms] in yellow. */
    fun highlight(input: ByteArray, terms: List<String>): Changed

    /** Adds a comment note at ([x], [y]) from the top-left of page [page]. */
    fun addNote(input: ByteArray, page: Int, x: Float, y: Float, text: String): ByteArray

    /** Highlights and comment notes in the document, page by page. */
    fun annotations(input: ByteArray): List<PdfAnnotation>

    /** Compares two versions page by page by how they look; the report shows changes in red. */
    fun compare(a: ByteArray, b: ByteArray): Comparison

    /** Recognises the text in scanned pages (English) and adds it invisibly, so it can be searched and copied. */
    fun ocr(input: ByteArray): ByteArray

    /* The page editor works in points from the page's top-left corner, as the reader sees it. */

    /** Each line of text on page [page] and where it is. */
    fun textLines(input: ByteArray, page: Int): List<TextLine>

    /** Writes new [text] with its top-left corner at ([x], [y]); [color] is ARGB. Lines break at "\n". */
    fun addText(input: ByteArray, page: Int, x: Float, y: Float, text: String, size: Float, color: Int): ByteArray

    /** Draws a box: [fill] (ARGB) for a white-out or coloured block, [stroke] for an outline; null leaves either out. */
    fun addRect(input: ByteArray, page: Int, x: Float, y: Float, width: Float, height: Float, fill: Int?, stroke: Int?, strokeWidth: Float = 2f): ByteArray

    /** Adds freehand drawing (an ink annotation) made of [strokes] of (x, y) points. */
    fun addInk(input: ByteArray, page: Int, strokes: List<List<Pair<Float, Float>>>, color: Int, width: Float): ByteArray

    /** Highlights an area of a page in yellow. */
    fun highlightArea(input: ByteArray, page: Int, x: Float, y: Float, width: Float, height: Float): ByteArray

    /** Permanently removes everything in an area, leaving a black box. */
    fun redactArea(input: ByteArray, page: Int, x: Float, y: Float, width: Float, height: Float): ByteArray

    /** Removes the text in an area and writes [text] in its place, sized to fit the area's height. */
    fun replaceArea(input: ByteArray, page: Int, x: Float, y: Float, width: Float, height: Float, text: String, color: Int = 0xFF000000.toInt(), bold: Boolean = false): ByteArray
}

/** A line of text and its box, in points from the page's top-left corner; [bold] when most of it is bold. */
data class TextLine(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val bold: Boolean = false)

/** A changed PDF and how many places were changed. */
class Changed(val pdf: ByteArray, val count: Int)

data class PdfAnnotation(val page: Int, val type: String, val contents: String)

/** [changed] is the share of each page that differs (0 to 1; 1 for pages only one version has). */
class Comparison(val changed: List<Float>, val report: ByteArray)

/** Page size for Images to PDF. [width]/[height] in points; null means "the image's own size". */
enum class ImagePage(val label: String, val width: Float?, val height: Float?) {
    FitImage("Same as each image", null, null),
    A4("A4", 595f, 842f),
    Letter("US Letter", 612f, 792f),
}

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    Jpeg("JPG", "jpg", "image/jpeg"),
    Png("PNG", "png", "image/png"),
    Webp("WebP", "webp", "image/webp"),
}

/** How hard Compress works. [dpi] and [quality] apply to images; null means images are left as they are. */
enum class CompressLevel(val label: String, val detail: String, val dpi: Int?, val quality: Int) {
    Light("Light", "Removes unused data. Images untouched", null, 0),
    Recommended("Recommended", "Good quality, much smaller (images at 150 dpi)", 150, 70),
    Strong("Strong", "Smallest file, lower image quality (96 dpi)", 96, 50),
}

enum class NumberPosition(val label: String) {
    BottomCenter("Bottom centre"), BottomRight("Bottom right"), BottomLeft("Bottom left"),
    TopCenter("Top centre"), TopRight("Top right"), TopLeft("Top left"),
}

/** Space to trim from each edge of a page, in points (72 per inch). */
data class Margins(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f)

/** The descriptive fields shown in a PDF's document properties. */
data class PdfInfo(val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "")

/**
 * Reads page ranges the user typed, like "1-3, 5, 7-9" (1-based, as on llmlabs.in), into
 * 0-based ranges; each range becomes one Result of Split. Throws IllegalArgumentException
 * with a message to show the user.
 */
fun parsePageRanges(spec: String, pageCount: Int): List<IntRange> {
    val ranges = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
        val m = Regex("""^(\d+)(?:\s*-\s*(\d+))?$""").matchEntire(part)
            ?: throw IllegalArgumentException("Invalid range \"$part\"")
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
        if (start < 1 || end < start || end > pageCount) throw IllegalArgumentException("Range \"$part\" is outside 1-$pageCount")
        (start - 1)..(end - 1)
    }
    if (ranges.isEmpty()) throw IllegalArgumentException("Enter at least one page range")
    return ranges
}

/** Input number [index] (0-based) is password protected and must be unlocked first. */
class LockedPdfException(val index: Int) : Exception("Input ${index + 1} is password protected")
