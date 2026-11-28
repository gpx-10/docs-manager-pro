package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.AndroidImageOps
import `in`.llmlabs.docsmanager.ImageFormat
import `in`.llmlabs.docsmanager.ImageOps
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.data.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val imageOps: ImageOps = AndroidImageOps()

@Composable
fun PdfToImagesScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var format by remember { mutableStateOf(ImageFormat.Jpeg) }
    var dpi by remember { mutableStateOf(150) }
    val choice = remember { PageChoice() }
    SinglePdfTool(
        title = "PDF to images", intro = "Choose a PDF to save each page as a picture.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Convert another",
        action = { f -> choice.pages(f.pages!!).getOrNull()?.let { "Save ${it.size} ${if (it.size == 1) "image" else "images"}" } },
        run = { f ->
            val pages = choice.pages(f.pages!!).getOrThrow().sorted()
            // Draw only the chosen pages: extract them first, then draw that smaller PDF.
            val subset = if (pages.size == f.pages) f.bytes!! else MuPdfOps().organize(f.bytes!!, pages)
            MuPdfOps().pdfToImages(subset, dpi, format).mapIndexed { i, bytes ->
                ToolOutput("${f.baseName()} (page ${pages[i] + 1})", bytes, 1, "Page ${pages[i] + 1} · ${sizeText(bytes.size)}", format.extension, format.mime)
            }
        },
    ) { f, enabled ->
        heading("Format")
        item { Choices(listOf(ImageFormat.Jpeg, ImageFormat.Png), format, enabled, title = { it.label }, subtitle = { if (it == ImageFormat.Jpeg) "Smaller files, good for photos" else "Sharpest, larger files" }) { format = it } }
        heading("Sharpness")
        item {
            Choices(listOf(96, 150, 300), dpi, enabled, title = { "$it dpi" }, subtitle = {
                when (it) { 96 -> "For screens and messages"; 150 -> "Good for most uses"; else -> "For printing (large files)" }
            }) { dpi = it }
        }
        heading("Pages")
        pageChoiceItems(choice, f.pages!!, enabled)
    }
}

/**
 * The screen the image tools share: choose one or more images, set the options, and save
 * new images to Download/Docs Manager Pro. [run] turns one image's bytes into the new image.
 */
@Composable
private fun ImageTool(
    title: String,
    intro: String,
    onBack: () -> Unit,
    onOpenResult: (Uri) -> Unit,
    single: Boolean = false,
    suffix: String,
    action: (count: Int) -> String?,
    onFirstImage: (bytes: ByteArray) -> Unit = {},
    run: (bytes: ByteArray) -> Pair<ByteArray, ImageFormat>,
    options: LazyListScope.(images: List<PickedImage>, enabled: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<PickedImage>() }
    var nextId by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SavedResult>?>(null) }

    fun add(uris: List<Uri>) {
        error = null
        if (single) images.clear()
        uris.filter { u -> images.none { it.uri == u } }.forEach { uri ->
            val img = PickedImage(nextId++, uri, DocumentFiles.displayName(context, uri) ?: "Image")
            images += img
            scope.launch {
                val done = describeImage(context, img)
                val i = images.indexOfFirst { it.id == img.id }
                if (i >= 0) images[i] = done
                if (images.firstOrNull()?.id == img.id && !done.error) {
                    withContext(Dispatchers.IO) { runCatching { DocumentFiles.readBytes(context, uri) }.getOrNull() }?.let(onFirstImage)
                }
            }
        }
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> add(listOf(u)) } }
    val pick = { if (single) one.launch(arrayOf("image/*")) else many.launch(arrayOf("image/*")) }
    val saveGate = rememberSaveGate(onDenied = { error = "Docs Manager Pro needs storage access to save to Download." })
    val usable = images.filter { !it.error && it.thumb != null }
    val label = if (usable.isNotEmpty() && usable.size == images.size) action(usable.size) else null

    fun start() = saveGate {
        busy = true
        error = null
        scope.launch {
            try {
                val outputs = mutableListOf<ToolOutput>()
                images.forEachIndexed { i, im ->
                    progress = if (images.size > 1) "Working on ${i + 1} of ${images.size}…" else "Working…"
                    val (bytes, format) = withContext(Dispatchers.Default) { run(DocumentFiles.readBytes(context, im.uri)) }
                    val info = withContext(Dispatchers.Default) { imageOps.info(bytes) }
                    outputs += ToolOutput("${im.name.substringBeforeLast('.')} $suffix".trim(), bytes, 1, "${info.width} × ${info.height} · ${sizeText(bytes.size)}", format.extension, format.mime)
                }
                results = withContext(Dispatchers.IO) { saveOutputs(context, outputs) }
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Check the options and try again."
            } catch (e: OutOfMemoryError) {
                error = "This image is too large for this phone."
            } catch (e: Exception) {
                error = "Couldn't finish. The image may be damaged."
            } finally {
                busy = false
            }
        }
    }

    ToolScaffold(
        title = title,
        onBack = onBack,
        bottomBar = {
            if (results == null && images.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                        Button(onClick = ::start, enabled = label != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(progress)
                            } else {
                                Text(label ?: title)
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
                item { ResultsPanel(done, onOpenResult, onAgain = { images.clear(); results = null }, againLabel = "Use other images") }
                return@LazyColumn
            }
            if (images.isEmpty()) {
                item { PickPrompt(intro, if (single) "Choose an image" else "Choose images", pick) }
                return@LazyColumn
            }
            items(images, key = { it.id }) { im ->
                ImageRow(null, im) {
                    if (single) TextButton(onClick = pick, enabled = !busy) { Text("Change") }
                    else IconButton(onClick = { images.remove(im) }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${im.name}") }
                }
            }
            if (!single) item { OutlinedButton(onClick = pick, enabled = !busy) { Text("Add more images") } }
            if (usable.isNotEmpty()) options(usable, !busy)
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun outputFormatOf(bytes: ByteArray) = AndroidImageOps.outputFormat(bytes)

@Composable
fun CompressImageScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var quality by remember { mutableStateOf(70f) }
    ImageTool(
        title = "Compress image", intro = "Choose JPG, PNG or WebP images to make them smaller, keeping their size in pixels.",
        onBack = onBack, onOpenResult = onOpenResult, suffix = "compressed",
        action = { "Compress ${if (it == 1) "image" else "$it images"}" },
        run = { b ->
            val out = imageOps.compress(b, quality.toInt())
            require(out.size < b.size) { "This image is already well compressed. Try a lower quality." }
            out to AndroidImageOps.outputFormat(out)
        },
    ) { _, enabled ->
        heading("Quality: ${quality.toInt()}%")
        item { Slider(value = quality, onValueChange = { quality = it }, valueRange = 20f..95f, enabled = enabled) }
        item { Text("Lower quality makes smaller files. PNG photos are saved as JPG (or WebP if they have see-through parts).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ResizeImageScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var byPercent by remember { mutableStateOf(true) }
    var percent by remember { mutableStateOf(50f) }
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var keepShape by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf(1f) } // height / width of the first image
    ImageTool(
        title = "Resize image", intro = "Choose images to make them bigger or smaller, by percent or to an exact size.",
        onBack = onBack, onOpenResult = onOpenResult, suffix = "resized",
        onFirstImage = { b ->
            val info = imageOps.info(b)
            ratio = info.height.toFloat() / info.width
            if (width.isEmpty()) { width = info.width.toString(); height = info.height.toString() }
        },
        action = { n ->
            val ok = byPercent || (width.toIntOrNull()?.let { it in 1..12000 } == true && height.toIntOrNull()?.let { it in 1..12000 } == true)
            if (ok) "Resize ${if (n == 1) "image" else "$n images"}" else null
        },
        run = { b ->
            val info = imageOps.info(b)
            val (w, h) = if (byPercent) {
                maxOf(1, (info.width * percent / 100).toInt()) to maxOf(1, (info.height * percent / 100).toInt())
            } else width.toInt() to height.toInt()
            imageOps.resize(b, w, h) to outputFormatOf(b)
        },
    ) { _, enabled ->
        item { Choices(listOf(true, false), byPercent, enabled, title = { if (it) "By percent" else "Exact size in pixels" }) { byPercent = it } }
        if (byPercent) {
            heading("${percent.toInt()}% of the original")
            item { Slider(value = percent, onValueChange = { percent = it }, valueRange = 10f..200f, enabled = enabled) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", width, enabled, Modifier.weight(1f)) { v ->
                        width = v
                        if (keepShape) v.toIntOrNull()?.let { height = maxOf(1, (it * ratio).toInt()).toString() }
                    }
                    NumberField("Height", height, enabled, Modifier.weight(1f)) { v ->
                        height = v
                        if (keepShape) v.toIntOrNull()?.let { width = maxOf(1, (it / ratio).toInt()).toString() }
                    }
                }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = keepShape, onCheckedChange = { keepShape = it }, enabled = enabled)
                    Text("Keep the shape (no stretching)")
                }
            }
        }
    }
}

@Composable
fun RotateImageScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var degrees by remember { mutableStateOf(90) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    ImageTool(
        title = "Rotate image", intro = "Choose images to turn or flip.",
        onBack = onBack, onOpenResult = onOpenResult, suffix = "rotated",
        action = { n -> if (degrees == 0 && !flipH && !flipV) null else "Save ${if (n == 1) "image" else "$n images"}" },
        run = { b -> imageOps.rotate(b, degrees, flipH, flipV) to outputFormatOf(b) },
    ) { _, enabled ->
        heading("Turn")
        item { Choices(listOf(0, 90, 180, 270), degrees, enabled, title = { when (it) { 0 -> "Don't turn"; 90 -> "Right (clockwise)"; 180 -> "Upside down"; else -> "Left (anticlockwise)" } }) { degrees = it } }
        heading("Flip")
        item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(flipH, { flipH = it }, enabled = enabled); Text("Mirror left to right") } }
        item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(flipV, { flipV = it }, enabled = enabled); Text("Mirror top to bottom") } }
    }
}

@Composable
fun WatermarkImageScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var text by remember { mutableStateOf("© My name") }
    var opacity by remember { mutableStateOf(0.5f) }
    var tiled by remember { mutableStateOf(false) }
    ImageTool(
        title = "Watermark image", intro = "Choose photos to stamp text on them, once or repeated across the picture.",
        onBack = onBack, onOpenResult = onOpenResult, suffix = "watermarked",
        action = { n -> if (text.isBlank()) null else "Add watermark to ${if (n == 1) "image" else "$n images"}" },
        run = { b -> imageOps.watermark(b, text.trim(), opacity, tiled) to outputFormatOf(b) },
    ) { _, enabled ->
        heading("Text")
        item { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
        item { Choices(listOf(false, true), tiled, enabled, title = { if (it) "Repeated all over" else "Once, in the middle" }) { tiled = it } }
        heading("Strength: ${(opacity * 100).toInt()}%")
        item { Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.15f..1f, enabled = enabled) }
    }
}

@Composable
fun ConvertImageScreen(heicToJpg: Boolean, onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var format by remember { mutableStateOf(ImageFormat.Jpeg) }
    ImageTool(
        title = if (heicToJpg) "HEIC to JPG" else "Convert image",
        intro = if (heicToJpg) "Choose iPhone photos (HEIC) to save them as JPG pictures that open anywhere." else "Choose images to save them as JPG, PNG or WebP.",
        onBack = onBack, onOpenResult = onOpenResult, suffix = "",
        action = { n -> "Convert ${if (n == 1) "image" else "$n images"} to ${format.label}" },
        run = { b -> imageOps.convert(b, format) to format },
    ) { _, enabled ->
        if (!heicToJpg) {
            heading("Save as")
            item {
                Choices(ImageFormat.entries, format, enabled, title = { it.label }, subtitle = {
                    when (it) { ImageFormat.Jpeg -> "Opens anywhere, best for photos"; ImageFormat.Png -> "Sharp edges and see-through parts"; ImageFormat.Webp -> "Small files for the web" }
                }) { format = it }
            }
        }
    }
}

private enum class CropRatio(val label: String, val ratio: Float?) { Free("Free", null), Square("Square", 1f), FourThree("4:3", 4f / 3f), SixteenNine("16:9", 16f / 9f), ThreeFour("3:4", 3f / 4f) }

@Composable
fun CropImageScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgW by remember { mutableStateOf(0) }
    var imgH by remember { mutableStateOf(0) }
    // The crop box as fractions of the image (0 to 1).
    var box by remember { mutableStateOf(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)) }
    var ratio by remember { mutableStateOf(CropRatio.Free) }

    fun applyRatio(r: CropRatio) {
        ratio = r
        val target = r.ratio ?: return
        // Largest box of that shape centred in the image.
        val imageRatio = imgW.toFloat() / imgH
        val (fw, fh) = if (target > imageRatio) 0.9f to 0.9f * imageRatio / target else 0.9f * target / imageRatio to 0.9f
        box = floatArrayOf(0.5f - fw / 2, 0.5f - fh / 2, 0.5f + fw / 2, 0.5f + fh / 2)
    }

    ImageTool(
        title = "Crop image", intro = "Choose an image, then drag the box to keep just the part you want.",
        onBack = onBack, onOpenResult = onOpenResult, single = true, suffix = "cropped",
        onFirstImage = { b ->
            val info = imageOps.info(b)
            imgW = info.width; imgH = info.height
            preview = thumbnail(b, 1080)?.asImageBitmap()
            box = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f); ratio = CropRatio.Free
        },
        action = { if (preview != null) "Crop to ${pixels(box, imgW, imgH).let { "${it[2]} × ${it[3]}" }}" else null },
        run = { b -> val p = pixels(box, imgW, imgH); imageOps.crop(b, p[0], p[1], p[2], p[3]) to outputFormatOf(b) },
    ) { _, enabled ->
        val pv = preview
        if (pv != null) {
            item { CropBox(pv, box, ratio.ratio?.let { it * imgH / imgW }, enabled) { box = it } }
            heading("Shape")
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CropRatio.entries.forEach { r -> FilterChip(selected = ratio == r, onClick = { applyRatio(r) }, label = { Text(r.label) }, enabled = enabled) }
                }
            }
        }
    }
}

/** The crop box in whole pixels: left, top, width, height. */
private fun pixels(box: FloatArray, w: Int, h: Int): IntArray {
    // Round, don't cut: 0.2 x 600 is 119.99999 in floating point.
    val l = Math.round(box[0] * w).coerceIn(0, w - 1)
    val t = Math.round(box[1] * h).coerceIn(0, h - 1)
    val r = Math.round(box[2] * w).coerceIn(l + 1, w)
    val b = Math.round(box[3] * h).coerceIn(t + 1, h)
    return intArrayOf(l, t, r - l, b - t)
}

/**
 * The picture with a box on it. Drag inside to move the box, drag near a corner to resize it.
 * [shape] keeps the box's width/height in the picture's fractions, or null for any shape.
 */
@Composable
private fun CropBox(image: ImageBitmap, box: FloatArray, shape: Float?, enabled: Boolean, onChange: (FloatArray) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(box)
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
        Image(image, "Image to crop", Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(enabled, shape) {
                if (!enabled) return@pointerInput
                var mode = -1 // 0..3 = corner being dragged (tl, tr, bl, br), 4 = move
                detectDragGestures(
                    onDragStart = { p ->
                        val b = current
                        val corners = listOf(Offset(b[0], b[1]), Offset(b[2], b[1]), Offset(b[0], b[3]), Offset(b[2], b[3]))
                            .map { Offset(it.x * size.width, it.y * size.height) }
                        val near = corners.indexOfFirst { (it - p).getDistance() < 48.dp.toPx() }
                        mode = if (near >= 0) near else 4
                    },
                    onDrag = { change, d ->
                        change.consume()
                        val dx = d.x / size.width; val dy = d.y / size.height
                        val b = current.copyOf()
                        val min = 0.05f
                        when (mode) {
                            4 -> {
                                val w = b[2] - b[0]; val h = b[3] - b[1]
                                b[0] = (b[0] + dx).coerceIn(0f, 1f - w); b[1] = (b[1] + dy).coerceIn(0f, 1f - h)
                                b[2] = b[0] + w; b[3] = b[1] + h
                            }
                            else -> {
                                val left = mode == 0 || mode == 2; val top = mode == 0 || mode == 1
                                if (left) b[0] = (b[0] + dx).coerceIn(0f, b[2] - min) else b[2] = (b[2] + dx).coerceIn(b[0] + min, 1f)
                                if (shape != null) {
                                    val h = (b[2] - b[0]) / shape
                                    if (top) b[1] = (b[3] - h).coerceAtLeast(0f) else b[3] = (b[1] + h).coerceAtMost(1f)
                                    // If the height hit an edge, shrink the width to match.
                                    val realH = b[3] - b[1]; val w = realH * shape
                                    if (left) b[0] = b[2] - w else b[2] = b[0] + w
                                } else {
                                    if (top) b[1] = (b[1] + dy).coerceIn(0f, b[3] - min) else b[3] = (b[3] + dy).coerceIn(b[1] + min, 1f)
                                }
                            }
                        }
                        onChange(b)
                    },
                )
            },
        ) {
            val l = box[0] * size.width; val t = box[1] * size.height
            val r = box[2] * size.width; val bt = box[3] * size.height
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(this.size.width, t))
            drawRect(shade, Offset(0f, bt), Size(this.size.width, this.size.height - bt))
            drawRect(shade, Offset(0f, t), Size(l, bt - t))
            drawRect(shade, Offset(r, t), Size(this.size.width - r, bt - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, bt - t), style = Stroke(2.dp.toPx()))
            val k = 10.dp.toPx()
            listOf(Offset(l, t), Offset(r, t), Offset(l, bt), Offset(r, bt)).forEach { drawCircle(primary, k, it); drawCircle(Color.White, k, it, style = Stroke(2.dp.toPx())) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(5)) }, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = value.toIntOrNull()?.let { it in 1..12000 } != true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
    )
}
