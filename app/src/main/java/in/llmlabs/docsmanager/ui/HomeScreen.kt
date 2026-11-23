package `in`.llmlabs.docsmanager.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.llmlabs.docsmanager.R
import `in`.llmlabs.docsmanager.data.RecentFile

/** MIME types the file picker offers. Office types join when the Office engine lands. */
val OPENABLE_TYPES = arrayOf(
    "application/pdf", "application/epub+zip", "application/oxps", "application/vnd.ms-xpsdocument",
    "application/vnd.comicbook+zip", "application/x-cbz", "image/*",
)

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home), Files("Files", Icons.Outlined.Folder), Tools("Tools", Icons.Outlined.GridView)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recent: List<RecentFile>,
    onOpen: (Uri) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var comingSoon by remember { mutableStateOf<Tool?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onOpen) }
    val openPicker = { picker.launch(OPENABLE_TYPES) }
    val onTool: (Tool) -> Unit = { if (it.ready) openPicker() else comingSoon = it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Docs Manager Pro", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = openPicker, icon = { Icon(Icons.Outlined.FileOpen, null) }, text = { Text("Open file") })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.Home -> HomeTab(recent, onOpen, onRemoveRecent, onTool, openPicker, seeAllTools = { tab = Tab.Tools }, seeAllFiles = { tab = Tab.Files })
                Tab.Files -> FilesTab(recent, onOpen, onRemoveRecent, openPicker)
                Tab.Tools -> ToolsTab(onTool)
            }
        }
    }

    comingSoon?.let { tool ->
        AlertDialog(
            onDismissRequest = { comingSoon = null },
            icon = { Icon(tool.icon, null) },
            title = { Text(tool.title) },
            text = { Text("${tool.subtitle}. This tool is being built and tested now and arrives in an upcoming update.") },
            confirmButton = { TextButton(onClick = { comingSoon = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun HomeTab(
    recent: List<RecentFile>,
    onOpen: (Uri) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onTool: (Tool) -> Unit,
    openPicker: () -> Unit,
    seeAllTools: () -> Unit,
    seeAllFiles: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(80.dp),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { HeroCard(openPicker) }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Quick tools", "All tools", seeAllTools) }
        items(TOOLS.take(8)) { QuickTool(it, onTool) }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Recent", if (recent.size > 5) "See all" else null, seeAllFiles) }
        if (recent.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyRecent() }
        } else {
            recent.take(5).forEach { f -> item(span = { GridItemSpan(maxLineSpan) }) { RecentRow(f, onOpen, onRemove) } }
        }
    }
}

@Composable
private fun HeroCard(openPicker: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4F6DF5), Color(0xFF2B3FB8))))
            .padding(20.dp),
    ) {
        Column {
            Text("All your documents, one app", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Read, organize, convert and sign. Your files stay on your device.", color = Color(0xFFDDE3FF), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Button(onClick = openPicker, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2B3FB8))) {
                Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open a document")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun QuickTool(tool: Tool, onTool: (Tool) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable { onTool(tool) }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(tool.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EmptyRecent() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Files you open will show up here", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentRow(f: RecentFile, onOpen: (Uri) -> Unit, onRemove: (RecentFile) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(Uri.parse(f.uri)) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileBadge(f.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(DateUtils.getRelativeTimeSpanString(f.openedAt).toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove from recent") }, onClick = { menu = false; onRemove(f) }, leadingIcon = { Icon(Icons.Outlined.Close, null) })
                }
            }
        }
    }
}

/** A coloured tile showing the file type, like PDF or EPUB. */
@Composable
fun FileBadge(name: String) {
    val ext = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val color = when (ext) {
        "PDF" -> Color(0xFFE5484D)
        "DOC", "DOCX" -> Color(0xFF2B6CB0)
        "XLS", "XLSX", "CSV" -> Color(0xFF2F855A)
        "PPT", "PPTX" -> Color(0xFFDD6B20)
        "EPUB" -> Color(0xFF805AD5)
        else -> Color(0xFF4F6DF5)
    }
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Text(ext, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilesTab(recent: List<RecentFile>, onOpen: (Uri) -> Unit, onRemove: (RecentFile) -> Unit, openPicker: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedCard(onClick = openPicker, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Browse this device", fontWeight = FontWeight.Medium)
                        Text("Downloads, Drive, SD card and more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionHeader("Recent files", null) {} }
        if (recent.isEmpty()) item { EmptyRecent() }
        items(recent, key = { it.uri }) { RecentRow(it, onOpen, onRemove) }
    }
}

@Composable
private fun ToolsTab(onTool: (Tool) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToolGroup.entries.forEach { group ->
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(group.title, null) {} }
            items(TOOLS.filter { it.group == group }) { ToolCard(it, onTool) }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, onTool: (Tool) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onTool(tool) },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                if (!tool.ready) Text("Soon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(tool.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The app icon, drawn from its two vector layers (Compose cannot draw an adaptive icon directly). */
@Composable
fun AppLogo(size: androidx.compose.ui.unit.Dp) {
    // Adaptive icon layers are 108dp with the visible part in the middle 72dp.
    val layer = size * (108f / 72f)
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(layer))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(layer))
    }
}
