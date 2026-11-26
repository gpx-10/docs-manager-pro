package `in`.llmlabs.docsmanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Reads Documents the user picked, shared or opened from another app, given their content link. */
object DocumentFiles {
    private const val COPIES = "received"

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("No content")

    /** Asks for lasting read access, so the file can be reopened from Recent. True when granted. */
    fun keepAccess(context: Context, uri: Uri): Boolean {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return hasLastingAccess(context, uri)
    }

    fun hasLastingAccess(context: Context, uri: Uri): Boolean =
        uri.authority == context.packageName + ".files" ||
            // Found through All files access: readable for as long as that stays on.
            (uri.scheme == "file" && AllFiles.isGranted(context)) ||
            // A Result this app saved to Download: always readable by the app that made it.
            ResultFiles.isOwnResult(context, uri) ||
            (uri.scheme == "file" && uri.path?.contains("/Download/${ResultFiles.FOLDER}/") == true) ||
            context.contentResolver.persistedUriPermissions.any {
                // An exact grant, or a file inside a folder the user granted in the Files tab.
                it.isReadPermission && (it.uri == uri || uri.toString().startsWith(it.uri.toString() + "/document/"))
            }

    /**
     * Keeps a private copy of a Document the app only had brief access to (Open with, Share),
     * and returns a link to the copy. Copies of the same content share one file.
     */
    fun keepCopy(context: Context, bytes: ByteArray, name: String): Uri {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        // One folder per copy, so the copy keeps the file's own name.
        val dir = File(File(context.filesDir, COPIES), hash).apply { mkdirs() }
        val safeName = name.replace(Regex("[/\\\u0000]"), "_").take(120).ifBlank { "document" }
        val file = File(dir, safeName)
        if (!file.exists()) file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** Deletes private copies that no Recent entry points to any more. */
    fun deleteUnusedCopies(context: Context, keptUris: Set<String>) {
        File(context.filesDir, COPIES).listFiles()?.forEach { entry ->
            val files = if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry)
            val kept = files.any { FileProvider.getUriForFile(context, context.packageName + ".files", it).toString() in keptUris }
            if (!kept) entry.deleteRecursively()
        }
    }

    /**
     * A link another app can read: a file found through All files access is wrapped in this
     * app's FileProvider (Android does not allow handing out raw file paths).
     */
    fun shareableUri(context: Context, uri: Uri): Uri =
        if (uri.scheme == "file") FileProvider.getUriForFile(context, context.packageName + ".files", File(uri.path!!)) else uri

    fun mimeType(context: Context, uri: Uri, name: String): String =
        context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** A sentence a person can act on, instead of a system error. */
    fun friendlyError(e: Throwable): String = when (e) {
        is SecurityException, is FileNotFoundException ->
            "This file is no longer available. It may have been moved, deleted, or the app that shared it no longer allows access."
        is OutOfMemoryError -> "This file is too large to open on this phone."
        else -> "Something went wrong while reading this file."
    }

    // ---- Folders the user lets the app watch (Files tab) ----

    data class DeviceFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

    val SUPPORTED = setOf("pdf", "epub", "xps", "oxps", "cbz", "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff")

    /** Lists supported Documents inside a folder the user granted, looking up to [maxDepth] folders deep. */
    fun listFolder(context: Context, tree: Uri, maxDepth: Int = 3, limit: Int = 500): List<DeviceFile> {
        val out = mutableListOf<DeviceFile>()
        fun walk(docId: String, depth: Int) {
            if (out.size >= limit) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < maxDepth && !name.startsWith(".")) walk(id, depth + 1)
                    } else if (name.substringAfterLast('.', "").lowercase() in SUPPORTED) {
                        out += DeviceFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
        return out.sortedByDescending { it.modified }
    }

    fun folderName(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifBlank { "Phone storage" }
}

/** Folders the user chose to see in the Files tab. Kept as links with lasting access. */
class Folders(private val context: Context) {
    private val prefs = context.getSharedPreferences("folders", Context.MODE_PRIVATE)

    fun list(): List<Uri> = prefs.getStringSet(KEY, emptySet())!!.map(Uri::parse)
        .filter { u -> context.contentResolver.persistedUriPermissions.any { it.uri == u } }

    fun add(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet())!! + tree.toString()).apply()
    }

    fun remove(tree: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet())!! - tree.toString()).apply()
    }

    private companion object { const val KEY = "trees" }
}
