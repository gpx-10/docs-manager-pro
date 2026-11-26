package `in`.llmlabs.docsmanager

/** Tools that turn PDFs into new PDFs, entirely on the phone. Inputs and Results are whole files in memory. */
interface PdfOps {
    /**
     * Joins [inputs] into one PDF, keeping their order and the order of pages inside each.
     * Throws [LockedPdfException] naming the first password-protected input.
     */
    fun merge(inputs: List<ByteArray>): ByteArray
}

/** Input number [index] (0-based) is password protected and must be unlocked first. */
class LockedPdfException(val index: Int) : Exception("Input ${index + 1} is password protected")
