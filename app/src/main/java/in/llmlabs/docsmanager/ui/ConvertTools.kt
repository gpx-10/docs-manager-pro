package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.ImagePage
import `in`.llmlabs.docsmanager.MuPdfOfficeEngine
import `in`.llmlabs.docsmanager.OfficeEngine
import `in`.llmlabs.docsmanager.OfficeTypes
import `in`.llmlabs.docsmanager.PageSetup
import `in`.llmlabs.docsmanager.office.DocBlock
import `in`.llmlabs.docsmanager.office.Run
import `in`.llmlabs.docsmanager.office.Sheet
import `in`.llmlabs.docsmanager.office.SlideContent
import `in`.llmlabs.docsmanager.office.writeDocx
import `in`.llmlabs.docsmanager.office.writePptx
import `in`.llmlabs.docsmanager.office.writeXlsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val office: OfficeEngine = MuPdfOfficeEngine()

/** File types the "to PDF" tool accepts in the picker. */
private val TO_PDF_TYPES = arrayOf(OfficeTypes.DOCX, OfficeTypes.XLSX, OfficeTypes.PPTX, "text/html", "text/plain")

@Composable
fun OfficeToPdfScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var size by remember { mutableStateOf(ImagePage.A4) }
    var landscape by remember { mutableStateOf(false) }
    SinglePdfTool(
        title = "Office to PDF", intro = "Choose a Word (.docx), Excel (.xlsx), PowerPoint (.pptx), web page (.html) or text file to save it as a PDF.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        accept = TO_PDF_TYPES, chooseLabel = "Choose a file",
        onPicked = { f -> if (OfficeTypes.extension(f.name) in OfficeTypes.excel) landscape = true },
        action = { "Convert to PDF" },
        run = { f ->
            val pdf = office.toPdf(f.bytes!!, f.name, PageSetup(size, landscape))
            val pages = `in`.llmlabs.docsmanager.MuPdfEngine().open(pdf, "r.pdf").use { it.pageCount }
            listOf(ToolOutput(f.name.substringBeforeLast('.'), pdf, pages))
        },
    ) { f, enabled ->
        val ext = OfficeTypes.extension(f.name)
        if (ext !in OfficeTypes.powerPoint) {
            heading("Page")
            item { Choices(listOf(ImagePage.A4, ImagePage.Letter), size, enabled, title = { it.label }) { size = it } }
            item { Choices(listOf(false, true), landscape, enabled, title = { if (it) "Landscape (wide)" else "Portrait (tall)" }) { landscape = it } }
        }
        item {
            Text(
                when (ext) {
                    in OfficeTypes.word -> "Keeps headings, paragraphs, bold and italic text, lists, tables and pictures, with real text you can select. Exact fonts, headers and footers may differ."
                    in OfficeTypes.excel -> "Each sheet becomes a table. Cell values are kept; charts, colours and formatting are not."
                    in OfficeTypes.powerPoint -> "Each slide becomes a page with its text boxes, pictures and coloured shapes in place. Charts, SmartArt and animations are not kept."
                    else -> "The page is laid out with real, selectable text."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PdfToWordScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var exact by remember { mutableStateOf(false) }
    SinglePdfTool(
        title = "PDF to Word", intro = "Choose a PDF to turn it into a Word document you can edit.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        action = { "Convert to Word" },
        run = { f -> listOf(ToolOutput(f.baseName(), office.pdfToWord(f.bytes!!, exact), f.pages!!, if (exact) "Each page as a picture" else "Editable text", "docx", OfficeTypes.DOCX)) },
    ) { _, enabled ->
        heading("Conversion")
        item {
            Choices(listOf(false, true), exact, enabled, title = { if (it) "Exact look" else "Editable text" }, subtitle = {
                if (it) "Each page as a picture, exactly as it looks" else "Headings, bold and pictures you can change"
            }) { exact = it }
        }
        item { Text("Editable text works best for reports and letters. Columns or tables may need tidying in Word. Scans have no text: run OCR PDF on them first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PdfToExcelScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var oneSheet by remember { mutableStateOf(false) }
    SinglePdfTool(
        title = "PDF to Excel", intro = "Choose a PDF with tables to turn them into spreadsheet cells.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        action = { "Convert to Excel" },
        run = { f -> listOf(ToolOutput(f.baseName(), office.pdfToExcel(f.bytes!!, oneSheet), f.pages!!, if (oneSheet) "One sheet" else "One sheet per page", "xlsx", OfficeTypes.XLSX)) },
    ) { _, enabled ->
        heading("Sheets")
        item { Choices(listOf(false, true), oneSheet, enabled, title = { if (it) "Everything on one sheet" else "One sheet per page" }) { oneSheet = it } }
        item { Text("Text is placed in rows and columns by where it sits on the page, so tables come out as cells. Plain numbers become numbers you can add up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PdfToPowerPointScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    SinglePdfTool(
        title = "PDF to PowerPoint", intro = "Choose a PDF to make a slide from each page.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        action = { "Convert to PowerPoint" },
        run = { f -> listOf(ToolOutput(f.baseName(), office.pdfToPowerPoint(f.bytes!!), f.pages!!, "${f.pages} slides", "pptx", OfficeTypes.PPTX)) },
    ) { _, _ ->
        item { Text("Each page becomes one slide showing the page exactly, ready to present or to add notes and new slides around.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PdfToPdfAScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    SinglePdfTool(
        title = "PDF to PDF/A", intro = "Choose a PDF to convert it for long-term archiving.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        action = { "Convert to PDF/A" },
        run = { f -> listOf(ToolOutput("${f.baseName()} PDF-A", office.pdfToPdfA(f.bytes!!, f.baseName()), f.pages!!, "PDF/A-2b")) },
    ) { _, _ ->
        item { Text("Makes a PDF/A-2b file, the standard for archiving. Every page keeps its exact look, as a high-resolution picture, so text can't be selected; run OCR PDF on the result if you need that.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/* ---------- New document ---------- */

private enum class NewKind(val label: String, val ext: String, val mime: String) {
    Word("Word document", "docx", OfficeTypes.DOCX), Excel("Excel spreadsheet", "xlsx", OfficeTypes.XLSX), PowerPoint("PowerPoint slides", "pptx", OfficeTypes.PPTX)
}

private class SlideDraft(val id: Int) { var title by mutableStateOf(""); var bullets by mutableStateOf("") }

@Composable
fun NewDocumentScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(NewKind.Word) }
    var name by remember { mutableStateOf("") }
    var heading by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf("Item, Price\nTea, 3\nCake, 5") }
    val slides = remember { mutableStateListOf(SlideDraft(0)) }
    var nextSlide by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })

    val ready = name.isNotBlank() && when (kind) {
        NewKind.Word -> heading.isNotBlank() || body.isNotBlank()
        NewKind.Excel -> grid.isNotBlank()
        NewKind.PowerPoint -> slides.any { it.title.isNotBlank() || it.bullets.isNotBlank() }
    }

    fun save() = saveGate {
        busy = true; error = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    when (kind) {
                        NewKind.Word -> writeDocx(
                            listOfNotNull(heading.takeIf { it.isNotBlank() }?.let { DocBlock.Paragraph(listOf(Run(it.trim())), heading = 1) }) +
                                body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { DocBlock.Paragraph(listOf(Run(it.replace('\n', ' ')))) },
                            title = heading.ifBlank { name },
                        )
                        NewKind.Excel -> writeXlsx(listOf(Sheet("Sheet1", grid.lines().filter { it.isNotBlank() }.map { line ->
                            line.split(',', '\t').map { cell -> cell.trim().let { c -> c.toDoubleOrNull()?.takeIf { !c.startsWith("0") || c == "0" || c.startsWith("0.") } ?: c } }
                        })))
                        NewKind.PowerPoint -> writePptx(slides.filter { it.title.isNotBlank() || it.bullets.isNotBlank() }.map { s ->
                            SlideContent.Text(s.title.trim(), s.bullets.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() })
                        })
                    }
                }
                results = withContext(Dispatchers.IO) { saveOutputs(context, listOf(ToolOutput(name.trim(), bytes, 1, kind.label, kind.ext, kind.mime))) }
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't save the document."
            } finally {
                busy = false
            }
        }
    }

    error?.let { ProblemDialog(it) { error = null } }
    ToolScaffold(
        title = "New document", onBack = onBack,
        bottomBar = {
            if (results == null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                    Button(onClick = ::save, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save ${kind.label.lowercase()}") }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val done = results
            if (done != null) {
                item { ResultsPanel(done, onOpenResult, onAgain = { results = null; name = ""; heading = ""; body = "" }, againLabel = "Make another") }
                return@LazyColumn
            }
            item { Choices(NewKind.entries, kind, !busy, title = { it.label }, subtitle = { ".${it.ext}, opens in Microsoft Office, Google Docs and others" }) { kind = it } }
            item { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("File name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            when (kind) {
                NewKind.Word -> {
                    item { OutlinedTextField(value = heading, onValueChange = { heading = it }, label = { Text("Title") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Text") }, minLines = 8, enabled = !busy, supportingText = { Text("Leave an empty line between paragraphs.") }, modifier = Modifier.fillMaxWidth()) }
                }
                NewKind.Excel -> {
                    item { OutlinedTextField(value = grid, onValueChange = { grid = it }, label = { Text("Rows") }, minLines = 8, enabled = !busy, supportingText = { Text("One row per line. Separate cells with commas. Numbers stay numbers.") }, modifier = Modifier.fillMaxWidth()) }
                }
                NewKind.PowerPoint -> {
                    itemsIndexed(slides, key = { _, s -> s.id }) { i, s ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Slide ${i + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (slides.size > 1) IconButton(onClick = { slides.removeAt(i) }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove slide ${i + 1}") }
                                }
                                OutlinedTextField(value = s.title, onValueChange = { s.title = it }, label = { Text("Title") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = s.bullets, onValueChange = { s.bullets = it }, label = { Text("Bullet points, one per line") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = { slides += SlideDraft(nextSlide++) }, enabled = !busy) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add a slide")
                        }
                    }
                }
            }
        }
    }
}
