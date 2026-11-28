package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.PasswordRequiredException
import `in`.llmlabs.docsmanager.PdfOps
import `in`.llmlabs.docsmanager.TextLine
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The editor's tools, shown down the left side. Tap tools act where you tap; drag tools on the area you drag. */
private enum class EditTool(val label: String, val icon: ImageVector, val drag: Boolean, val hint: String) {
    EditText("Edit text", Icons.Outlined.EditNote, false, "Tap a line of text to change it"),
    AddText("Add text", Icons.Outlined.TextFields, false, "Tap where the new text should start"),
    Highlight("Highlight", Icons.Outlined.BorderColor, true, "Drag over the text to highlight"),
    Draw("Draw", Icons.Outlined.Gesture, true, "Draw with your finger"),
    Sign("Sign", Icons.Outlined.Draw, false, "Tap where your signature should go"),
    Picture("Image", Icons.Outlined.Image, false, "Tap where the image should go"),
    Whiteout("White-out", Icons.Outlined.FormatColorReset, true, "Drag over what to cover with white"),
    Box("Box", Icons.Outlined.CropSquare, true, "Drag to draw a box"),
    Comment("Comment", Icons.Outlined.AddComment, false, "Tap where the comment note should go"),
    Redact("Redact", Icons.Outlined.HideSource, true, "Drag over what to remove for good"),
}

/** What the next tap or drag is waiting to place (text, a signature, an image). */
private sealed interface Pending {
    class Text(val page: Int, val x: Float, val y: Float) : Pending
    class EditLine(val page: Int, val line: TextLine) : Pending
    class Note(val page: Int, val x: Float, val y: Float) : Pending
    class Signature(val page: Int, val x: Float, val y: Float) : Pending
}

private val INKS = listOf(Color(0xFF111111), Color(0xFF1B3A8C), Color(0xFFE5484D), Color(0xFF2F855A))

/**
 * Edit PDF: open a PDF and change it on the page, like Sejda's editor. Every change applies to
 * the open document straight away and can be undone; Save writes a new file, leaving the original as it was.
 */
@Composable
fun EditorScreen(start: Uri?, onBack: () -> Unit, onOpenResult: (Uri) -> Unit, onMoreTools: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // MuPDF documents are used from one thread only.
    val worker = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    DisposableEffect(Unit) { onDispose { worker.close() } }
    val ops: PdfOps = remember { MuPdfOps() }

    var name by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var version by remember { mutableStateOf(0) }
    val history = remember { mutableStateListOf<ByteArray>() }
    var saved by remember { mutableStateOf(true) }
    var sizes by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var tool by remember { mutableStateOf(EditTool.EditText) }
    var ink by remember { mutableStateOf(INKS[1]) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var pictureAt by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var savedResult by remember { mutableStateOf<SavedResult?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val lines = remember { mutableStateMapOf<Int, List<TextLine>>() }
    // Zoom: [zoom] follows the fingers; pages are redrawn sharp at [renderZoom] once a pinch ends.
    var zoom by remember { mutableStateOf(1f) }
    var renderZoom by remember { mutableStateOf(1f) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val hScroll = rememberScrollState()
    fun zoomTo(z: Float) { zoom = z.coerceIn(1f, 4f); renderZoom = zoom }

    fun load(uri: Uri) {
        busy = true
        scope.launch {
            try {
                val b = withContext(worker) { DocumentFiles.readBytes(context, uri) }
                val n = DocumentFiles.displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Document.pdf"
                val s = withContext(worker) { MuPdfEngine().open(b, "e.pdf").use { d -> (0 until d.pageCount).map { d.pageSize(it).let { p -> p.width to p.height } } } }
                name = n; bytes = b; sizes = s; history.clear(); saved = true; version++; lines.clear()
            } catch (e: PasswordRequiredException) {
                problem = "This PDF is password protected. Unlock it first with Unlock PDF, then edit it."
            } catch (e: Exception) {
                problem = "This file can't be opened for editing. Choose a PDF that isn't damaged."
            } finally {
                busy = false
            }
        }
    }

    /** Applies a change to the open document, keeping the old version for Undo. */
    fun apply(change: (ByteArray) -> ByteArray) {
        val current = bytes ?: return
        busy = true
        scope.launch {
            try {
                val next = withContext(worker) { change(current) }
                history += current
                if (history.size > 30) history.removeAt(0)
                bytes = next; saved = false; version++; lines.clear()
            } catch (e: IllegalArgumentException) {
                problem = e.message ?: "That change couldn't be made."
            } catch (e: OutOfMemoryError) {
                problem = "This file is too large to edit on this phone."
            } catch (e: Exception) {
                problem = "That change couldn't be made on this page."
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) else if (bytes == null) onBack() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val at = pictureAt
        pictureAt = null
        if (uri != null && at != null) apply { b ->
            val img = DocumentFiles.readBytes(context, uri)
            val png = `in`.llmlabs.docsmanager.AndroidImageOps().convert(img, `in`.llmlabs.docsmanager.ImageFormat.Png)
            val info = `in`.llmlabs.docsmanager.AndroidImageOps().info(png)
            val w = minOf(200f, sizes[at.first].first * 0.6f)
            val h = w * info.height / info.width
            ops.addImage(b, at.first, png, at.second - w / 2, at.third - h / 2, w, h)
        }
    }
    LaunchedEffect(Unit) { if (start != null) load(start) else picker.launch(arrayOf("application/pdf")) }
    val saveGate = rememberSaveGate(onDenied = { problem = "Docs Manager Pro needs storage access to save to Download." })

    fun save() = saveGate {
        val b = bytes ?: return@saveGate
        busy = true
        scope.launch {
            try {
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                savedResult = withContext(worker) { saveOutputs(context, listOf(ToolOutput("$base edited", b, sizes.size))) }.first()
                saved = true
            } catch (e: Exception) {
                problem = "Couldn't save. Check there is space on the phone."
            } finally {
                busy = false
            }
        }
    }

    val leave = { if (!saved && bytes != null) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(name.ifEmpty { "Edit PDF" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (bytes != null) Text(if (saved) "No unsaved changes" else "Unsaved changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { history.removeLastOrNull()?.let { bytes = it; version++; lines.clear(); saved = history.isEmpty() } }, enabled = history.isNotEmpty() && !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, "Undo")
                    }
                    IconButton(onClick = onMoreTools) { Icon(Icons.Outlined.Apps, "All PDF tools") }
                    Button(onClick = ::save, enabled = bytes != null && !busy && !saved, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Save") }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) { pad ->
        val b = bytes
        if (b == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Choose a PDF to edit", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Choose a PDF") }
                }
            }
        } else {
            Row(Modifier.padding(pad).fillMaxSize()) {
                ToolRail(tool, ink, onTool = { tool = it }, onInk = { ink = it })
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(tool.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(tool.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            IconButton(onClick = { zoomTo(zoom / 1.5f) }, enabled = zoom > 1.01f, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ZoomOut, "Zoom out", Modifier.size(20.dp)) }
                            Text(
                                "${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { zoomTo(1f) }.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            IconButton(onClick = { zoomTo(zoom * 1.5f) }, enabled = zoom < 3.99f, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ZoomIn, "Zoom in", Modifier.size(20.dp)) }
                        }
                    }
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(Unit) {
                            // Two fingers zoom and move the pages; one finger stays with the chosen tool.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var pinched = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        pinched = true
                                        val c = event.calculateCentroid(useCurrent = false)
                                        val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                        val f = newZoom / zoom
                                        if (f != 1f) {
                                            zoom = newZoom
                                            listState.dispatchRawDelta((f - 1f) * (listState.firstVisibleItemScrollOffset + c.y))
                                            val target = ((hScroll.value + c.x) * f - c.x).toInt()
                                            scope.launch { hScroll.scrollTo(target) }
                                        }
                                        val pan = event.calculatePan()
                                        listState.dispatchRawDelta(-pan.y)
                                        hScroll.dispatchRawDelta(-pan.x)
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (event.changes.none { it.pressed }) break
                                }
                                if (pinched) renderZoom = zoom
                            }
                        },
                    ) {
                        val viewW = maxWidth
                        // Pages are drawn at most 2600 px wide, sharp enough at any zoom without running out of memory.
                        val widthPx = with(LocalDensity.current) { (viewW * renderZoom - 24.dp).toPx().toInt() }.coerceIn(100, 2600)
                        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoom > 1.01f)) {
                        LazyColumn(state = listState, modifier = Modifier.width(viewW * zoom).fillMaxHeight(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(sizes.indices.toList(), key = { it }) { page ->
                                PageSurface(
                                    bytes = b, version = version, page = page, size = sizes[page], widthPx = widthPx, worker = worker, scope = scope,
                                    tool = tool, ink = ink, enabled = !busy,
                                    onTap = { x, y ->
                                        when (tool) {
                                            EditTool.EditText -> scope.launch {
                                                val pageLines = lines[page] ?: withContext(worker) { runCatching { ops.textLines(b, page) }.getOrDefault(emptyList()) }.also { lines[page] = it }
                                                val hit = pageLines.firstOrNull { x in it.x0 - 4..it.x1 + 4 && y in it.y0 - 4..it.y1 + 4 }
                                                    ?: pageLines.minByOrNull { l -> Math.abs((l.y0 + l.y1) / 2 - y) }?.takeIf { l -> Math.abs((l.y0 + l.y1) / 2 - y) < 12 && x in l.x0 - 20..l.x1 + 20 }
                                                if (hit != null) pending = Pending.EditLine(page, hit)
                                                else problem = if (pageLines.isEmpty()) "There's no text on this page to edit. If it's a scan, run OCR PDF first, or use Add text." else "Tap right on a line of text to change it."
                                            }
                                            EditTool.AddText -> pending = Pending.Text(page, x, y)
                                            EditTool.Comment -> pending = Pending.Note(page, x, y)
                                            EditTool.Sign -> pending = Pending.Signature(page, x, y)
                                            EditTool.Picture -> { pictureAt = Triple(page, x, y); imagePicker.launch(arrayOf("image/*")) }
                                            else -> Unit
                                        }
                                    },
                                    onArea = { x, y, w, h ->
                                        when (tool) {
                                            EditTool.Highlight -> apply { ops.highlightArea(it, page, x, y, w, h) }
                                            EditTool.Whiteout -> apply { ops.addRect(it, page, x, y, w, h, fill = 0xFFFFFFFF.toInt(), stroke = null) }
                                            EditTool.Box -> apply { ops.addRect(it, page, x, y, w, h, fill = null, stroke = ink.toArgb(), strokeWidth = 2f) }
                                            EditTool.Redact -> apply { ops.redactArea(it, page, x, y, w, h) }
                                            else -> Unit
                                        }
                                    },
                                    onStroke = { points -> apply { ops.addInk(it, page, listOf(points), ink.toArgb(), 2.5f) } },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    when (val p = pending) {
        is Pending.Text -> TextDialog("Add text", "", showSize = true, onDismiss = { pending = null }) { text, size ->
            pending = null; apply { ops.addText(it, p.page, p.x, p.y, text, size, ink.toArgb()) }
        }
        is Pending.EditLine -> TextDialog("Change text", p.line.text, showSize = false, onDismiss = { pending = null }) { text, _ ->
            pending = null
            apply { ops.replaceArea(it, p.page, p.line.x0, p.line.y0, p.line.x1 - p.line.x0, p.line.y1 - p.line.y0, text, bold = p.line.bold) }
        }
        is Pending.Note -> TextDialog("Comment", "", showSize = false, onDismiss = { pending = null }) { text, _ ->
            pending = null; apply { ops.addNote(it, p.page, p.x, p.y, text) }
        }
        is Pending.Signature -> SignatureDialog(onDismiss = { pending = null }) { png ->
            pending = null
            apply { b ->
                val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                val w = minOf(160f, sizes[p.page].first * 0.5f); val h = w * bmp.height / bmp.width
                ops.addImage(b, p.page, png, p.x - w / 2, p.y - h / 2, w, h)
            }
        }
        null -> Unit
    }

    problem?.let { msg ->
        AlertDialog(
            onDismissRequest = { problem = null; if (bytes == null) onBack() },
            icon = { Icon(Icons.Outlined.ErrorOutline, null) },
            title = { Text(if (bytes == null) "Can't open this file" else "Couldn't do that") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { problem = null; if (bytes == null) onBack() }) { Text("OK") } },
        )
    }

    savedResult?.let { r ->
        AlertDialog(
            onDismissRequest = { savedResult = null },
            icon = { Icon(Icons.Outlined.CheckCircle, null) },
            title = { Text("Saved") },
            text = { Text("${r.name} is in Download/Docs Manager Pro. Your original is unchanged.") },
            confirmButton = { TextButton(onClick = { savedResult = null; onOpenResult(r.uri) }) { Text("Open") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { shareFiles(context, listOf(r.uri)) }) { Text("Share") }
                    TextButton(onClick = { savedResult = null }) { Text("Keep editing") }
                }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your changes to $name haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") }
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/** The tools down the left side, with the ink colour at the bottom. */
@Composable
private fun ToolRail(tool: EditTool, ink: Color, onTool: (EditTool) -> Unit, onInk: (Color) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(76.dp).fillMaxHeight()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EditTool.entries.forEach { t ->
                val selected = t == tool
                Column(
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onTool(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        t.label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 12.dp))
            Text("Colour", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            INKS.forEach { c ->
                Box(
                    Modifier.padding(4.dp).size(26.dp).clip(CircleShape).background(c)
                        .border(if (c == ink) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { onInk(c) },
                )
            }
        }
    }
}

/** One page, drawn and ready for taps, drags and drawing in page points (from the top-left). */
@Composable
private fun PageSurface(
    bytes: ByteArray, version: Int, page: Int, size: Pair<Float, Float>, widthPx: Int, worker: kotlinx.coroutines.CoroutineDispatcher, scope: CoroutineScope,
    tool: EditTool, ink: Color, enabled: Boolean,
    onTap: (Float, Float) -> Unit, onArea: (Float, Float, Float, Float) -> Unit, onStroke: (List<Pair<Float, Float>>) -> Unit,
) {
    var image by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(version, widthPx) {
        image = withContext(worker) { runCatching { MuPdfEngine().open(bytes, "e.pdf").use { it.renderPage(page, widthPx).asImageBitmap() } }.getOrNull() } ?: image
    }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().aspectRatio(size.first / size.second).background(Color.White)) {
        image?.let { Image(it, "Page ${page + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        Canvas(
            Modifier.fillMaxSize().pointerInput(tool, enabled, version) {
                if (!enabled) return@pointerInput
                // Points per pixel, read on every touch: zooming changes the page width without restarting this block.
                fun k() = size.first / this.size.width
                if (tool.drag) {
                    detectDragGestures(
                        onDragStart = { o -> if (tool == EditTool.Draw) stroke = listOf(o) else { dragStart = o; dragNow = o } },
                        onDrag = { ch, _ -> ch.consume(); if (tool == EditTool.Draw) stroke = stroke + ch.position else dragNow = ch.position },
                        onDragEnd = {
                            if (tool == EditTool.Draw) {
                                if (stroke.size > 1) onStroke(stroke.map { it.x * k() to it.y * k() })
                                stroke = emptyList()
                            } else {
                                val a = dragStart; val b = dragNow
                                if (a != null && b != null && Math.abs(a.x - b.x) > 6 && Math.abs(a.y - b.y) > 6) {
                                    onArea(minOf(a.x, b.x) * k(), minOf(a.y, b.y) * k(), Math.abs(a.x - b.x) * k(), Math.abs(a.y - b.y) * k())
                                }
                                dragStart = null; dragNow = null
                            }
                        },
                        onDragCancel = { dragStart = null; dragNow = null; stroke = emptyList() },
                    )
                } else {
                    detectTapGestures { o -> onTap(o.x * k(), o.y * k()) }
                }
            },
        ) {
            val a = dragStart; val b = dragNow
            if (a != null && b != null) {
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y)); val sz = Size(Math.abs(a.x - b.x), Math.abs(a.y - b.y))
                val fill = when (tool) { EditTool.Highlight -> Color(0x66FFD400); EditTool.Whiteout -> Color.White; EditTool.Redact -> Color(0xCC000000); else -> Color.Transparent }
                drawRect(fill, tl, sz)
                drawRect(if (tool == EditTool.Box) ink else primary, tl, sz, style = Stroke(2.dp.toPx()))
            }
            if (stroke.size > 1) {
                val p = Path().apply { moveTo(stroke[0].x, stroke[0].y); stroke.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(p, ink, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            "${page + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TextDialog(title: String, initial: String, showSize: Boolean, onDismiss: () -> Unit, onDone: (String, Float) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var size by remember { mutableStateOf(14f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = if (showSize) 2 else 1, modifier = Modifier.fillMaxWidth())
                if (showSize) {
                    Spacer(Modifier.height(8.dp))
                    Text("Size: ${size.toInt()} pt", style = MaterialTheme.typography.bodySmall)
                    Slider(value = size, onValueChange = { size = it }, valueRange = 8f..48f)
                }
                if (!showSize && initial.isNotEmpty()) Text("The new text uses a standard font, so it may look slightly different.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, size) }, enabled = text.isNotBlank() && text != initial) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SignatureDialog(onDismiss: () -> Unit, onDone: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sig = remember { Signature() }
    var mode by remember { mutableStateOf(0) } // 0 draw, 1 type, 2 upload
    var name by remember { mutableStateOf("") }
    var uploaded by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploadProblem by remember { mutableStateOf<String?>(null) }
    val ink = Color(0xFF1B3A8C)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            uploadProblem = null
            try {
                val png = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    `in`.llmlabs.docsmanager.AndroidImageOps().cutOutSignature(DocumentFiles.readBytes(context, uri))
                }
                uploaded = png
                preview = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            } catch (e: IllegalArgumentException) {
                uploadProblem = e.message
            } catch (e: Exception) {
                uploadProblem = "That picture can't be read."
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Draw") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Type") })
                    FilterChip(selected = mode == 2, onClick = { mode = 2; if (uploaded == null) picker.launch(arrayOf("image/*")) }, label = { Text("Upload") })
                }
                when (mode) {
                    0 -> {
                        SignaturePad(sig, true, ink)
                        TextButton(onClick = { sig.strokes.clear(); sig.current = emptyList() }) { Text("Clear") }
                    }
                    1 -> OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Your name") }, singleLine = true)
                    else -> {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(2.5f).clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            preview?.let { Image(it, "Your signature", Modifier.fillMaxSize().padding(8.dp)) }
                                ?: Text("A photo or scan of your signature on light paper", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8F9C), textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                        }
                        uploadProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(if (uploaded == null) "Choose a picture" else "Choose another picture") }
                        Text("The paper is removed automatically, so only the ink is placed on the page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        2 -> uploaded?.let(onDone)
                        1 -> sig.toPng(name, ink.toArgb())?.let(onDone)
                        else -> sig.toPng(null, ink.toArgb())?.let(onDone)
                    }
                },
                enabled = when (mode) { 0 -> !sig.isEmpty; 1 -> name.isNotBlank(); else -> uploaded != null },
            ) { Text("Place it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
