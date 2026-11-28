package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.LockedPdfException
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.PdfOps
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.ResultFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Merge PDF: pick several PDFs, put them in order, and save them as one. */
@Composable
fun MergeScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit, ops: PdfOps = remember { MuPdfOps() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<PickedPdf>() }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        error = null
        uris.filter { u -> files.none { it.uri == u } }.forEach { uri ->
            files += PickedPdf(uri, "…")
            scope.launch {
                val loaded = loadPicked(context, uri)
                val i = files.indexOfFirst { it.uri == uri }
                if (i >= 0) files[i] = loaded
                if (name.isBlank() && files.firstOrNull()?.uri == uri) name = loaded.name.removeSuffix(".pdf").removeSuffix(".PDF") + " merged"
            }
        }
    }
    val pick = { picker.launch(arrayOf("application/pdf")) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })
    val canMerge = files.size >= 2 && files.all { it.ready } && !busy

    fun merge() = saveGate {
        busy = true
        error = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { ops.merge(files.map { it.bytes!! }) }
                val fileName = name.ifBlank { "Merged" }
                val uri = withContext(Dispatchers.IO) { ResultFiles.savePdf(context, fileName, bytes) }
                // Android may add " (1)" if the name is taken; show the name it actually used.
                val savedName = DocumentFiles.displayName(context, uri) ?: if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"
                results = listOf(SavedResult(uri, savedName, files.sumOf { it.pages ?: 0 }))
            } catch (e: LockedPdfException) {
                error = "\"${files[e.index].name}\" is password protected. Remove it, or unlock it first."
            } catch (e: OutOfMemoryError) {
                error = "These files are too large to merge on this phone. Try fewer at a time."
            } catch (e: Exception) {
                error = "Couldn't merge these files. One of them may be damaged."
            } finally {
                busy = false
            }
        }
    }

    error?.let { ProblemDialog(it) { error = null } }
    ToolScaffold(
        title = "Merge PDF",
        onBack = onBack,
        bottomBar = {
            if (results == null && files.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        Button(onClick = ::merge, enabled = canMerge, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Merging…")
                            } else {
                                Text(if (files.size < 2) "Add at least 2 PDFs" else "Merge ${files.size} PDFs")
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val done = results
            if (done != null) {
                item { ResultsPanel(done, onOpenResult, onAgain = { files.clear(); name = ""; results = null }, againLabel = "Merge more") }
                return@LazyColumn
            }
            if (files.isEmpty()) {
                item { PickPrompt("Choose two or more PDFs. You can put them in any order before merging.", "Choose PDFs", pick) }
                return@LazyColumn
            }
            item { Text("Files are joined from top to bottom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(files, key = { _, f -> f.uri.toString() }) { i, f ->
                PickedRow(f) {
                    IconButton(onClick = { files.add(i - 1, files.removeAt(i)) }, enabled = i > 0 && !busy) { Icon(Icons.Outlined.ArrowUpward, "Move ${f.name} up") }
                    IconButton(onClick = { files.add(i + 1, files.removeAt(i)) }, enabled = i < files.lastIndex && !busy) { Icon(Icons.Outlined.ArrowDownward, "Move ${f.name} down") }
                    IconButton(onClick = { files.removeAt(i) }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${f.name}") }
                }
            }
            item {
                OutlinedButton(onClick = pick, enabled = !busy) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add more PDFs")
                }
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name of the merged PDF") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
