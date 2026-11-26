package `in`.llmlabs.docsmanager

/** Tools that turn PDFs into new PDFs, entirely on the phone. Inputs and Results are whole files in memory. */
interface PdfOps {
    /**
     * Joins [inputs] into one PDF, keeping their order and the order of pages inside each.
     * Throws [LockedPdfException] naming the first password-protected input.
     */
    fun merge(inputs: List<ByteArray>): ByteArray
}

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
