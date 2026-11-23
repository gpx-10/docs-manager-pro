package `in`.llmlabs.docsmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.RecentFiles
import `in`.llmlabs.docsmanager.ui.DocsTheme
import `in`.llmlabs.docsmanager.ui.HomeScreen
import `in`.llmlabs.docsmanager.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private var openUri by mutableStateOf<Uri?>(null)
    private lateinit var recentFiles: RecentFiles
    private var recent by mutableStateOf(emptyList<`in`.llmlabs.docsmanager.data.RecentFile>())

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recentFiles = RecentFiles(this)
        recent = recentFiles.list()
        if (savedInstanceState == null) openUri = documentFrom(intent)

        setContent {
            DocsTheme {
                val uri = openUri
                if (uri == null) {
                    HomeScreen(
                        recent = recent,
                        onOpen = ::open,
                        onRemoveRecent = { recentFiles.remove(it.uri); recent = recentFiles.list() },
                    )
                } else {
                    ViewerScreen(
                        uri = uri,
                        onBack = { openUri = null },
                        onOpened = { name -> recentFiles.add(uri.toString(), name); recent = recentFiles.list() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentFrom(intent)?.let { openUri = it }
    }

    private fun open(uri: Uri) {
        DocumentFiles.keepAccess(this, uri)
        openUri = uri
    }

    private fun documentFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
