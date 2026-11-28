package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.LockedPdfException
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.PdfOps
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.ResultFiles
import `in`.llmlabs.docsmanager.parsePageRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SplitMode { EveryPage, Ranges }

/** Split PDF: break one PDF into several, page by page or by the ranges the user types. */
@Composable
fun SplitScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit, ops: PdfOps = remember { MuPdfOps() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PickedPdf?>(null) }
    var mode by remember { mutableStateOf(SplitMode.Ranges) }
    var spec by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            error = null
            file = PickedPdf(uri, "…")
            scope.launch {
                val loaded = loadPicked(context, uri)
                file = loaded
                if (loaded.ready) spec = if (loaded.pages!! > 1) "1-${loaded.pages / 2}, ${loaded.pages / 2 + 1}-${loaded.pages}" else "1"
            }
        }
    }
    val pick = { picker.launch(arrayOf("application/pdf")) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })

    val f = file
    val pageCount = f?.pages ?: 0
    // The ranges to make, or the message explaining what's wrong with what was typed.
    val plan: Result<List<IntRange>> = when {
        f == null || !f.ready -> Result.failure(IllegalStateException(""))
        mode == SplitMode.EveryPage -> Result.success((0 until pageCount).map { it..it })
        else -> runCatching { parsePageRanges(spec, pageCount) }
    }
    val ranges = plan.getOrNull()

    fun split() = saveGate {
        val src = file ?: return@saveGate
        val parts = ranges ?: return@saveGate
        busy = true
        error = null
        scope.launch {
            try {
                progress = "Splitting…"
                val outputs = withContext(Dispatchers.Default) { ops.split(src.bytes!!, parts) }
                val base = src.name.removeSuffix(".pdf").removeSuffix(".PDF")
                val saved = mutableListOf<SavedResult>()
                outputs.forEachIndexed { i, bytes ->
                    progress = "Saving ${i + 1} of ${outputs.size}…"
                    val r = parts[i]
                    val label = if (r.first == r.last) "page ${r.first + 1}" else "pages ${r.first + 1}-${r.last + 1}"
                    val fileName = "$base ($label).pdf"
                    val uri = withContext(Dispatchers.IO) { ResultFiles.savePdf(context, fileName, bytes) }
                    val count = r.last - r.first + 1
                    // Long names get cut off, so say which pages each file holds on its second line.
                    val detail = if (count == 1) "Page ${r.first + 1}" else "Pages ${r.first + 1}-${r.last + 1} · ${pagesText(count)}"
                    saved += SavedResult(uri, DocumentFiles.displayName(context, uri) ?: fileName, count, detail)
                }
                results = saved
            } catch (e: LockedPdfException) {
                error = "This PDF is password protected. Unlock it first."
            } catch (e: OutOfMemoryError) {
                error = "This file is too large to split on this phone."
            } catch (e: Exception) {
                error = "Couldn't split this file. It may be damaged."
            } finally {
                busy = false
            }
        }
    }

    error?.let { ProblemDialog(it) { error = null } }
    ToolScaffold(
        title = "Split PDF",
        onBack = onBack,
        bottomBar = {
            if (results == null && f != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        Button(onClick = ::split, enabled = ranges != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(progress)
                            } else {
                                Text(if (ranges == null) "Split" else "Split into ${ranges.size} ${if (ranges.size == 1) "file" else "files"}")
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
                item { ResultsPanel(done, onOpenResult, onAgain = { file = null; spec = ""; results = null }, againLabel = "Split another") }
                return@LazyColumn
            }
            if (f == null) {
                item { PickPrompt("Choose a PDF to break into smaller PDFs, page by page or by page ranges.", "Choose a PDF", pick) }
                return@LazyColumn
            }
            item {
                PickedRow(f) { TextButton(onClick = pick, enabled = !busy) { Text("Change") } }
            }
            if (f.ready) {
                item { Text("How to split", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ModeRow("By page ranges", "Choose which pages go into each new PDF", mode == SplitMode.Ranges, !busy) { mode = SplitMode.Ranges }
                            ModeRow("Every page", "One PDF for each of the ${pagesText(pageCount)}", mode == SplitMode.EveryPage, !busy) { mode = SplitMode.EveryPage }
                        }
                    }
                }
                if (mode == SplitMode.Ranges) {
                    item {
                        val message = plan.exceptionOrNull()?.message
                        OutlinedTextField(
                            value = spec,
                            onValueChange = { spec = it },
                            label = { Text("Page ranges") },
                            placeholder = { Text("1-3, 5, 7-9") },
                            singleLine = true,
                            enabled = !busy,
                            isError = message != null,
                            supportingText = {
                                Text(message ?: "Each range becomes one PDF. Pages 1 to $pageCount.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
