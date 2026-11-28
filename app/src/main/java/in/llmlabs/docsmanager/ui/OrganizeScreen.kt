package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.MuPdfEngine
import `in`.llmlabs.docsmanager.MuPdfOps
import `in`.llmlabs.docsmanager.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One page in the new order: a page of the original ([source], 0-based) or a blank page (null), with an extra turn. */
private data class Slot(val id: Int, val source: Int?, val turn: Int = 0)

/** Organize pages: reorder, turn, delete and add blank pages, seeing each page as you go. */
@Composable
fun OrganizeScreen(onBack: () -> Unit, onOpenResult: (Uri) -> Unit, ops: PdfOps = remember { MuPdfOps() }) {
    val slots = remember { mutableStateListOf<Slot>() }
    var nextId by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }
    val thumbs = remember { mutableStateMapOf<Int, ImageBitmap>() }

    // Draw small pictures of each page, one at a time, once a PDF is chosen.
    LaunchedEffect(picked) {
        val f = picked ?: return@LaunchedEffect
        thumbs.clear()
        withContext(Dispatchers.IO) {
            runCatching {
                MuPdfEngine().open(f.bytes!!, f.name).use { doc ->
                    for (i in 0 until doc.pageCount) thumbs[i] = doc.renderPage(i, 180).asImageBitmap()
                }
            }
        }
    }

    SinglePdfTool(
        title = "Organize pages", intro = "Choose a PDF to put its pages in a new order, turn them, delete some, or add blank pages.",
        onBack = onBack, onOpenResult = onOpenResult, againLabel = "Organize another",
        onPicked = { f ->
            slots.clear()
            repeat(f.pages!!) { slots += Slot(it, it) }
            nextId = f.pages
            picked = f
        },
        action = { f -> if (slots.isEmpty()) null else if (unchanged(slots, f.pages!!)) null else "Save ${pagesText(slots.size)}" },
        run = { f ->
            var result = ops.organize(f.bytes!!, slots.map { it.source })
            // Then turn the pages that need it, grouped by how far they turn.
            slots.withIndex().filter { it.value.turn != 0 }.groupBy { it.value.turn }.forEach { (turn, group) ->
                result = ops.rotate(result, group.map { it.index }.toSet(), turn)
            }
            listOf(ToolOutput("${f.baseName()} organized", result, slots.size))
        },
    ) { _, enabled ->
        item {
            Text(
                "Use the arrows to move pages. Changes are saved to a new PDF; your original stays as it is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(slots, key = { _, s -> s.id }) { i, s ->
            PageRow(
                position = i + 1,
                slot = s,
                thumb = s.source?.let { thumbs[it] },
                enabled = enabled,
                canUp = i > 0, canDown = i < slots.lastIndex, canDelete = slots.size > 1,
                onUp = { slots.add(i - 1, slots.removeAt(i)) },
                onDown = { slots.add(i + 1, slots.removeAt(i)) },
                onTurn = { slots[i] = s.copy(turn = (s.turn + 90) % 360) },
                onDelete = { slots.removeAt(i) },
            )
        }
        item {
            OutlinedButton(onClick = { slots += Slot(nextId++, null) }, enabled = enabled) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a blank page at the end")
            }
        }
    }
}

private fun unchanged(slots: List<Slot>, pageCount: Int) =
    slots.size == pageCount && slots.withIndex().all { (i, s) -> s.source == i && s.turn == 0 }

@Composable
private fun PageRow(
    position: Int, slot: Slot, thumb: ImageBitmap?, enabled: Boolean,
    canUp: Boolean, canDown: Boolean, canDelete: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onTurn: () -> Unit, onDelete: () -> Unit,
) {
    val name = slot.source?.let { "page ${it + 1}" } ?: "blank page"
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(4.dp)
                val m = Modifier.rotate(slot.turn.toFloat()).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                when {
                    thumb != null -> Image(thumb, null, m.heightIn(max = 64.dp).widthIn(max = 64.dp))
                    slot.source == null -> Box(m.size(44.dp, 60.dp).background(Color.White))
                    else -> Box(m.size(44.dp, 60.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$position", fontWeight = FontWeight.SemiBold)
                Text(
                    name.replaceFirstChar { it.uppercase() } + if (slot.turn != 0) " · turned ${slot.turn}°" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUp, enabled = enabled && canUp) { Icon(Icons.Outlined.ArrowUpward, "Move $name up") }
            IconButton(onClick = onDown, enabled = enabled && canDown) { Icon(Icons.Outlined.ArrowDownward, "Move $name down") }
            IconButton(onClick = onTurn, enabled = enabled) { Icon(Icons.Outlined.RotateRight, "Turn $name") }
            IconButton(onClick = onDelete, enabled = enabled && canDelete) { Icon(Icons.Outlined.Delete, "Delete $name") }
        }
    }
}
