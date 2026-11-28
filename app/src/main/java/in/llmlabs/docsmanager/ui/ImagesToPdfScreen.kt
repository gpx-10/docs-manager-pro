package `in`.llmlabs.docsmanager.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import `in`.llmlabs.docsmanager.AndroidImageOps
import `in`.llmlabs.docsmanager.ImagePage
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.PdfOps
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An image the user added: where it is, a small picture of it, and a short description. */
data class PickedImage(val id: Long, val uri: Uri, val name: String, val thumb: ImageBitmap? = null, val detail: String = "Reading…", val error: Boolean = false)

/** Reads an image's size and makes a small picture of it, off the main thread. */
suspend fun describeImage(context: Context, img: PickedImage): PickedImage = withContext(Dispatchers.IO) {
    try {
        val bytes = DocumentFiles.readBytes(context, img.uri)
        val info = AndroidImageOps().info(bytes)
        img.copy(thumb = thumbnail(bytes, 200)?.asImageBitmap(), detail = "${info.width} × ${info.height} · ${info.format} · ${sizeText(bytes.size)}")
    } catch (e: IllegalArgumentException) {
        img.copy(detail = e.message ?: "This image can't be read", error = true)
    } catch (e: Throwable) {
        img.copy(detail = DocumentFiles.friendlyError(e), error = true)
    }
}

/** A small decoded copy of an image, turned the way the camera meant. */
fun thumbnail(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
            val k = maxOf(1f, maxOf(info.size.width, info.size.height) / maxSide.toFloat())
            d.setTargetSize((info.size.width / k).toInt().coerceAtLeast(1), (info.size.height / k).toInt().coerceAtLeast(1))
        }
    } else {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

/**
 * Images to PDF and Scan to PDF: collect photos (from files or the camera), put them in order,
 * and save them as one PDF. [scan] starts with the camera.
 */
@Composable
fun ImagesToPdfScreen(scan: Boolean, onBack: () -> Unit, onOpenResult: (Uri) -> Unit, ops: PdfOps = remember { MuPdfOps() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<PickedImage>() }
    var page by remember { mutableStateOf(if (scan) ImagePage.A4 else ImagePage.FitImage) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }
    var nextId by remember { mutableStateOf(0L) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    fun add(uri: Uri, label: String) {
        val img = PickedImage(nextId++, uri, label)
        images += img
        if (name.isBlank()) name = if (scan) "Scan ${java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale.getDefault()).format(java.util.Date())}" else label.substringBeforeLast('.')
        scope.launch {
            val done = describeImage(context, img)
            val i = images.indexOfFirst { it.id == img.id }
            if (i >= 0) images[i] = done
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        error = null
        uris.forEach { add(it, DocumentFiles.displayName(context, it) ?: "Image") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) add(u, "Page ${images.size + 1}.jpg")
    }
    val takePhoto: () -> Unit = {
        val dir = File(context.cacheDir, "shared/scan").apply { mkdirs() }
        val file = File(dir, "page-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        photoUri = uri
        try {
            camera.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            error = "No camera app was found on this phone."
        }
    }
    val pickImages = { picker.launch(arrayOf("image/*")) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })
    val ready = images.isNotEmpty() && images.none { it.error || it.thumb == null && it.detail == "Reading…" }

    LaunchedEffect(Unit) { if (scan && images.isEmpty()) takePhoto() }

    fun make() = saveGate {
        busy = true
        error = null
        scope.launch {
            try {
                val pdf = withContext(Dispatchers.Default) {
                    ops.imagesToPdf(images.map { DocumentFiles.readBytes(context, it.uri) }, page)
                }
                results = withContext(Dispatchers.IO) {
                    saveOutputs(context, listOf(ToolOutput(name.ifBlank { if (scan) "Scan" else "Images" }, pdf, images.size)))
                }
                File(context.cacheDir, "shared/scan").listFiles()?.forEach { it.delete() } // camera photos are no longer needed
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: OutOfMemoryError) {
                error = "These images are too large for this phone. Try fewer at a time."
            } catch (e: Exception) {
                error = "Couldn't make the PDF. One of the images may be damaged."
            } finally {
                busy = false
            }
        }
    }

    error?.let { ProblemDialog(it) { error = null } }
    ToolScaffold(
        title = if (scan) "Scan to PDF" else "Images to PDF",
        onBack = onBack,
        bottomBar = {
            if (results == null && images.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        Button(onClick = ::make, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Making the PDF…")
                            } else {
                                Text("Make a PDF of ${pagesText(images.size)}")
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val done = results
            if (done != null) {
                item { ResultsPanel(done, onOpenResult, onAgain = { images.clear(); name = ""; results = null }, againLabel = if (scan) "Scan more" else "Make another") }
                return@LazyColumn
            }
            if (images.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (scan) Icons.Outlined.DocumentScanner else Icons.Outlined.Image, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (scan) "Photograph each page with your camera. They become one PDF, in order." else "Choose photos or pictures. Each becomes one page of a PDF, in the order you set.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (scan) Button(onClick = takePhoto) { Text("Take a photo") } else Button(onClick = pickImages) { Text("Choose images") }
                                if (scan) OutlinedButton(onClick = pickImages) { Text("From files") } else OutlinedButton(onClick = takePhoto) { Text("Camera") }
                            }
                        }
                    }
                }
                    return@LazyColumn
            }
            item { Text("Pages are in this order, top to bottom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(images, key = { _, im -> im.id }) { i, im ->
                ImageRow(i + 1, im) {
                    IconButton(onClick = { images.add(i - 1, images.removeAt(i)) }, enabled = i > 0 && !busy) { Icon(Icons.Outlined.ArrowUpward, "Move ${im.name} up") }
                    IconButton(onClick = { images.add(i + 1, images.removeAt(i)) }, enabled = i < images.lastIndex && !busy) { Icon(Icons.Outlined.ArrowDownward, "Move ${im.name} down") }
                    IconButton(onClick = { images.removeAt(i) }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${im.name}") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = takePhoto, enabled = !busy) {
                        Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (scan) "Next page" else "Camera")
                    }
                    OutlinedButton(onClick = pickImages, enabled = !busy) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add images")
                    }
                }
            }
            heading("Page size")
            item { Choices(ImagePage.entries, page, !busy, title = { it.label }, subtitle = { if (it == ImagePage.FitImage) "Each page is the shape of its image" else "Images centred with a small margin" }) { page = it } }
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name of the PDF") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** One image in a list: its picture, position or name, and details. */
@Composable
fun ImageRow(position: Int?, img: PickedImage, trailing: @Composable RowScope.() -> Unit = {}) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                img.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (position != null) "$position. ${img.name}" else img.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(img.detail, style = MaterialTheme.typography.bodySmall, color = if (img.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}
