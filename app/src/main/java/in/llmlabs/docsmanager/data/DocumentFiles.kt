package `in`.llmlabs.docsmanager.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Reads a Document the user picked or shared, given its content link. */
object DocumentFiles {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read this file")

    /** Keeps read access across restarts, so the file can be reopened from Recent. */
    fun keepAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
