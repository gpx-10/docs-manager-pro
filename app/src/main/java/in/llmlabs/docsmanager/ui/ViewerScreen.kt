package `in`.llmlabs.docsmanager.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.DocumentEngine
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.OpenDocument
import `in`.llmlabs.docsmanager.PageSize
import `in`.llmlabs.docsmanager.PasswordRequiredException
import `in`.llmlabs.docsmanager.ViewerState
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** An open Document plus the one thread allowed to touch it (MuPDF is not thread-safe). */
private class Session(val doc: OpenDocument, val thread: ExecutorCoroutineDispatcher) {
    val sizes: SnapshotStateMap<Int, PageSize> = mutableStateMapOf()
    val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

private sealed interface Load {
    data object Loading : Load
    data class NeedPassword(val wrong: Boolean) : Load
    data class Failed(val message: String) : Load
    data class Ready(val session: Session) : Load
}

private val A4 = PageSize(595f, 842f)
private val INVERT = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(uri: Uri, onBack: () -> Unit, onOpened: (name: String) -> Unit, engine: DocumentEngine = remember { MuPdfEngine() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thread = remember(uri) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    var name by remember(uri) { mutableStateOf("") }
    var bytes by remember(uri) { mutableStateOf<ByteArray?>(null) }
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var load by remember(uri) { mutableStateOf<Load>(Load.Loading) }
    var viewer by remember(uri) { mutableStateOf(ViewerState(pageCount = 1)) }
    var night by rememberSaveable { mutableStateOf(false) }
    var goToOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)

    LaunchedEffect(uri) {
        try {
            name = withContext(Dispatchers.IO) { DocumentFiles.displayName(context, uri) }
            bytes = withContext(Dispatchers.IO) { DocumentFiles.readBytes(context, uri) }
        } catch (e: Exception) {
            load = Load.Failed(e.message ?: "Could not read this file")
        }
    }

    LaunchedEffect(bytes, password) {
        val b = bytes ?: return@LaunchedEffect
        load = Load.Loading
        load = try {
            val session = withContext(thread) {
                val doc = engine.open(b, name, password)
                Session(doc, thread).also { it.sizes[0] = doc.pageSize(0) }
            }
            viewer = ViewerState(pageCount = session.doc.pageCount)
            onOpened(name)
            Load.Ready(session)
        } catch (e: PasswordRequiredException) {
            Load.NeedPassword(e.wrongPassword)
        } catch (e: Exception) {
            Load.Failed("The file may be damaged, empty, or not really a ${name.substringAfterLast('.', "supported").uppercase()} file.")
        }
    }

    DisposableEffect(uri) {
        onDispose {
            (load as? Load.Ready)?.session?.doc?.let { doc -> thread.executor.execute { doc.close() } }
            thread.close()
        }
    }

    // Keep the page counter in step with scrolling.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { viewer = viewer.goTo(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { night = !night }) {
                        Icon(if (night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (night) "Day mode" else "Night mode")
                    }
                    IconButton(onClick = { share(context, uri) }) { Icon(Icons.Outlined.Share, "Share") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Go to page") }, leadingIcon = { Icon(Icons.Outlined.Numbers, null) }, onClick = { menuOpen = false; goToOpen = true })
                            DropdownMenuItem(text = { Text("Fit width") }, leadingIcon = { Icon(Icons.Outlined.FitScreen, null) }, onClick = { menuOpen = false; viewer = viewer.zoomBy(1f / viewer.zoom) })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(if (night) Color(0xFF0E0F13) else MaterialTheme.colorScheme.surfaceVariant)) {
            when (val l = load) {
                Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Load.Failed -> Message(Icons.Outlined.ErrorOutline, "Couldn't open this file", l.message, Modifier.align(Alignment.Center))
                is Load.NeedPassword -> PasswordPrompt(l.wrong, onSubmit = { password = it }, onCancel = onBack)
                is Load.Ready -> {
                    Pages(l.session, viewer, night, listState, onZoom = { viewer = viewer.zoomBy(it) })
                    PageChip(viewer.pageLabel, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) { goToOpen = true }
                }
            }
        }
    }

    if (goToOpen) {
        GoToPageDialog(viewer.pageCount, onDismiss = { goToOpen = false }) { page ->
            goToOpen = false
            viewer = viewer.goTo(page)
            scope.launch { listState.scrollToItem(viewer.currentPage) }
        }
    }
}

@Composable
private fun Pages(session: Session, viewer: ViewerState, night: Boolean, listState: androidx.compose.foundation.lazy.LazyListState, onZoom: (Float) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers zoom; one finger is left to scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) onZoom(z)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(viewer.zoom > 1f) {
                detectTapGestures(onDoubleTap = { onZoom(if (viewer.zoom > 1f) 1f / viewer.zoom else 2f) })
            },
    ) {
        val density = LocalDensity.current
        val viewWidth = maxWidth
        val pageWidth = viewWidth * viewer.zoom
        // Sharpness steps: re-draw only when zoom moves to a new half-step, up to 2x screen width.
        val step = (Math.round(viewer.zoom * 2f) / 2f).coerceAtMost(2f)
        val renderWidthPx = with(density) { (viewWidth - 24.dp).toPx() * step }.toInt().coerceAtLeast(1)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = viewer.zoom > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.requiredWidth(pageWidth).fillMaxHeight(),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(viewer.pageCount, key = { it }) { index -> Page(session, index, renderWidthPx, night) }
            }
        }
    }
}

@Composable
private fun Page(session: Session, index: Int, widthPx: Int, night: Boolean) {
    val key = "$index@$widthPx"
    var bitmap by remember(key) { mutableStateOf(session.cache.get(key)) }
    val size = session.sizes[index] ?: session.sizes[0] ?: A4

    LaunchedEffect(index) {
        if (session.sizes[index] == null) session.sizes[index] = withContext(session.thread) { session.doc.pageSize(index) }
    }
    LaunchedEffect(key) {
        if (bitmap == null) {
            bitmap = withContext(session.thread) {
                session.cache.get(key) ?: session.doc.renderPage(index, widthPx).also { session.cache.put(key, it) }
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(size.width / size.height)
            .shadow(2.dp, RoundedCornerShape(2.dp))
            .background(if (night) Color.Black else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = if (night) INVERT else null)
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PageChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        color = Color(0xE61B1F2A),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                label = { Text("Page (1 to $pageCount)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = page != null, onClick = { onGo(page!! - 1) }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text("Password protected") },
        text = {
            Column {
                Text("Enter the password to open this document.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text("Wrong password, try again") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = { onSubmit(text) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun share(context: android.content.Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(context.contentResolver.getType(uri) ?: "application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share"))
}
