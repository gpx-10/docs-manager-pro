package `in`.llmlabs.docsmanager.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolGroup(val title: String) { View("View & read"), Organize("Organize"), Optimize("Optimize & secure"), Edit("Edit & sign"), Convert("Convert"), Image("Image tools"), Ai("AI") }

/** A Tool as on llmlabs.in. [ready] is false until it is built and tested in the app. */
/** Tool screens that are built. A Tool without one is either "Open & read" (the file picker) or not ready yet. */
enum class ToolScreen { Editor, EditHub, ConvertHub, Merge, Split, Organize, Rotate, Extract, Remove, Compress, Protect, Unlock, Repair, Metadata, Watermark, PageNumbers, Crop, ImagesToPdf, Scan, PdfToImages, CompressImage, ResizeImage, CropImage, RotateImage, WatermarkImage, ConvertImage, HeicToJpg, Sign, Redact, EditText, Annotate, Compare, Ocr, OfficeToPdf, PdfToWord, PdfToExcel, PdfToPowerPoint, PdfA, NewDocument }

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
    Tool("Office files", "Read Word, Excel, PowerPoint", Icons.Outlined.Description, ToolGroup.View, ready = true),
    Tool("Merge PDF", "Join several PDFs into one", Icons.Outlined.MergeType, ToolGroup.Organize, ready = true, screen = ToolScreen.Merge),
    Tool("Split PDF", "Break a PDF into parts", Icons.Outlined.CallSplit, ToolGroup.Organize, ready = true, screen = ToolScreen.Split),
    Tool("Organize pages", "Reorder, turn, delete, add", Icons.Outlined.ViewModule, ToolGroup.Organize, ready = true, screen = ToolScreen.Organize),
    Tool("Rotate PDF", "Turn pages the right way", Icons.Outlined.RotateRight, ToolGroup.Organize, ready = true, screen = ToolScreen.Rotate),
    Tool("Extract pages", "Save chosen pages", Icons.Outlined.FileCopy, ToolGroup.Organize, ready = true, screen = ToolScreen.Extract),
    Tool("Remove pages", "Delete pages you don't need", Icons.Outlined.Delete, ToolGroup.Organize, ready = true, screen = ToolScreen.Remove),
    Tool("Compress PDF", "Make the file smaller", Icons.Outlined.Compress, ToolGroup.Optimize, ready = true, screen = ToolScreen.Compress),
    Tool("Protect PDF", "Add a password", Icons.Outlined.Lock, ToolGroup.Optimize, ready = true, screen = ToolScreen.Protect),
    Tool("Unlock PDF", "Remove a password", Icons.Outlined.LockOpen, ToolGroup.Optimize, ready = true, screen = ToolScreen.Unlock),
    Tool("Repair PDF", "Fix damaged files", Icons.Outlined.Build, ToolGroup.Optimize, ready = true, screen = ToolScreen.Repair),
    Tool("Edit metadata", "Title, author, keywords", Icons.Outlined.Info, ToolGroup.Optimize, ready = true, screen = ToolScreen.Metadata),
    Tool("PDF to PDF/A", "For long-term archiving", Icons.Outlined.Archive, ToolGroup.Optimize, ready = true, screen = ToolScreen.PdfA),
    Tool("Comment & highlight", "Mark up any page", Icons.Outlined.BorderColor, ToolGroup.Edit, ready = true, screen = ToolScreen.Annotate),
    Tool("Sign PDF", "Draw or type your signature", Icons.Outlined.Draw, ToolGroup.Edit, ready = true, screen = ToolScreen.Sign),
    Tool("Edit text", "Change words in a PDF", Icons.Outlined.EditNote, ToolGroup.Edit, ready = true, screen = ToolScreen.EditText),
    Tool("Redact PDF", "Remove private details for good", Icons.Outlined.HideSource, ToolGroup.Edit, ready = true, screen = ToolScreen.Redact),
    Tool("Watermark", "Stamp text on pages", Icons.Outlined.WaterDrop, ToolGroup.Edit, ready = true, screen = ToolScreen.Watermark),
    Tool("Page numbers", "Number every page", Icons.Outlined.FormatListNumbered, ToolGroup.Edit, ready = true, screen = ToolScreen.PageNumbers),
    Tool("Crop PDF", "Trim page margins", Icons.Outlined.Crop, ToolGroup.Edit, ready = true, screen = ToolScreen.Crop),
    Tool("Compare PDF", "Spot the differences", Icons.Outlined.Compare, ToolGroup.Edit, ready = true, screen = ToolScreen.Compare),
    Tool("Scan to PDF", "Use the camera", Icons.Outlined.DocumentScanner, ToolGroup.Convert, ready = true, screen = ToolScreen.Scan),
    Tool("Images to PDF", "Photos into one PDF", Icons.Outlined.Image, ToolGroup.Convert, ready = true, screen = ToolScreen.ImagesToPdf),
    Tool("PDF to images", "Save pages as pictures", Icons.Outlined.Photo, ToolGroup.Convert, ready = true, screen = ToolScreen.PdfToImages),
    Tool("Compress image", "Make JPG, PNG, WebP smaller", Icons.Outlined.PhotoSizeSelectSmall, ToolGroup.Image, ready = true, screen = ToolScreen.CompressImage),
    Tool("Resize image", "By pixels or percent", Icons.Outlined.AspectRatio, ToolGroup.Image, ready = true, screen = ToolScreen.ResizeImage),
    Tool("Crop image", "Drag a box or pick a shape", Icons.Outlined.CropFree, ToolGroup.Image, ready = true, screen = ToolScreen.CropImage),
    Tool("Rotate image", "Turn or flip pictures", Icons.Outlined.Rotate90DegreesCw, ToolGroup.Image, ready = true, screen = ToolScreen.RotateImage),
    Tool("Watermark image", "Stamp text on photos", Icons.Outlined.BrandingWatermark, ToolGroup.Image, ready = true, screen = ToolScreen.WatermarkImage),
    Tool("Convert image", "JPG, PNG and WebP", Icons.Outlined.Transform, ToolGroup.Image, ready = true, screen = ToolScreen.ConvertImage),
    Tool("HEIC to JPG", "Open iPhone photos anywhere", Icons.Outlined.PhoneIphone, ToolGroup.Image, ready = true, screen = ToolScreen.HeicToJpg),
    Tool("OCR PDF", "Make scans searchable", Icons.Outlined.TextFields, ToolGroup.Convert, ready = true, screen = ToolScreen.Ocr),
    Tool("PDF to Word", "Editable document", Icons.Outlined.Article, ToolGroup.Convert, ready = true, screen = ToolScreen.PdfToWord),
    Tool("PDF to Excel", "Tables into a sheet", Icons.Outlined.TableChart, ToolGroup.Convert, ready = true, screen = ToolScreen.PdfToExcel),
    Tool("PDF to PowerPoint", "Pages into slides", Icons.Outlined.Slideshow, ToolGroup.Convert, ready = true, screen = ToolScreen.PdfToPowerPoint),
    Tool("Office to PDF", "Word, Excel, PowerPoint, HTML", Icons.Outlined.PictureAsPdf, ToolGroup.Convert, ready = true, screen = ToolScreen.OfficeToPdf),
    Tool("New document", "Create Word, Excel, PowerPoint", Icons.Outlined.NoteAdd, ToolGroup.Convert, ready = true, screen = ToolScreen.NewDocument),
)
