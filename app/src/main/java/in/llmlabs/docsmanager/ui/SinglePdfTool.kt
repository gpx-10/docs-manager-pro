package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.LockedPdfException
import `in`.llmlabs.docsmanager.PasswordRequiredException
import `in`.llmlabs.docsmanager.data.DocumentFiles
import `in`.llmlabs.docsmanager.data.ResultFiles
import `in`.llmlabs.docsmanager.parsePageRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A new PDF a tool made, before it is saved. */
class ToolOutput(val name: String, val bytes: ByteArray, val pages: Int, val detail: String? = null)

/**
 * The screen every one-PDF tool shares: choose a PDF, set the tool's options, run it, and
 * see the saved Results. Each tool supplies only its options and what running it does.
 *
 * [action] gives the button label for the current options, or null while they aren't valid.
 * [run] does the work off the main thread; throw IllegalArgumentException with a message to
 * show the user. [acceptLocked] lets password-protected PDFs be chosen (for Unlock).
 */
@Composable
fun SinglePdfTool(
    title: String,
    intro: String,
    onBack: () -> Unit,
    onOpenResult: (Uri) -> Unit,
    acceptLocked: Boolean = false,
    againLabel: String = "Use another PDF",
    onPicked: (PickedPdf) -> Unit = {},
    action: (PickedPdf) -> String?,
    run: suspend (PickedPdf) -> List<ToolOutput>,
    options: LazyListScope.(file: PickedPdf, enabled: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PickedPdf?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            error = null
            file = PickedPdf(uri, "…")
            scope.launch {
                val loaded = loadPicked(context, uri)
                file = loaded
                if (usable(loaded, acceptLocked)) onPicked(loaded)
            }
        }
    }
    val pick = { picker.launch(arrayOf("application/pdf")) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })

    val f = file
    val ready = f != null && usable(f, acceptLocked)
    val label = if (ready) action(f!!) else null

    fun start() = saveGate {
        val src = file ?: return@saveGate
        busy = true
        error = null
        scope.launch {
            try {
                val outputs = withContext(Dispatchers.Default) { run(src) }
                results = withContext(Dispatchers.IO) {
                    outputs.map { o ->
                        val uri = ResultFiles.savePdf(context, o.name, o.bytes)
                        SavedResult(uri, DocumentFiles.displayName(context, uri) ?: o.name, o.pages, o.detail)
                    }
                }
            } catch (e: LockedPdfException) {
                error = "This PDF is password protected. Unlock it first."
            } catch (e: PasswordRequiredException) {
                error = if (e.wrongPassword) "That password is wrong. Check it and try again." else "This PDF needs its password."
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Check the options and try again."
            } catch (e: OutOfMemoryError) {
                error = "This file is too large for this phone."
            } catch (e: Exception) {
                error = "Couldn't finish. The file may be damaged."
            } finally {
                busy = false
            }
        }
    }

    ToolScaffold(
        title = title,
        onBack = onBack,
        bottomBar = {
            if (results == null && f != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        Button(onClick = ::start, enabled = label != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Working…")
                            } else {
                                Text(label ?: title)
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
                item { ResultsPanel(done, onOpenResult, onAgain = { file = null; results = null; error = null }, againLabel = againLabel) }
                return@LazyColumn
            }
            if (f == null) {
                item { PickPrompt(intro, "Choose a PDF", pick) }
                return@LazyColumn
            }
            item {
                PickedRow(if (acceptLocked && f.locked) f.copy(detail = "Password protected", locked = false) else f) {
                    TextButton(onClick = pick, enabled = !busy) { Text("Change") }
                }
            }
            if (ready) options(f!!, !busy)
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun usable(f: PickedPdf, acceptLocked: Boolean) =
    f.bytes != null && f.error == null && (f.ready || (acceptLocked && f.locked))

/** The file name without ".pdf", for naming Results. */
fun PickedPdf.baseName() = name.removeSuffix(".pdf").removeSuffix(".PDF")

/** A section heading inside a tool's options. */
fun LazyListScope.heading(text: String) = item {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** A card of radio choices. */
@Composable
fun <T> Choices(options: List<T>, selected: T, enabled: Boolean, title: (T) -> String, subtitle: (T) -> String? = { null }, onSelect: (T) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            options.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = o == selected, enabled = enabled, role = Role.RadioButton) { onSelect(o) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = o == selected, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title(o))
                        subtitle(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** Which pages a tool applies to: all of them, or the ones typed as "1-3, 5". */
class PageChoice {
    var all by mutableStateOf(true)
    var spec by mutableStateOf("")

    /** The chosen 0-based pages, or the message explaining what's wrong with what was typed. */
    fun pages(pageCount: Int): Result<Set<Int>> =
        if (all) Result.success((0 until pageCount).toSet())
        else runCatching { parsePageRanges(spec, pageCount).flatMap { it.toList() }.toSet() }
}

/** "All pages" or "Some pages" with a field for the page numbers. */
fun LazyListScope.pageChoiceItems(choice: PageChoice, pageCount: Int, enabled: Boolean, allLabel: String = "All pages", someLabel: String = "Only some pages") {
    item {
        Choices(listOf(true, false), choice.all, enabled, title = { if (it) allLabel else someLabel }) { choice.all = it }
    }
    if (!choice.all) item { PageSpecField(choice.spec, pageCount, enabled, choice.pages(pageCount).exceptionOrNull()?.message) { choice.spec = it } }
}

/** A field for page numbers like "1-3, 5", with the problem shown underneath as the user types. */
@Composable
fun PageSpecField(value: String, pageCount: Int, enabled: Boolean, problem: String?, label: String = "Page numbers", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("1-3, 5, 8") },
        singleLine = true,
        enabled = enabled,
        isError = value.isNotEmpty() && problem != null,
        supportingText = { Text(if (value.isNotEmpty() && problem != null) problem else "Pages 1 to $pageCount, for example 1-3, 5") },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "2.4 MB" style sizes. */
fun sizeText(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
