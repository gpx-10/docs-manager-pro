package `in`.llmlabs.docsmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A section of a hub: a heading and the tools under it, by title. */
private class HubSection(val title: String, val tools: List<String>)

private val EDIT_SECTIONS = listOf(
    HubSection("Edit & sign", listOf("Sign PDF", "Edit text", "Comment & highlight", "Redact PDF", "Watermark", "Page numbers", "Crop PDF")),
    HubSection("Pages", listOf("Organize pages", "Merge PDF", "Split PDF", "Rotate PDF", "Extract pages", "Remove pages")),
    HubSection("Secure & optimize", listOf("Compress PDF", "Protect PDF", "Unlock PDF", "Edit metadata", "Repair PDF", "Compare PDF")),
)

private val CONVERT_SECTIONS = listOf(
    HubSection("From PDF", listOf("PDF to Word", "PDF to Excel", "PDF to PowerPoint", "PDF to images", "PDF to PDF/A")),
    HubSection("To PDF", listOf("Office to PDF", "Images to PDF", "Scan to PDF", "OCR PDF")),
    HubSection("Create", listOf("New document")),
    HubSection("Images", listOf("Compress image", "Resize image", "Crop image", "Rotate image", "Watermark image", "Convert image", "HEIC to JPG")),
)

@Composable
fun EditHubScreen(onBack: () -> Unit, onOpenTool: (ToolScreen) -> Unit) =
    Hub("Edit PDF", "Sign, change and mark up PDFs, and work with their pages.", EDIT_SECTIONS, onBack, onOpenTool)

@Composable
fun ConvertHubScreen(onBack: () -> Unit, onOpenTool: (ToolScreen) -> Unit) =
    Hub("Convert", "Turn PDFs into Office files and images, and back again.", CONVERT_SECTIONS, onBack, onOpenTool)

@Composable
private fun Hub(title: String, intro: String, sections: List<HubSection>, onBack: () -> Unit, onOpenTool: (ToolScreen) -> Unit) {
    ToolScaffold(title = title, onBack = onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(intro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
            for (s in sections) {
                val tools = s.tools.mapNotNull { name -> TOOLS.firstOrNull { it.title == name && it.screen != null } }
                if (tools.isEmpty()) continue
                item { Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            tools.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                ToolRow(t) { onOpenTool(t.screen!!) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: Tool, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, fontWeight = FontWeight.Medium)
            Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
