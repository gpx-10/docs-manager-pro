package `in`.llmlabs.docsmanager.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A page drawn for placing things on it. [onTap] gets the tap in page points from the top-left. */
@Composable
private fun PagePreview(bytes: ByteArray, page: Int, marker: (androidx.compose.ui.graphics.drawscope.DrawScope.(scale: Float) -> Unit)?, onTap: (x: Float, y: Float) -> Unit) {
    var image by remember(bytes, page) { mutableStateOf<ImageBitmap?>(null) }
    var pageW by remember(bytes, page) { mutableStateOf(1f) }
    var pageH by remember(bytes, page) { mutableStateOf(1.4f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(bytes, page) {
        withContext(Dispatchers.IO) {
            runCatching {
                MuPdfEngine().open(bytes, "p.pdf").use { d ->
                    val s = d.pageSize(page); pageW = s.width; pageH = s.height
                    image = d.renderPage(page, 900).asImageBitmap()
                }
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(pageW / pageH).clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).background(Color.White),
    ) {
        image?.let { Image(it, "Page ${page + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(bytes, page) {
            detectTapGestures { p -> if (size.width > 0) onTap(p.x / size.width * pageW, p.y / size.height * pageH) }
        }) { marker?.invoke(this, if (pageW > 0) this.size.width / pageW else 1f) }
    }
}

/** "‹ Page 2 of 5 ›" */
@Composable
private fun PageStepper(page: Int, count: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(page - 1) }, enabled = enabled && page > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous page") }
        Text("Page ${page + 1} of $count", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onChange(page + 1) }, enabled = enabled && page < count - 1) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next page") }
    }
}

/* ---------- Sign ---------- */

/** A drawn signature: strokes in a 600 x 240 box. */
internal class Signature {
    val strokes = mutableStateListOf<List<Offset>>()
    var current by mutableStateOf<List<Offset>>(emptyList())
    val isEmpty get() = strokes.isEmpty() && current.isEmpty()

    /** The signature as a PNG with a see-through background, cropped to the ink. */
    fun toPng(typed: String?, ink: Int): ByteArray? {
        val bmp = Bitmap.createBitmap(1200, 480, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        if (typed != null) {
            if (typed.isBlank()) return null
            paint.typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
            paint.textSize = 220f
            while (paint.measureText(typed) > 1150 && paint.textSize > 40) paint.textSize -= 10
            c.drawText(typed, 25f, 330f, paint)
        } else {
            if (isEmpty) return null
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 12f
            paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.strokeJoin = android.graphics.Paint.Join.ROUND
            for (s in strokes) {
                if (s.size == 1) { c.drawPoint(s[0].x * 2, s[0].y * 2, paint); continue }
                val p = android.graphics.Path().apply { moveTo(s[0].x * 2, s[0].y * 2); s.drop(1).forEach { lineTo(it.x * 2, it.y * 2) } }
                c.drawPath(p, paint)
            }
        }
        // Crop to the ink so the box on the page fits the signature.
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        var l = bmp.width; var t = bmp.height; var r = -1; var b = -1
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (px[y * bmp.width + x] ushr 24 > 20) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        if (r < 0) return null
        val cropped = Bitmap.createBitmap(bmp, maxOf(0, l - 8), maxOf(0, t - 8), minOf(bmp.width, r + 9) - maxOf(0, l - 8), minOf(bmp.height, b + 9) - maxOf(0, t - 8))
        return java.io.ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

@Composable
internal fun SignaturePad(sig: Signature, enabled: Boolean, ink: Color) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxWidth().aspectRatio(2.5f).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))) {
        Text("Sign here", Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color(0xFFB0B4C0), style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            fun norm(o: Offset) = Offset(o.x / size.width * 600f, o.y / size.height * 240f)
            detectDragGestures(
                onDragStart = { sig.current = listOf(norm(it)) },
                onDrag = { ch, _ -> ch.consume(); sig.current = sig.current + norm(ch.position) },
                onDragEnd = { sig.strokes += sig.current; sig.current = emptyList() },
            )
        }) {
            val k = this.size.width / 600f
            for (s in sig.strokes + listOf(sig.current)) {
                if (s.isEmpty()) continue
                val p = Path().apply { moveTo(s[0].x * k, s[0].y * k); s.drop(1).forEach { lineTo(it.x * k, it.y * k) } }
                drawPath(p, ink, style = Stroke(width = 6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun SignScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val sig = remember { Signature() }
    var typed by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf(false) }
    var uploaded by remember { mutableStateOf<ByteArray?>(null) }
    var uploadedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploadProblem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            uploadProblem = null
            try {
                val png = withContext(Dispatchers.Default) { `in`.llmlabs.docsmanager.AndroidImageOps().cutOutSignature(DocumentFiles.readBytes(context, uri)) }
                uploaded = png
                uploadedPreview = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            } catch (e: IllegalArgumentException) {
                uploadProblem = e.message
            } catch (e: Exception) {
                uploadProblem = "That picture can't be read."
            }
        }
    }
    var name by remember { mutableStateOf("") }
    var blue by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var widthPt by remember { mutableStateOf(150f) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    val ink = if (blue) Color(0xFF1B3A8C) else Color.Black
    fun png() = if (upload) uploaded else sig.toPng(if (typed) name else null, android.graphics.Color.rgb((ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt()))
    val ready = (when { upload -> uploaded != null; typed -> name.isNotBlank(); else -> !sig.isEmpty }) && pos != null

    SinglePdfTool(
        title = "Sign PDF", intro = "Choose a PDF, draw or type your signature, then tap where it goes.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Sign another",
        onPicked = { f -> bytes = f.bytes; page = 0; pos = null },
        action = { if (ready) "Sign page ${page + 1}" else null },
        run = { f ->
            val image = png() ?: throw IllegalArgumentException("Draw, type or upload your signature first")
            val bmp = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size)
            val h = widthPt * bmp.height / bmp.width
            val (x, y) = pos!!
            listOf(ToolOutput("${f.baseName()} signed", MuPdfOps().addImage(f.bytes!!, page, image, x - widthPt / 2, y - h / 2, widthPt, h), f.pages!!))
        },
    ) { f, enabled ->
        heading("Your signature")
        item {
            Choices(listOf(0, 1, 2), if (upload) 2 else if (typed) 1 else 0, enabled, title = { when (it) { 0 -> "Draw it"; 1 -> "Type it"; else -> "Upload a picture of it" } }) {
                upload = it == 2; typed = it == 1
                if (it == 2 && uploaded == null) imagePicker.launch(arrayOf("image/*"))
            }
        }
        if (upload) {
            item {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(2.5f).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    uploadedPreview?.let { Image(it, "Your signature", Modifier.fillMaxSize().padding(8.dp)) }
                        ?: Text("A photo or scan of your signature on light paper", color = Color(0xFF8A8F9C), style = MaterialTheme.typography.bodySmall)
                }
            }
            uploadProblem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            item { TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = enabled) { Text(if (uploaded == null) "Choose a picture" else "Choose another picture") } }
        } else if (typed) {
            item { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
        } else {
            item { SignaturePad(sig, enabled, ink) }
            item { TextButton(onClick = { sig.strokes.clear(); sig.current = emptyList() }, enabled = enabled && !sig.isEmpty) { Text("Clear and draw again") } }
        }
        if (!upload) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = blue, onClick = { blue = true }, label = { Text("Blue ink") }, enabled = enabled)
                FilterChip(selected = !blue, onClick = { blue = false }, label = { Text("Black ink") }, enabled = enabled)
            }
        }
        heading("Where it goes")
        if (f.pages!! > 1) item { PageStepper(page, f.pages, enabled) { page = it; pos = null } }
        item { Text(if (pos == null) "Tap the page where the signature should go." else "Tap again to move it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            PagePreview(bytes ?: f.bytes!!, page, marker = pos?.let { (x, y) ->
                { k: Float ->
                    val w = widthPt * k; val h = w * 0.4f
                    drawRect(Color(0x334F6DF5), Offset(x * k - w / 2, y * k - h / 2), Size(w, h))
                    drawRect(Color(0xFF4F6DF5), Offset(x * k - w / 2, y * k - h / 2), Size(w, h), style = Stroke(2.dp.toPx()))
                }
            }) { x, y -> pos = x to y }
        }
        heading("Size")
        item { Slider(value = widthPt, onValueChange = { widthPt = it }, valueRange = 60f..300f, enabled = enabled) }
    }
}

/* ---------- Redact ---------- */

@Composable
fun RedactScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var terms by remember { mutableStateOf("") }
    val list = terms.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    SinglePdfTool(
        title = "Redact PDF", intro = "Choose a PDF, then type the words, names or numbers to remove for good.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Redact another",
        action = { if (list.isNotEmpty()) "Redact" else null },
        run = { f ->
            val r = MuPdfOps().redact(f.bytes!!, list)
            listOf(ToolOutput("${f.baseName()} redacted", r.pdf, f.pages!!, "${r.count} ${if (r.count == 1) "place" else "places"} blacked out"))
        },
    ) { _, enabled ->
        heading("What to remove")
        item {
            OutlinedTextField(
                value = terms, onValueChange = { terms = it }, label = { Text("Words to remove") }, placeholder = { Text("Jane Smith, 4111 1111 1111 1111") },
                enabled = enabled, minLines = 2, supportingText = { Text("Separate with commas. Matching ignores capitals.") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                "The text is deleted from the file, not just covered, so it can't be copied or uncovered. A black box marks each place.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------- Edit text ---------- */

@Composable
fun EditTextScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    fun load(b: ByteArray, p: Int) {
        chosen = null
        scope.launch {
            lines = withContext(Dispatchers.IO) {
                runCatching { MuPdfEngine().open(b, "p.pdf").use { it.pageText(p) } }.getOrDefault("")
                    .lines().map { it.trim() }.filter { it.length > 1 }.distinct()
            }
        }
    }
    SinglePdfTool(
        title = "Edit text", intro = "Choose a PDF, pick a line of text on a page and change it.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Edit another",
        onPicked = { f -> bytes = f.bytes; page = 0; load(f.bytes!!, 0) },
        action = { if (chosen != null && replacement != chosen) "Save the change" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} edited", MuPdfOps().replaceText(f.bytes!!, page, chosen!!, replacement), f.pages!!, "Page ${page + 1}: \"${replacement.take(40)}\"")) },
    ) { f, enabled ->
        if (f.pages!! > 1) item { PageStepper(page, f.pages, enabled) { page = it; load(bytes ?: f.bytes!!, it) } }
        val c = chosen
        if (c != null) {
            heading("Change to")
            item { Text("\"$c\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedTextField(value = replacement, onValueChange = { replacement = it }, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
            item { TextButton(onClick = { chosen = null }, enabled = enabled) { Text("Pick another line") } }
            item {
                Text(
                    "The new text uses a standard font (Helvetica), so it may look slightly different from the rest of the page.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (lines.isEmpty()) {
            item { Text("No text was found on this page. If it's a scan, run OCR PDF first.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            heading("Tap the line to change")
            itemsIndexed(lines) { _, line ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { chosen = line; replacement = line }) {
                    Text(line, Modifier.padding(12.dp), maxLines = 2)
                }
            }
        }
    }
}

/* ---------- Comment & highlight ---------- */

@Composable
fun AnnotateScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var note by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val list = terms.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    SinglePdfTool(
        title = "Comment & highlight", intro = "Choose a PDF to highlight words in yellow or add a comment note to a page.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Mark up another",
        onPicked = { page = 0; pos = null },
        action = { if (note) (if (comment.isNotBlank() && pos != null) "Add comment" else null) else (if (list.isNotEmpty()) "Highlight" else null) },
        run = { f ->
            if (note) {
                val (x, y) = pos!!
                listOf(ToolOutput("${f.baseName()} commented", MuPdfOps().addNote(f.bytes!!, page, x, y, comment), f.pages!!, "Comment on page ${page + 1}"))
            } else {
                val r = MuPdfOps().highlight(f.bytes!!, list)
                listOf(ToolOutput("${f.baseName()} highlighted", r.pdf, f.pages!!, "${r.count} ${if (r.count == 1) "highlight" else "highlights"}"))
            }
        },
    ) { f, enabled ->
        item { Choices(listOf(false, true), note, enabled, title = { if (it) "Add a comment note" else "Highlight words" }) { note = it } }
        if (!note) {
            item {
                OutlinedTextField(
                    value = terms, onValueChange = { terms = it }, label = { Text("Words to highlight") }, enabled = enabled,
                    supportingText = { Text("Separate with commas. Every match is highlighted.") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item { OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("Comment") }, enabled = enabled, minLines = 2, modifier = Modifier.fillMaxWidth()) }
            if (f.pages!! > 1) item { PageStepper(page, f.pages, enabled) { page = it; pos = null } }
            item { Text(if (pos == null) "Tap the page where the note should go." else "Tap again to move it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                PagePreview(f.bytes!!, page, marker = pos?.let { (x, y) ->
                    { k: Float -> drawRect(Color(0xFFFFC400), Offset(x * k, y * k), Size(18.dp.toPx(), 18.dp.toPx())) }
                }) { x, y -> pos = x to y }
            }
        }
    }
}

/* ---------- OCR ---------- */

@Composable
fun OcrScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val context = LocalContext.current
    SinglePdfTool(
        title = "OCR PDF", intro = "Choose a scanned PDF to make its English text searchable and copyable. The pages keep their exact look.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "OCR another",
        action = { "Make searchable" },
        run = { f -> listOf(ToolOutput("${f.baseName()} searchable", MuPdfOps(context.applicationContext).ocr(f.bytes!!), f.pages!!, "Text can now be searched and copied")) },
    ) { f, _ ->
        item {
            Text(
                "Reads printed English text. This takes a few seconds a page${if (f.pages!! > 5) ", so ${f.pages} pages may take a minute or two" else ""}. Everything stays on your phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------- Compare ---------- */

@Composable
fun CompareScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf<PickedPdf?>(null) }
    var b by remember { mutableStateOf<PickedPdf?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<List<Float>?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }
    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { summary = null; results = null; a = PickedPdf(u, "…"); scope.launch { a = loadPicked(context, u) } } }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { summary = null; results = null; b = PickedPdf(u, "…"); scope.launch { b = loadPicked(context, u) } } }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })
    val ready = a?.ready == true && b?.ready == true && !busy

    fun run() = saveGate {
        busy = true; error = null
        scope.launch {
            try {
                val c = withContext(Dispatchers.Default) { MuPdfOps().compare(a!!.bytes!!, b!!.bytes!!) }
                summary = c.changed
                val changedPages = c.changed.count { it > 0f }
                results = withContext(Dispatchers.IO) {
                    saveOutputs(context, listOf(ToolOutput("${a!!.baseName()} vs ${b!!.baseName()}", c.report, c.changed.size, if (changedPages == 0) "No differences" else "${pagesText(changedPages)} changed, in red")))
                }
            } catch (e: `in`.llmlabs.docsmanager.LockedPdfException) {
                error = "One of these PDFs is password protected. Unlock it first."
            } catch (e: OutOfMemoryError) {
                error = "These files are too large to compare on this phone."
            } catch (e: Exception) {
                error = "Couldn't compare these files. One may be damaged."
            } finally {
                busy = false
            }
        }
    }

    error?.let { ProblemDialog(it) { error = null } }
    ToolScaffold(
        title = "Compare PDF", onBack = onBack,
        bottomBar = {
            if (results == null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = ::run, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Comparing…") } else Text("Compare")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val done = results
            if (done != null) {
                val s = summary.orEmpty()
                item {
                    Text(
                        if (s.none { it > 0f }) "The two versions look the same." else "Changed: " + s.withIndex().filter { it.value > 0f }.joinToString(", ") { "page ${it.index + 1}" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item { ResultsPanel(done, onOpenResult, onAgain = { a = null; b = null; results = null; summary = null }, againLabel = "Compare others") }
                return@LazyColumn
            }
            item { Text("Choose the two versions. Pages are compared by how they look; changes show in red.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("Original", fontWeight = FontWeight.SemiBold) }
            item { a?.let { PickedRow(it) { TextButton(onClick = { pickA.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text("Change") } } } ?: OutlinedButton(onClick = { pickA.launch(arrayOf("application/pdf")) }) { Text("Choose the original PDF") } }
            item { Text("Changed version", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            item { b?.let { PickedRow(it) { TextButton(onClick = { pickB.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text("Change") } } } ?: OutlinedButton(onClick = { pickB.launch(arrayOf("application/pdf")) }) { Text("Choose the changed PDF") } }
        }
    }
}
