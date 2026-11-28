package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.CompressLevel
import `in`.llmlabs.docsmanager.Margins
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.NumberPosition
import `in`.llmlabs.docsmanager.PdfInfo
import `in`.llmlabs.docsmanager.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ops: PdfOps = MuPdfOps()


@Composable
fun RotateScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var degrees by remember { mutableStateOf(90) }
    val choice = remember { PageChoice() }
    SinglePdfTool(
        title = "Rotate PDF", intro = "Choose a PDF to turn all its pages, or just some of them.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Rotate another",
        action = { f -> choice.pages(f.pages!!).getOrNull()?.let { "Rotate ${pagesText(it.size)}" } },
        run = { f ->
            val pages = choice.pages(f.pages!!).getOrThrow()
            listOf(ToolOutput("${f.baseName()} rotated", ops.rotate(f.bytes!!, pages, degrees), f.pages))
        },
    ) { f, enabled ->
        heading("Turn")
        item {
            Choices(listOf(90, 180, 270), degrees, enabled, title = {
                when (it) { 90 -> "Right (clockwise)"; 180 -> "Upside down"; else -> "Left (anticlockwise)" }
            }) { degrees = it }
        }
        heading("Pages")
        pageChoiceItems(choice, f.pages!!, enabled)
    }
}

@Composable
fun RemovePagesScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var spec by remember { mutableStateOf("") }
    fun plan(count: Int) = runCatching {
        val drop = `in`.llmlabs.docsmanager.parsePageRanges(spec, count).flatMap { it.toList() }.toSet()
        require(drop.size < count) { "You can't remove every page" }
        drop
    }
    SinglePdfTool(
        title = "Remove pages", intro = "Choose a PDF, then type the pages to delete. Your original stays as it is.",
        onBack = onBack, onOpenResult = onOpenResult,
        action = { f -> plan(f.pages!!).getOrNull()?.let { "Remove ${pagesText(it.size)}" } },
        run = { f ->
            val drop = plan(f.pages!!).getOrThrow()
            val keep = (0 until f.pages).filter { it !in drop }
            listOf(ToolOutput("${f.baseName()} (pages removed)", ops.organize(f.bytes!!, keep), keep.size))
        },
    ) { f, enabled ->
        heading("Pages to remove")
        item { PageSpecField(spec, f.pages!!, enabled, plan(f.pages).exceptionOrNull()?.message) { spec = it } }
    }
}

@Composable
fun ExtractPagesScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var spec by remember { mutableStateOf("") }
    fun plan(count: Int) = runCatching { `in`.llmlabs.docsmanager.parsePageRanges(spec, count).flatMap { it.toList() }.distinct() }
    SinglePdfTool(
        title = "Extract pages", intro = "Choose a PDF, then type the pages to copy into a new PDF.",
        onBack = onBack, onOpenResult = onOpenResult,
        action = { f -> plan(f.pages!!).getOrNull()?.let { "Extract ${pagesText(it.size)}" } },
        run = { f ->
            val pages = plan(f.pages!!).getOrThrow()
            listOf(ToolOutput("${f.baseName()} (pages ${spec.replace(" ", "")})", ops.organize(f.bytes!!, pages), pages.size))
        },
    ) { f, enabled ->
        heading("Pages to extract")
        item { PageSpecField(spec, f.pages!!, enabled, plan(f.pages).exceptionOrNull()?.message) { spec = it } }
    }
}

@Composable
fun CompressScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var level by remember { mutableStateOf(CompressLevel.Recommended) }
    SinglePdfTool(
        title = "Compress PDF", intro = "Choose a PDF to make it smaller, for email or upload limits.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Compress another",
        action = { "Compress" },
        run = { f ->
            val before = f.bytes!!.size
            val result = ops.compress(f.bytes, level)
            require(result.size < before * 0.97) {
                if (level == CompressLevel.Strong) "This PDF is already about as small as it can be."
                else "This PDF is already compact at this level. Try ${if (level == CompressLevel.Light) "Recommended" else "Strong"}."
            }
            val saved = 100 - result.size * 100 / before
            listOf(ToolOutput("${f.baseName()} compressed", result, f.pages!!, "${sizeText(before)} → ${sizeText(result.size)} · $saved% smaller"))
        },
    ) { f, enabled ->
        item { Text("Now ${sizeText(f.bytes!!.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        heading("Level")
        item { Choices(CompressLevel.entries, level, enabled, title = { it.label }, subtitle = { it.detail }) { level = it } }
    }
}

@Composable
fun RepairScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    SinglePdfTool(
        title = "Repair PDF", intro = "Choose a PDF that won't open properly elsewhere. The app rebuilds it and keeps every page it can read.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Repair another",
        action = { "Repair" },
        run = { f -> listOf(ToolOutput("${f.baseName()} repaired", ops.repair(f.bytes!!), f.pages!!)) },
    ) { _, _ -> }
}

@Composable
fun ProtectScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    fun problem(): String? = when {
        password.isEmpty() -> null
        ',' in password -> "Passwords can't contain commas"
        password.length < 4 -> "Use at least 4 characters"
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match"
        else -> null
    }
    SinglePdfTool(
        title = "Protect PDF", intro = "Choose a PDF to lock with a password. Anyone opening it will need the password.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Protect another",
        action = { if (password.length >= 4 && confirm == password && problem() == null) "Protect with password" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} protected", ops.protect(f.bytes!!, password), f.pages!!, "Needs the password to open")) },
    ) { _, enabled ->
        item { PasswordField(password, "New password", enabled, problem()) { password = it } }
        item { PasswordField(confirm, "Type it again", enabled, null) { confirm = it } }
        item { Text("Keep the password safe. It can't be recovered if you forget it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun UnlockScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var password by remember { mutableStateOf("") }
    SinglePdfTool(
        title = "Unlock PDF", intro = "Choose a password-protected PDF and enter its password to save a copy that opens without one.",
        onBack = onBack, onOpenResult = onOpenResult, acceptLocked = true, againLabel = "Unlock another",
        action = { f -> if (!f.locked) null else if (password.isNotEmpty()) "Unlock" else null },
        run = { f ->
            val result = ops.unlock(f.bytes!!, password)
            val pages = `in`.llmlabs.docsmanager.MuPdfEngine().open(result, "unlocked.pdf").use { it.pageCount }
            listOf(ToolOutput("${f.baseName()} unlocked", result, pages, "Opens without a password"))
        },
    ) { f, enabled ->
        if (!f.locked) {
            item { Text("This PDF has no password, so there's nothing to unlock.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item { PasswordField(password, "Password of this PDF", enabled, null) { password = it } }
        }
    }
}

@Composable
fun MetadataScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(PdfInfo()) }
    var loaded by remember { mutableStateOf(false) }
    SinglePdfTool(
        title = "Edit metadata", intro = "Choose a PDF to change the title, author, subject and keywords shown in its document properties.",
        onBack = onBack, onOpenResult = onOpenResult,
        onPicked = { f ->
            loaded = false
            scope.launch { info = withContext(Dispatchers.Default) { runCatching { ops.metadata(f.bytes!!) }.getOrDefault(PdfInfo()) }; loaded = true }
        },
        action = { if (loaded) "Save details" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} edited", ops.setMetadata(f.bytes!!, info), f.pages!!, info.title.ifBlank { null })) },
    ) { _, enabled ->
        heading("Details")
        item { MetaField("Title", info.title, enabled && loaded) { info = info.copy(title = it) } }
        item { MetaField("Author", info.author, enabled && loaded) { info = info.copy(author = it) } }
        item { MetaField("Subject", info.subject, enabled && loaded) { info = info.copy(subject = it) } }
        item { MetaField("Keywords", info.keywords, enabled && loaded) { info = info.copy(keywords = it) } }
        item { Text("Leave a field empty to remove it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private enum class NumberStyle(val format: String) { Plain("{n}"), Word("Page {n}"), WordOf("Page {n} of {total}"), Slash("{n} / {total}") }

@Composable
fun PageNumbersScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var style by remember { mutableStateOf(NumberStyle.WordOf) }
    var position by remember { mutableStateOf(NumberPosition.BottomCenter) }
    var start by remember { mutableStateOf("1") }
    SinglePdfTool(
        title = "Page numbers", intro = "Choose a PDF to number every page.",
        onBack = onBack, onOpenResult = onOpenResult,
        action = { if (start.toIntOrNull()?.let { it in 0..99999 } == true) "Add page numbers" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} numbered", ops.addPageNumbers(f.bytes!!, style.format, position, start.toInt()), f.pages!!)) },
    ) { f, enabled ->
        val first = start.toIntOrNull() ?: 1
        heading("Style")
        item {
            Choices(NumberStyle.entries, style, enabled, title = {
                it.format.replace("{n}", first.toString()).replace("{total}", (first + f.pages!! - 1).toString())
            }) { style = it }
        }
        heading("Position")
        item { Choices(NumberPosition.entries, position, enabled, title = { it.label }) { position = it } }
        item {
            OutlinedTextField(
                value = start, onValueChange = { start = it.filter(Char::isDigit).take(5) }, label = { Text("Start at") },
                singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun WatermarkScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableStateOf(0.3f) }
    SinglePdfTool(
        title = "Watermark", intro = "Choose a PDF to stamp text across every page, like CONFIDENTIAL or DRAFT.",
        onBack = onBack, onOpenResult = onOpenResult,
        action = { if (text.isNotBlank()) "Add watermark" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} watermarked", ops.watermark(f.bytes!!, text.trim(), opacity), f.pages!!)) },
    ) { _, enabled ->
        heading("Text")
        item {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(60) }, singleLine = true, enabled = enabled,
                supportingText = { Text("Letters, numbers and common symbols") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        heading("See-through: ${(100 - opacity * 100).toInt()}%")
        item { Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.1f..0.9f, enabled = enabled) }
    }
}

@Composable
fun CropScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit) {
    var top by remember { mutableStateOf("10") }
    var right by remember { mutableStateOf("10") }
    var bottom by remember { mutableStateOf("10") }
    var left by remember { mutableStateOf("10") }
    fun margins(): Margins? {
        val mm = listOf(top, right, bottom, left).map { it.toFloatOrNull() ?: return null }
        if (mm.any { it < 0 || it > 300 }) return null
        val pt = mm.map { it / 25.4f * 72f }
        return Margins(pt[0], pt[1], pt[2], pt[3])
    }
    SinglePdfTool(
        title = "Crop PDF", intro = "Choose a PDF to trim the margins from every page.",
        onBack = onBack, onOpenResult = onOpenResult,
        action = { if (margins() != null) "Crop pages" else null },
        run = { f -> listOf(ToolOutput("${f.baseName()} cropped", ops.crop(f.bytes!!, margins()!!), f.pages!!)) },
    ) { _, enabled ->
        heading("Trim from each edge (mm)")
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MmField("Top", top, enabled, Modifier.weight(1f)) { top = it }
                MmField("Bottom", bottom, enabled, Modifier.weight(1f)) { bottom = it }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MmField("Left", left, enabled, Modifier.weight(1f)) { left = it }
                MmField("Right", right, enabled, Modifier.weight(1f)) { right = it }
            }
        }
        item { Text("10 mm is about the width of a fingernail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MmField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) }, label = { Text(label) },
        singleLine = true, enabled = enabled, isError = value.toFloatOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun MetaField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PasswordField(value: String, label: String, enabled: Boolean, problem: String?, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = problem != null, supportingText = problem?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) "Hide password" else "Show password")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
