package `in`.llmlabs.docsmanager.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolGroup(val title: String) { View("View & read"), Organize("Organize"), Optimize("Optimize & secure"), Edit("Edit & sign"), Convert("Convert"), Ai("AI") }

/** A Tool as on llmlabs.in. [ready] is false until it is built and tested in the app. */
/** Tool screens that are built. A Tool without one is either "Open & read" (the file picker) or not ready yet. */
enum class ToolScreen { Merge, Split }

data class Tool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val group: ToolGroup,
    val ready: Boolean = false,
    val screen: ToolScreen? = null,
)

val TOOLS = listOf(
    Tool("Open & read", "PDF, EPUB, XPS, comics, images", Icons.Outlined.MenuBook, ToolGroup.View, ready = true),
    Tool("Office files", "Word, Excel, PowerPoint", Icons.Outlined.Description, ToolGroup.View),
    Tool("Merge PDF", "Join several PDFs into one", Icons.Outlined.MergeType, ToolGroup.Organize, ready = true, screen = ToolScreen.Merge),
    Tool("Split PDF", "Break a PDF into parts", Icons.Outlined.CallSplit, ToolGroup.Organize, ready = true, screen = ToolScreen.Split),
    Tool("Organize pages", "Reorder, rotate, delete", Icons.Outlined.ViewModule, ToolGroup.Organize),
    Tool("Rotate PDF", "Turn pages the right way", Icons.Outlined.RotateRight, ToolGroup.Organize),
    Tool("Extract pages", "Save chosen pages", Icons.Outlined.FileCopy, ToolGroup.Organize),
    Tool("Remove pages", "Delete pages you don't need", Icons.Outlined.Delete, ToolGroup.Organize),
    Tool("Compress PDF", "Make the file smaller", Icons.Outlined.Compress, ToolGroup.Optimize),
    Tool("Protect PDF", "Add a password", Icons.Outlined.Lock, ToolGroup.Optimize),
    Tool("Unlock PDF", "Remove a password", Icons.Outlined.LockOpen, ToolGroup.Optimize),
    Tool("Repair PDF", "Fix damaged files", Icons.Outlined.Build, ToolGroup.Optimize),
    Tool("Edit metadata", "Title, author, keywords", Icons.Outlined.Info, ToolGroup.Optimize),
    Tool("Comment & highlight", "Mark up any page", Icons.Outlined.BorderColor, ToolGroup.Edit),
    Tool("Fill & sign", "Forms and signatures", Icons.Outlined.Draw, ToolGroup.Edit),
    Tool("Edit text", "Change words in a PDF", Icons.Outlined.EditNote, ToolGroup.Edit),
    Tool("Redact", "Black out private details", Icons.Outlined.HideSource, ToolGroup.Edit),
    Tool("Watermark", "Stamp text on pages", Icons.Outlined.WaterDrop, ToolGroup.Edit),
    Tool("Page numbers", "Number every page", Icons.Outlined.FormatListNumbered, ToolGroup.Edit),
    Tool("Crop PDF", "Trim page margins", Icons.Outlined.Crop, ToolGroup.Edit),
    Tool("Compare PDF", "Spot the differences", Icons.Outlined.Compare, ToolGroup.Edit),
    Tool("Scan to PDF", "Use the camera", Icons.Outlined.DocumentScanner, ToolGroup.Convert),
    Tool("Images to PDF", "Photos into one PDF", Icons.Outlined.Image, ToolGroup.Convert),
    Tool("PDF to images", "Save pages as pictures", Icons.Outlined.Photo, ToolGroup.Convert),
    Tool("OCR PDF", "Make scans searchable", Icons.Outlined.TextFields, ToolGroup.Convert),
    Tool("PDF to Word", "Editable document", Icons.Outlined.Article, ToolGroup.Convert),
    Tool("PDF to Excel", "Tables into a sheet", Icons.Outlined.TableChart, ToolGroup.Convert),
    Tool("PDF to PowerPoint", "Pages into slides", Icons.Outlined.Slideshow, ToolGroup.Convert),
    Tool("Office to PDF", "Word, Excel, PowerPoint", Icons.Outlined.PictureAsPdf, ToolGroup.Convert),
    Tool("New document", "Create Word, Excel, PowerPoint", Icons.Outlined.NoteAdd, ToolGroup.Convert),
    Tool("AI Summary", "Key points in seconds (uses server)", Icons.Outlined.AutoAwesome, ToolGroup.Ai),
    Tool("Ask your document", "Questions and answers (uses server)", Icons.Outlined.QuestionAnswer, ToolGroup.Ai),
)
