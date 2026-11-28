package `in`.llmlabs.docsmanager.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves tool Results as new files in Download/Docs Manager Pro, where people expect to find them. */
object ResultFiles {
    const val FOLDER = "Docs Manager Pro"

    /** Android 8 and 9 need the storage permission to write to Download; newer versions do not. */
    val needsWritePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Saves [bytes] as a new PDF named [name] (".pdf" added if missing; never overwrites) and returns a link to it. */
    fun savePdf(context: Context, name: String, bytes: ByteArray): Uri = save(context, name, bytes, "pdf", "application/pdf")

    /** Saves [bytes] as a new file named [name] with [extension] added if missing; never overwrites. */
    fun save(context: Context, name: String, bytes: ByteArray, extension: String, mime: String): Uri {
        val fileName = safeName(name).let { if (it.endsWith(".$extension", ignoreCase = true)) it else "$it.$extension" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file in Download")
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            val file = uniqueFile(dir, fileName)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    }

    /** True for files this app saved itself, which it can always read back (no copy needed for Recent). */
    fun isOwnResult(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != MediaStore.AUTHORITY) return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { c ->
                c.moveToFirst() && c.getString(0) == context.packageName
            } ?: false
        }.getOrDefault(false)
    }

    private fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().take(120).ifBlank { "Document" }

    private fun uniqueFile(dir: File, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "")
        val base = fileName.removeSuffix(".$ext")
        var f = File(dir, fileName)
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
        return f
    }
}
