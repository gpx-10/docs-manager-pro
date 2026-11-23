package `in`.llmlabs.docsmanager.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The optional "every document on this phone" mode. Android 11+ calls it All files access;
 * Android 8 to 10 use the storage permission. Folder-by-folder access never needs it.
 */
object AllFiles {
    /** Android 8 to 10 ask with a normal permission dialog instead of a settings screen. */
    val usesRuntimePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    const val RUNTIME_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(RUNTIME_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The settings screen where the user turns All files access on or off for this app (Android 11+). */
    fun settingsIntent(context: Context): Intent {
        val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        return if (forApp.resolveActivity(context.packageManager) != null) forApp
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** The app's own settings page, to turn the Android 8 to 10 permission off again. */
    fun appSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /**
     * Finds supported Documents on the phone's storage, newest first. Skips hidden folders and
     * Android/ (other apps' private data), looks up to [maxDepth] folders deep, stops at [limit].
     */
    fun scan(root: File = Environment.getExternalStorageDirectory(), maxDepth: Int = 8, limit: Int = 2000): List<DocumentFiles.DeviceFile> {
        val out = mutableListOf<DocumentFiles.DeviceFile>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (out.size >= limit) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < maxDepth && !(depth == 0 && f.name == "Android")) walk(f, depth + 1)
                } else if (f.extension.lowercase() in DocumentFiles.SUPPORTED && f.length() > 0) {
                    out += DocumentFiles.DeviceFile(Uri.fromFile(f), f.name, f.length(), f.lastModified())
                }
            }
        }
        runCatching { walk(root, 0) }
        return out.sortedByDescending { it.modified }
    }
}
