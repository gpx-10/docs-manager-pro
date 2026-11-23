package `in`.llmlabs.docsmanager.ui

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import `in`.llmlabs.docsmanager.DocumentEngine
import `in`.llmlabs.docsmanager.MainActivity
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.OpenDocument
import `in`.llmlabs.docsmanager.PageSize
import `in`.llmlabs.docsmanager.PasswordRequiredException
import `in`.llmlabs.docsmanager.ViewerState
import `in`.llmlabs.docsmanager.data.AllFiles
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** An open Document plus the one thread allowed to touch it (MuPDF is not thread-safe). */
private class Session(val doc: OpenDocument, val thread: ExecutorCoroutineDispatcher) {
    val sizes: SnapshotStateMap<Int, PageSize> = mutableStateMapOf()

    // A modest budget so the app stays light on budget phones: about 8 screen-sized pages.
    val cache = object : LruCache<String, Bitmap>(minOf(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

private sealed interface Load {
    data object Loading : Load
    data class NeedPassword(val wrong: Boolean, val attempt: Int) : Load
    data class Failed(val message: String, val unavailable: Boolean) : Load
    data class Ready(val session: Session) : Load
}

private val A4 = PageSize(595f, 842f)
/**
 * Night mode: flips light and dark but keeps each colour's hue (an inversion followed by a
 * 180° hue turn), so white paper turns black and text turns light while blue charts stay blue.
 */
private val INVERT = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.574f, -1.430f, -0.144f, 0f, 255f,
    -0.426f, -0.430f, -0.144f, 0f, 255f,
    -0.426f, -1.430f, 0.856f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))
private val NIGHT_BAR = Color(0xFF1B1E28)
private val NIGHT_PAPER = Color(0xFF0E0F13)
private const val DEFAULT_TEXT_SIZE = 12f
private val TEXT_SIZES = listOf(9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f)

/** A point to keep steady while zooming, inside the pages area, in pixels. */
private data class ZoomAnchor(val focus: Offset, val ratio: Float, val firstIndex: Int, val firstOffset: Int, val scrollX: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    uri: Uri,
    sessionKey: Long,
    knownName: String?,
    startPage: Int,
    onBack: () -> Unit,
    /** Called once the Document opens; returns the key Recent uses for it. */
    onOpened: (name: String, bytes: ByteArray) -> String,
    onPageChanged: (recentKey: String, page: Int) -> Unit,
    /** Set when the Document came from Recent, so an unavailable file can be removed from it. */
    onRemoveFromRecent: (() -> Unit)?,
    engine: DocumentEngine = remember { MuPdfEngine() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thread = remember(sessionKey) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    var name by remember(sessionKey) { mutableStateOf(knownName ?: "") }
    var bytes by remember(sessionKey) { mutableStateOf<ByteArray?>(null) }
    var password by remember(sessionKey) { mutableStateOf<String?>(null) }
    var attempt by remember(sessionKey) { mutableIntStateOf(0) }
    var load by remember(sessionKey) { mutableStateOf<Load>(Load.Loading) }
    var recentKey by remember(sessionKey) { mutableStateOf<String?>(null) }
    var viewer by remember(sessionKey) { mutableStateOf(ViewerState(pageCount = 1)) }
    var layoutVersion by remember(sessionKey) { mutableIntStateOf(0) }
    var textSize by rememberSaveable { mutableFloatStateOf(DEFAULT_TEXT_SIZE) }
    var reflowable by remember(sessionKey) { mutableStateOf(false) }
    var night by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var goToOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var textMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val ready = load is Load.Ready

    BackHandler(onBack = onBack)

    // Read the file.
    LaunchedEffect(sessionKey) {
        try {
            withContext(Dispatchers.IO) {
                // The name saved in Recent wins: a private copy's link may not carry the original name.
                if (knownName == null) DocumentFiles.displayName(context, uri)?.let { name = it }
                bytes = DocumentFiles.readBytes(context, uri)
            }
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        } catch (e: Throwable) {
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
            val message = if (uri.scheme == "file" && !AllFiles.isGranted(context))
                "This file was found with full access, which is now off. Turn it back on in the Files tab to open it."
            else DocumentFiles.friendlyError(e)
            load = Load.Failed(message, unavailable = e !is OutOfMemoryError)
        }
    }

    // Open it. Every password attempt counts, even the same wrong password twice.
    LaunchedEffect(bytes, attempt) {
        val b = bytes ?: return@LaunchedEffect
        load = Load.Loading
        load = try {
            val session = withContext(thread) {
                val doc = engine.open(b, name, password)
                if (doc.isReflowable) doc.setTextSize(textSize)
                Session(doc, thread).also { it.sizes[0] = doc.pageSize(0) }
            }
            reflowable = session.doc.isReflowable
            viewer = ViewerState(pageCount = session.doc.pageCount).goTo(startPage)
            recentKey = onOpened(name, b)
            Load.Ready(session)
        } catch (e: PasswordRequiredException) {
            Load.NeedPassword(e.wrongPassword, attempt)
        } catch (e: OutOfMemoryError) {
            Load.Failed(DocumentFiles.friendlyError(e), unavailable = false)
        } catch (e: Exception) {
            Load.Failed("The file may be damaged, empty, or not really a ${name.substringAfterLast('.', "supported").uppercase()} file.", unavailable = false)
        }
        // Continue where the reader left off.
        if (load is Load.Ready && viewer.currentPage > 0) listState.scrollToItem(viewer.currentPage)
    }

    DisposableEffect(sessionKey) {
        onDispose {
            (load as? Load.Ready)?.session?.doc?.let { doc -> thread.executor.execute { doc.close() } }
            thread.close()
        }
    }

    // Free page images when Android is short of memory.
    DisposableEffect(load) {
        val session = (load as? Load.Ready)?.session
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) session?.cache?.evictAll()
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() { session?.cache?.evictAll() }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    // The page counter follows what is mostly on screen, and reaches the last page at the end.
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val hidden = if (first != null && first.size > 0) listState.firstVisibleItemScrollOffset.toFloat() / first.size else 0f
            Triple(listState.firstVisibleItemIndex, hidden, !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 1)
        }.collect { (index, hidden, atEnd) -> viewer = viewer.scrolledTo(index, hidden, atEnd) }
    }

    // Remember the page, so the file reopens here.
    LaunchedEffect(recentKey) {
        val key = recentKey ?: return@LaunchedEffect
        snapshotFlow { viewer.currentPage }.collectLatest { delay(400); onPageChanged(key, it) }
    }

    // Show the page counter while scrolling, then fade it away.
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { moving ->
            if (moving) scrolling = true else { delay(1500); scrolling = false }
        }
    }

    // Dark status bar icons on light bars, light icons on dark bars.
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    DisposableEffect(night, darkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !(night || darkTheme)
        onDispose { controller?.isAppearanceLightStatusBars = !darkTheme }
    }

    val showChrome = chromeVisible || !ready
    Scaffold(
        topBar = {
            AnimatedVisibility(visible = showChrome, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                    colors = if (night) TopAppBarDefaults.topAppBarColors(
                        containerColor = NIGHT_BAR, titleContentColor = Color.White,
                        navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                    ) else TopAppBarDefaults.topAppBarColors(),
                    actions = {
                        val session = (load as? Load.Ready)?.session
                        if (session != null && reflowable) {
                            Box {
                                IconButton(onClick = { textMenuOpen = true }) { Icon(Icons.Outlined.FormatSize, "Text size") }
                                TextSizeMenu(textMenuOpen, textSize, onDismiss = { textMenuOpen = false }) { size ->
                                    val keepFraction = viewer.currentPage.toFloat() / viewer.pageCount
                                    scope.launch {
                                        val count = withContext(session.thread) {
                                            session.doc.setTextSize(size)
                                            session.cache.evictAll()
                                            session.doc.pageCount
                                        }
                                        val first = withContext(session.thread) { session.doc.pageSize(0) }
                                        session.sizes.clear()
                                        session.sizes[0] = first
                                        textSize = size
                                        layoutVersion++
                                        viewer = ViewerState(pageCount = count, zoom = viewer.zoom).goTo((keepFraction * count).toInt())
                                        listState.scrollToItem(viewer.currentPage)
                                    }
                                }
                            }
                        }
                        if (session != null) {
                            IconButton(onClick = { night = !night }) {
                                Icon(if (night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (night) "Day mode" else "Night mode")
                            }
                        }
                        if (load !is Load.Failed) IconButton(onClick = { share(context, uri, name) }) { Icon(Icons.Outlined.Share, "Share") }
                        if (session != null) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Go to page") }, leadingIcon = { Icon(Icons.Outlined.Numbers, null) }, onClick = { menuOpen = false; goToOpen = true })
                                    DropdownMenuItem(
                                        text = { Text("Fit width") }, leadingIcon = { Icon(Icons.Outlined.FitScreen, null) },
                                        enabled = viewer.zoom > 1f,
                                        onClick = { menuOpen = false; viewer = viewer.zoomBy(1f / viewer.zoom) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .background(if (night) NIGHT_PAPER else MaterialTheme.colorScheme.surfaceVariant)
                // With the bar hidden, still keep pages clear of the clock and status icons.
                .then(if (showChrome) Modifier.padding(top = pad.calculateTopPadding()) else Modifier.statusBarsPadding()),
        ) {
            when (val l = load) {
                Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Load.Failed -> Unavailable(l, onRemoveFromRecent, onBack, Modifier.align(Alignment.Center))
                is Load.NeedPassword -> PasswordPrompt(l.wrong, l.attempt, onSubmit = { password = it; attempt++ }, onCancel = onBack)
                is Load.Ready -> {
                    Pages(
                        session = l.session,
                        pageCount = viewer.pageCount,
                        zoom = viewer.zoom,
                        layoutVersion = layoutVersion,
                        night = night,
                        listState = listState,
                        hScroll = hScroll,
                        onZoom = { factor -> viewer = viewer.zoomBy(factor) },
                        onTap = { chromeVisible = !chromeVisible },
                    )
                    AnimatedVisibility(
                        visible = chromeVisible || scrolling,
                        enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                    ) {
                        PageChip(viewer.pageLabel, night) { goToOpen = true }
                    }
                }
            }
        }
    }

    if (goToOpen) {
        GoToPageDialog(viewer, onDismiss = { goToOpen = false }) { page ->
            goToOpen = false
            viewer = viewer.goTo(page)
            scope.launch { listState.scrollToItem(page) }
        }
    }
}

/**
 * The pages, in one scrolling column. Two fingers zoom around the point between them;
 * a double tap zooms around the tapped point; a single tap shows or hides the controls.
 */
@Composable
private fun Pages(
    session: Session,
    pageCount: Int,
    zoom: Float,
    layoutVersion: Int,
    night: Boolean,
    listState: LazyListState,
    hScroll: ScrollState,
    onZoom: (factor: Float) -> Unit,
    onTap: () -> Unit,
) {
    var anchor by remember { mutableStateOf<ZoomAnchor?>(null) }
    val currentZoom by rememberUpdatedState(zoom)

    // After the zoom changes the layout, scroll so the focus point stays under the finger.
    LaunchedEffect(zoom) {
        val a = anchor ?: return@LaunchedEffect
        anchor = null
        withFrameNanos { }
        hScroll.scrollTo(((a.scrollX + a.focus.x) * a.ratio - a.focus.x).roundToInt().coerceAtLeast(0))
        listState.scrollToItem(a.firstIndex, ((a.firstOffset + a.focus.y) * a.ratio - a.focus.y).roundToInt().coerceAtLeast(0))
    }

    fun zoomAround(factor: Float, focus: Offset) {
        val before = currentZoom
        val after = (before * factor).coerceIn(1f, 5f)
        if (after == before) return
        anchor = ZoomAnchor(focus, after / before, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, hScroll.value)
        onZoom(factor)
    }

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
                            if (z != 1f) zoomAround(z, event.calculateCentroid())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at -> zoomAround(if (currentZoom > 1f) 1f / currentZoom else 2f, at) },
                )
            },
    ) {
        val density = LocalDensity.current
        val viewWidth = maxWidth
        // Sharpness steps: re-draw only when zoom moves to a new half-step, up to 2x screen width.
        val step = (Math.round(zoom * 2f) / 2f).coerceIn(1f, 2f)
        val renderWidthPx = with(density) { (viewWidth - 24.dp).toPx() * step }.toInt().coerceAtLeast(1)
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoom > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.requiredWidth(viewWidth * zoom).fillMaxHeight(),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pageCount, key = { "$layoutVersion-$it" }, contentType = { "page" }) { index ->
                    Page(session, index, renderWidthPx, layoutVersion, night)
                }
            }
        }
    }
}

@Composable
private fun Page(session: Session, index: Int, widthPx: Int, layoutVersion: Int, night: Boolean) {
    val key = "$layoutVersion:$index@$widthPx"
    var bitmap by remember(key) { mutableStateOf(session.cache.get(key)) }
    val size = session.sizes[index] ?: session.sizes[0] ?: A4

    LaunchedEffect(layoutVersion, index) {
        if (session.sizes[index] == null) {
            val s = withContext(session.thread) { session.doc.pageSize(index) }
            session.sizes[index] = s
        }
    }
    LaunchedEffect(key) {
        if (bitmap == null) {
            bitmap = withContext(session.thread) {
                session.cache.get(key) ?: session.doc.renderPage(index, widthPx).also {
                    it.prepareToDraw() // upload to the GPU now, not during the next scroll frame
                    session.cache.put(key, it)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(size.width / size.height)
            .background(if (night) Color.Black else Color.White)
            .border(0.5.dp, if (night) Color(0xFF2A2E3C) else Color(0xFFD5D9E4)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(image, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = if (night) INVERT else null)
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PageChip(label: String, night: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Go to page", onClick = onClick),
        color = if (night) Color(0xF2ECEEF5) else Color(0xE61B1F2A),
        contentColor = if (night) Color(0xFF1B1F2A) else Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextSizeMenu(expanded: Boolean, current: Float, onDismiss: () -> Unit, onPick: (Float) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val i = TEXT_SIZES.indexOf(current).coerceAtLeast(0)
            IconButton(enabled = i > 0, onClick = { onPick(TEXT_SIZES[i - 1]) }) { Icon(Icons.Outlined.TextDecrease, "Smaller text") }
            Text("${current.toInt()} pt", Modifier.width(56.dp), textAlign = TextAlign.Center)
            IconButton(enabled = i < TEXT_SIZES.lastIndex, onClick = { onPick(TEXT_SIZES[i + 1]) }) { Icon(Icons.Outlined.TextIncrease, "Larger text") }
        }
    }
}

@Composable
private fun GoToPageDialog(viewer: ViewerState, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = viewer.pageIndexFor(text)
    val showError = text.isNotEmpty() && page == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                label = { Text("Page (1 to ${viewer.pageCount})") },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) ({ Text("Enter a page from 1 to ${viewer.pageCount}") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onGo) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = page != null, onClick = { page?.let(onGo) }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordPrompt(wrong: Boolean, attempt: Int, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(attempt) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(attempt) { focus.requestFocus() }
    val submit = { if (text.isNotEmpty()) onSubmit(text) }
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
                    supportingText = if (wrong) ({ Text(if (attempt > 1) "Still not right. Check caps lock and try again" else "Wrong password, try again") }) else null,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide password" else "Show password")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = submit) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Unavailable(failed: Load.Failed, onRemoveFromRecent: (() -> Unit)?, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (failed.unavailable) Icons.Outlined.FolderOff else Icons.Outlined.ErrorOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Couldn't open this file", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(failed.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (onRemoveFromRecent != null) {
            Button(onClick = onRemoveFromRecent) { Text("Remove from Recent") }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onBack) { Text("Go back") }
    }
}

private fun share(context: android.content.Context, uri: Uri, name: String) {
    val link = DocumentFiles.shareableUri(context, uri)
    val send = Intent(Intent.ACTION_SEND)
        .setType(DocumentFiles.mimeType(context, link, name))
        .putExtra(Intent.EXTRA_STREAM, link)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, "Share")
        // Don't offer this app as a place to share to.
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    context.startActivity(chooser)
}
