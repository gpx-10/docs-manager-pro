package `in`.llmlabs.docsmanager.office

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Writers for Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files, ported from llmlabs.in
 * (src/lib/office). An Office file is a zip of XML parts.
 */

internal const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
private const val EMU_PER_PT = 12700

/** Escapes text for XML and drops characters XML 1.0 forbids (they can come out of PDFs). */
internal fun esc(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
        '\t', '\n', '\r' -> append(ch)
        else -> if (ch.code < 0x20 || ch == '￾' || ch == '￿') Unit else append(ch)
    }
}

/** Builds an Office package from part names and contents (strings are written as UTF-8). */
internal fun pack(parts: Map<String, Any>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        // [Content_Types].xml first, as Office expects.
        for ((name, v) in parts.entries.sortedBy { if (it.key == "[Content_Types].xml") 0 else 1 }) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(if (v is String) v.toByteArray(Charsets.UTF_8) else v as ByteArray)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/* ---------- Word ---------- */

class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, /** Points. */ val size: Float? = null)

sealed interface DocBlock {
    class Paragraph(val runs: List<Run>, val heading: Int? = null, val align: String? = null) : DocBlock
    class Picture(val bytes: ByteArray, val png: Boolean, /** Points. */ val width: Float, val height: Float) : DocBlock
    object PageBreak : DocBlock
}

private const val W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
/** Usable width of an A4 page with 2 cm margins, in points. */
private const val MAX_IMAGE_WIDTH = 481f

fun writeDocx(blocks: List<DocBlock>, title: String = "Document"): ByteArray {
    val media = linkedMapOf<String, Any>()
    val rels = mutableListOf("<Relationship Id=\"rStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
    var images = 0
    val body = blocks.joinToString("") { b ->
        when (b) {
            DocBlock.PageBreak -> "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
            is DocBlock.Picture -> {
                images++
                val name = "image$images.${if (b.png) "png" else "jpg"}"
                media["word/media/$name"] = b.bytes
                rels += "<Relationship Id=\"rImg$images\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>"
                val scale = minOf(1f, MAX_IMAGE_WIDTH / b.width)
                val cx = Math.round(b.width * scale * EMU_PER_PT); val cy = Math.round(b.height * scale * EMU_PER_PT)
                "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$images\" name=\"Picture $images\"/>" +
                    "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
                    "<pic:nvPicPr><pic:cNvPr id=\"$images\" name=\"Picture $images\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                    "<pic:blipFill><a:blip r:embed=\"rImg$images\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                    "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                    "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
            }
            is DocBlock.Paragraph -> {
                val props = listOfNotNull(
                    b.heading?.let { "<w:pStyle w:val=\"Heading$it\"/>" },
                    b.align?.takeIf { it != "left" }?.let { "<w:jc w:val=\"${if (it == "center") "center" else "right"}\"/>" },
                ).joinToString("")
                "<w:p>${if (props.isNotEmpty()) "<w:pPr>$props</w:pPr>" else ""}${b.runs.joinToString("") { run(it) }}</w:p>"
            }
        }
    }
    fun heading(n: Int, size: Int) =
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"${size * 2}\"/></w:rPr></w:style>"
    return pack(linkedMapOf<String, Any>(
        "[Content_Types].xml" to XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
        "_rels/.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"r2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
        "docProps/core.xml" to XML_HEAD +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(title)}</dc:title><dc:creator>Docs Manager Pro</dc:creator></cp:coreProperties>",
        "word/_rels/document.xml.rels" to XML_HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">${rels.joinToString("")}</Relationships>",
        "word/styles.xml" to XML_HEAD +
            "<w:styles $W_NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            heading(1, 20) + heading(2, 16) + heading(3, 13) + "</w:styles>",
        "word/document.xml" to XML_HEAD +
            "<w:document $W_NS><w:body>${body.ifEmpty { "<w:p/>" }}" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>",
    ).apply { putAll(media) })
}

private fun run(r: Run): String {
    val props = listOfNotNull(if (r.bold) "<w:b/>" else null, if (r.italic) "<w:i/>" else null, r.size?.let { "<w:sz w:val=\"${Math.round(it * 2)}\"/>" }).joinToString("")
    return "<w:r>${if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(r.text)}</w:t></w:r>"
}

/* ---------- Excel ---------- */

/** A cell holds text (String) or a number (Double); "" is empty. */
class Sheet(val name: String, val rows: List<List<Any>>)

fun columnName(col: Int): String {
    var s = ""
    var n = col + 1
    while (n > 0) { s = ('A' + (n - 1) % 26) + s; n = (n - 1) / 26 }
    return s
}

/** Excel sheet names: at most 31 characters, no []:*?/\ and unique. */
private fun sheetNames(sheets: List<Sheet>): List<String> {
    val used = mutableSetOf<String>()
    return sheets.mapIndexed { i, s ->
        val base = s.name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Sheet${i + 1}" }.take(31)
        var name = base
        var n = 2
        while (name.lowercase() in used) name = "${base.take(28)} ${n++}"
        used += name.lowercase()
        name
    }
}

fun writeXlsx(input: List<Sheet>): ByteArray {
    val sheets = input.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
    val names = sheetNames(sheets)
    // Text goes in the shared string table, as Excel itself writes it (some readers ignore inline text).
    val strings = linkedMapOf<String, Int>()
    val parts = linkedMapOf<String, Any>()
    sheets.forEachIndexed { i, s ->
        val rows = s.rows.mapIndexed { r, row ->
            val cells = row.mapIndexed { c, v ->
                val ref = columnName(c) + (r + 1)
                when {
                    v is Number && v.toDouble().isFinite() -> "<c r=\"$ref\"><v>${numberText(v.toDouble())}</v></c>"
                    v.toString().isEmpty() -> ""
                    else -> "<c r=\"$ref\" t=\"s\"><v>${strings.getOrPut(v.toString()) { strings.size }}</v></c>"
                }
            }.joinToString("")
            "<row r=\"${r + 1}\">$cells</row>"
        }.joinToString("")
        parts["xl/worksheets/sheet${i + 1}.xml"] = XML_HEAD +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>"
    }
    parts["xl/sharedStrings.xml"] = XML_HEAD +
        "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${strings.size}\" uniqueCount=\"${strings.size}\">" +
        strings.keys.joinToString("") { "<si><t xml:space=\"preserve\">${esc(it)}</t></si>" } + "</sst>"
    return pack(linkedMapOf<String, Any>(
        "[Content_Types].xml" to XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "</Types>",
        "_rels/.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
        "xl/workbook.xml" to XML_HEAD +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
            "</Relationships>",
    ).apply { putAll(parts) })
}

private fun numberText(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

/* ---------- PowerPoint ---------- */

private const val P_NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/** A slide: either a full-slide picture, or a title with bullet points. */
sealed interface SlideContent {
    class Picture(val bytes: ByteArray, val png: Boolean, /** Points. */ val width: Float, val height: Float) : SlideContent
    class Text(val title: String, val bullets: List<String>) : SlideContent
}

private const val THEME = "<a:theme $P_NS name=\"Office\"><a:themeElements>" +
    "<a:clrScheme name=\"Office\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
    "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
    "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
    "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
    "<a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
    "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
    "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
    "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
    "</a:themeElements></a:theme>"

private const val EMPTY_TREE = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>"

/**
 * Writes a deck. Picture slides fill the slide; the deck takes the size of the first picture,
 * or 16:9 (960 x 540 pt) when the first slide is text.
 */
fun writePptx(slides: List<SlideContent>): ByteArray {
    require(slides.isNotEmpty()) { "There are no slides to save" }
    val firstPic = slides.first() as? SlideContent.Picture
    val cx = Math.round((firstPic?.width ?: 960f) * EMU_PER_PT)
    val cy = Math.round((firstPic?.height ?: 540f) * EMU_PER_PT)
    val parts = linkedMapOf<String, Any>()
    slides.forEachIndexed { i, s ->
        val n = i + 1
        val rels = StringBuilder("<Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
        val shapes = when (s) {
            is SlideContent.Picture -> {
                val ext = if (s.png) "png" else "jpg"
                val scale = minOf(cx / (s.width * EMU_PER_PT), cy / (s.height * EMU_PER_PT))
                val w = Math.round(s.width * EMU_PER_PT * scale); val h = Math.round(s.height * EMU_PER_PT * scale)
                parts["ppt/media/image$n.$ext"] = s.bytes
                rels.append("<Relationship Id=\"rId2\" Type=\"$REL/image\" Target=\"../media/image$n.$ext\"/>")
                "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $n\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                    "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                    "<p:spPr><a:xfrm><a:off x=\"${(cx - w) / 2}\" y=\"${(cy - h) / 2}\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
            }
            is SlideContent.Text -> {
                val m = (cx * 0.07).toLong()
                textBox(2, "Title", m, (cy * 0.06).toLong(), cx - 2 * m, (cy * 0.18).toLong(), listOf(s.title), 3600, bold = true, bullets = false) +
                    textBox(3, "Content", m, (cy * 0.28).toLong(), cx - 2 * m, (cy * 0.62).toLong(), s.bullets.ifEmpty { listOf("") }, 2400, bold = false, bullets = true)
            }
        }
        parts["ppt/slides/slide$n.xml"] = XML_HEAD +
            "<p:sld $P_NS><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$shapes" +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
        parts["ppt/slides/_rels/slide$n.xml.rels"] = XML_HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>"
    }
    val slideIds = slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>" }
    return pack(linkedMapOf<String, Any>(
        "[Content_Types].xml" to XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "</Types>",
        "_rels/.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>",
        "ppt/presentation.xml" to XML_HEAD +
            "<p:presentation $P_NS><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$slideIds</p:sldIdLst><p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
        "ppt/_rels/presentation.xml.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            slides.indices.joinToString("") { "<Relationship Id=\"rId${it + 2}\" Type=\"$REL/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${slides.size + 2}\" Type=\"$REL/theme\" Target=\"theme/theme1.xml\"/></Relationships>",
        "ppt/slideMasters/slideMaster1.xml" to XML_HEAD +
            "<p:sldMaster $P_NS>$EMPTY_TREE<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>",
        "ppt/slideMasters/_rels/slideMaster1.xml.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>",
        "ppt/slideLayouts/slideLayout1.xml" to XML_HEAD +
            "<p:sldLayout $P_NS type=\"blank\">$EMPTY_TREE<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>",
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>",
        "ppt/theme/theme1.xml" to XML_HEAD + THEME,
    ).apply { putAll(parts) })
}

private fun textBox(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, lines: List<String>, size: Int, bold: Boolean, bullets: Boolean): String {
    val paras = lines.joinToString("") { line ->
        val pPr = if (bullets && line.isNotBlank()) "<a:pPr marL=\"342900\" indent=\"-342900\"><a:buChar char=\"•\"/></a:pPr>" else "<a:pPr><a:buNone/></a:pPr>"
        "<a:p>$pPr<a:r><a:rPr lang=\"en-US\" sz=\"$size\"${if (bold) " b=\"1\"" else ""} dirty=\"0\"/><a:t>${esc(line)}</a:t></a:r></a:p>"
    }
    return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
        "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
        "<p:txBody><a:bodyPr wrap=\"square\"${if (!bullets) " anchor=\"ctr\"" else ""}/><a:lstStyle/>$paras</p:txBody></p:sp>"
}
