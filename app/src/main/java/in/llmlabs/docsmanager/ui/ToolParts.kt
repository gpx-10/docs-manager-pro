package `in`.llmlabs.docsmanager.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.MainActivity
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.PasswordRequiredException
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.ResultFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A PDF the user picked for a tool. [pages] is null while loading; [locked] files can't be used until unlocked. */
data class PickedPdf(
    val uri: Uri,
    val name: String,
    val bytes: ByteArray? = null,
    val pages: Int? = null,
    val locked: Boolean = false,
    val error: String? = null,
    /** Shown instead of the page count, for example "Pages 1-3 · 3 pages". */
    val detail: String? = null,
) {
    val ready get() = bytes != null && pages != null && !locked && error == null
}

/** Reads a picked PDF and counts its pages, off the main thread. */
suspend fun loadPicked(context: Context, uri: Uri): PickedPdf = withContext(Dispatchers.IO) {
    val name = DocumentFiles.displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
    val bytes = try {
        DocumentFiles.readBytes(context, uri)
    } catch (e: Throwable) {
        return@withContext PickedPdf(uri, name, error = DocumentFiles.friendlyError(e))
    }
    try {
        MuPdfEngine().open(bytes, name).use { PickedPdf(uri, name, bytes, it.pageCount) }
    } catch (e: PasswordRequiredException) {
        PickedPdf(uri, name, bytes, pages = 0, locked = true)
    } catch (e: Exception) {
        PickedPdf(uri, name, error = "This file is damaged or not a PDF.")
    }
}

/** A Result the tool saved, shown with Open and Share. */
data class SavedResult(val uri: Uri, val name: String, val pages: Int, val detail: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, bottomBar: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** The big "pick files" panel shown before anything is chosen. */
@Composable
fun PickPrompt(text: String, button: String, onPick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPick) { Text(button) }
        }
    }
}

/** One picked PDF: name, page count or a problem, and optional trailing controls. */
@Composable
fun PickedRow(file: PickedPdf, trailing: @Composable RowScope.() -> Unit = {}) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FileBadge(file.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                when {
                    file.error != null -> Text(file.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    file.locked -> Text("Password protected. Remove it, or unlock it first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    file.pages == null -> Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    else -> Text(file.detail ?: pagesText(file.pages), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            trailing()
        }
    }
}

fun pagesText(n: Int) = if (n == 1) "1 page" else "$n pages"

/** Shows what was saved, with Open and Share for each, and a way to start again. */
@Composable
fun ResultsPanel(results: List<SavedResult>, onOpen: (Uri) -> Unit, onAgain: () -> Unit, againLabel: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (results.size == 1) "Saved" else "Saved ${results.size} files", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("In Download/${ResultFiles.FOLDER}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        results.forEach { r ->
            PickedRow(PickedPdf(r.uri, r.name, pages = r.pages, detail = r.detail)) {
                IconButton(onClick = { shareFiles(context, listOf(r.uri)) }) { Icon(Icons.Outlined.Share, "Share ${r.name}") }
                TextButton(onClick = { onOpen(r.uri) }) { Text("Open") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (results.size > 1) OutlinedButton(onClick = { shareFiles(context, results.map { it.uri }) }) { Text("Share all") }
            Button(onClick = onAgain) { Text(againLabel) }
        }
    }
}

/** Shares one or more saved files through Android's share sheet. */
fun shareFiles(context: Context, uris: List<Uri>) {
    val links = ArrayList(uris.map { DocumentFiles.shareableUri(context, it) })
    val send = if (links.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, links[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, links)
    }.setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, "Share").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java))),
    )
}

/**
 * Runs [save] once saving is allowed: straight away on Android 10+, after asking for the
 * storage permission on Android 8 and 9. Calls [onDenied] if the user says no.
 */
@Composable
fun rememberSaveGate(onDenied: () -> Unit): (save: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted) p?.invoke() else onDenied()
    }
    return { save ->
        if (!ResultFiles.needsWritePermission ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) save()
        else { pending = save; ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
}
