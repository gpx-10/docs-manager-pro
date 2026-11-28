package `in`.llmlabs.docsmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import `in`.llmlabs.docsmanager.data.AllFiles
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.Folders
import `in`.llmlabs.docsmanager.data.RecentFile
import `in`.llmlabs.docsmanager.data.RecentFiles
import `in`.llmlabs.docsmanager.ui.DocsTheme
import `in`.llmlabs.docsmanager.ui.HomeScreen
import `in`.llmlabs.docsmanager.ui.MergeScreen
import `in`.llmlabs.docsmanager.ui.SplitScreen
import `in`.llmlabs.docsmanager.ui.*
import `in`.llmlabs.docsmanager.ui.ToolScreen
import `in`.llmlabs.docsmanager.ui.ViewerScreen

/**
 * The Document on screen. [fromOtherApp] is true when another app sent it (Open with, Share),
 * so Back returns there. [nonce] makes sending the same file again reload it.
 */
data class Opened(val uri: Uri, val fromOtherApp: Boolean, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private var opened by mutableStateOf<Opened?>(null)
    /** The tool screen open under any Document, so Back from a Result returns to the tool. */
    private var tool by mutableStateOf<ToolScreen?>(null)
    private var recent by mutableStateOf(emptyList<RecentFile>())
    private var folders by mutableStateOf(emptyList<Uri>())
    private var allFilesGranted by mutableStateOf(false)
    private val askStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private lateinit var recentFiles: RecentFiles
    private lateinit var folderStore: Folders

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recentFiles = RecentFiles(this)
        folderStore = Folders(this)
        refresh()
        // After Android closed the app in the background, come back to the same Document.
        tool = savedInstanceState?.getString(KEY_TOOL)?.let { runCatching { ToolScreen.valueOf(it) }.getOrNull() }
        opened = savedInstanceState?.getString(KEY_URI)?.let { Opened(Uri.parse(it), savedInstanceState.getBoolean(KEY_EXTERNAL)) }
            ?: documentFrom(intent)

        setContent {
            DocsTheme {
                val o = opened
                val t = tool
                if (t != null) {
                    // The tool stays underneath while a Result is open, so Back returns to it with its results intact.
                    Box(Modifier.fillMaxSize()) {
                        val openResult: (Uri) -> Unit = { opened = Opened(it, fromOtherApp = false) }
                        val back = { tool = null; refresh() }
                        when (t) {
                            ToolScreen.Merge -> MergeScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Split -> SplitScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Organize -> OrganizeScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Rotate -> RotateScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Extract -> ExtractPagesScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Remove -> RemovePagesScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Compress -> CompressScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Protect -> ProtectScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Unlock -> UnlockScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Repair -> RepairScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Metadata -> MetadataScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Watermark -> WatermarkScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.PageNumbers -> PageNumbersScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.Crop -> CropScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.ImagesToPdf -> ImagesToPdfScreen(scan = false, onBack = back, onOpenResult = openResult)
                            ToolScreen.Scan -> ImagesToPdfScreen(scan = true, onBack = back, onOpenResult = openResult)
                            ToolScreen.PdfToImages -> PdfToImagesScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.CompressImage -> CompressImageScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.ResizeImage -> ResizeImageScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.CropImage -> CropImageScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.RotateImage -> RotateImageScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.WatermarkImage -> WatermarkImageScreen(onBack = back, onOpenResult = openResult)
                            ToolScreen.ConvertImage -> ConvertImageScreen(heicToJpg = false, onBack = back, onOpenResult = openResult)
                            ToolScreen.HeicToJpg -> ConvertImageScreen(heicToJpg = true, onBack = back, onOpenResult = openResult)
                        }
                        if (o != null) Viewer(o)
                    }
                } else if (o == null) {
                    HomeScreen(
                        recent = recent,
                        folders = folders,
                        onOpen = ::openPicked,
                        onRemoveRecent = { f -> val pos = recent.indexOf(f); recentFiles.remove(f.uri); refresh(); pos },
                        onUndoRemove = { f, pos -> recentFiles.restore(f, pos); refresh() },
                        onClearRecent = { recentFiles.clear(); refresh() },
                        onAddFolder = { folderStore.add(it); refresh() },
                        onRemoveFolder = { folderStore.remove(it); refresh() },
                        allFilesGranted = allFilesGranted,
                        onAllowAllFiles = ::askForAllFiles,
                        onManageAllFiles = {
                            startActivity(if (AllFiles.usesRuntimePermission) AllFiles.appSettingsIntent(this) else AllFiles.settingsIntent(this))
                        },
                        onOpenTool = { tool = it },
                    )
                } else {
                    Viewer(o)
                }
            }
        }
    }

    // The user may have turned All files access on or off in Settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askForAllFiles() {
        if (AllFiles.usesRuntimePermission) askStoragePermission.launch(AllFiles.RUNTIME_PERMISSION)
        else startActivity(AllFiles.settingsIntent(this))
    }

    @androidx.compose.runtime.Composable
    private fun Viewer(o: Opened) {
        val known = recentFiles.find(o.uri.toString())
        ViewerScreen(
            uri = o.uri,
            sessionKey = o.nonce,
            knownName = known?.name,
            startPage = known?.lastPage ?: 0,
            onBack = { if (o.fromOtherApp) finish() else { opened = null; refresh() } },
            onOpened = { name, bytes -> rememberOpened(o.uri, name, bytes) },
            onPageChanged = { key, page -> recentFiles.setLastPage(key, page) },
            onRemoveFromRecent = if (known != null) ({ recentFiles.remove(o.uri.toString()); opened = null; refresh() }) else null,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tool?.let { outState.putString(KEY_TOOL, it.name) }
        opened?.let {
            outState.putString(KEY_URI, it.uri.toString())
            outState.putBoolean(KEY_EXTERNAL, it.fromOtherApp)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentFrom(intent)?.let { opened = it }
    }

    private fun refresh() {
        recent = recentFiles.list()
        folders = folderStore.list()
        allFilesGranted = AllFiles.isGranted(this)
    }

    private fun openPicked(uri: Uri) {
        DocumentFiles.keepAccess(this, uri)
        opened = Opened(uri, fromOtherApp = false)
    }

    /** Adds the Document to Recent and returns the link Recent will reopen it with. */
    private fun rememberOpened(uri: Uri, name: String, bytes: ByteArray): String {
        val reopen = if (DocumentFiles.keepAccess(this, uri)) uri else DocumentFiles.keepCopy(this, bytes, name)
        recentFiles.add(reopen.toString(), name, bytes.size.toLong())
        refresh()
        return reopen.toString()
    }

    private fun documentFrom(intent: Intent?): Opened? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        return Opened(uri, fromOtherApp = true)
    }

    private companion object {
        const val KEY_URI = "opened_uri"
        const val KEY_EXTERNAL = "opened_external"
        const val KEY_TOOL = "tool"
    }
}
