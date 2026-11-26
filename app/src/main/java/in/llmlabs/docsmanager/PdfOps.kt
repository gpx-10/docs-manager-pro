package `in`.llmlabs.docsmanager

/** Tools that turn PDFs into new PDFs, entirely on the phone. Inputs and Results are whole files in memory. */
interface PdfOps {
    /** Joins [inputs] into one PDF, keeping their order and the order of pages inside each. */
    fun merge(inputs: List<ByteArray>): ByteArray
}
