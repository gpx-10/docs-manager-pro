package `in`.llmlabs.docsmanager.office

import android.util.Base64

/**
 * Turns a Word document (.docx) into simple HTML that keeps headings, bold, italic, underline,
 * text sizes and colours, alignment, lists, tables, line and page breaks, and pictures, so MuPDF's
 * HTML engine can lay it out. Like llmlabs.in (which uses mammoth), exact fonts, headers and
 * footers are not kept.
 */
fun docxToHtml(input: ByteArray): String {
    val pkg = Package.open(input, "Word (.docx)")
    val doc = pkg.text("word/document.xml") ?: throw IllegalArgumentException("This is not a Word (.docx) file")
    val rels = relationships(pkg, "word/document.xml")
    val styles = headingStyles(pkg.text("word/styles.xml") ?: "")
    val body = first(doc, "w:body")?.inner ?: ""
    val out = StringBuilder()
    var list: String? = null // "ul" or "ol" while inside a list

    fun closeList() { list?.let { out.append("</$it>") }; list = null }

    for (part in topLevel(body)) {
        if (part.startsWith("<w:tbl")) { closeList(); out.append(table(part, pkg, rels, styles)); continue }
        val p = paragraph(part, pkg, rels, styles)
        if (p.listType != null) {
            if (list != p.listType) { closeList(); out.append("<${p.listType}>"); list = p.listType }
            out.append("<li>").append(p.inner).append("</li>")
        } else {
            closeList()
            out.append(p.html)
        }
    }
    closeList()
    if (out.isBlank()) throw IllegalArgumentException("This Word file has no content")
    return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>$out</body></html>"
}

/** The top-level paragraphs and tables of a body, in order (tables may contain paragraphs and tables). */
private fun topLevel(xml: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    val start = Regex("<w:(p|tbl)(?=[\\s>/])")
    while (true) {
        val m = start.find(xml, i) ?: break
        val tag = m.groupValues[1]
        val selfClose = xml.indexOf('>', m.range.first).let { it > 0 && xml[it - 1] == '/' }
        if (selfClose) { i = xml.indexOf('>', m.range.first) + 1; continue }
        // Find the matching close tag, counting nested elements of the same name.
        var depth = 0
        var end = -1
        for (t in Regex("<(/?)w:$tag(?=[\\s>/])").findAll(xml, m.range.first)) {
            val close = t.groupValues[1].isNotEmpty()
            val gt = xml.indexOf('>', t.range.first)
            if (!close && xml[gt - 1] == '/') continue
            depth += if (close) -1 else 1
            if (depth == 0) { end = gt + 1; break }
        }
        if (end < 0) break
        out += xml.substring(m.range.first, end)
        i = end
    }
    return out
}

/** Style ids that are headings (Heading1..6, Title, Subtitle) mapped to an HTML level. */
private fun headingStyles(xml: String): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    for (s in elements(xml, "w:style")) {
        val id = attr(s.open, "w:styleId") ?: continue
        val name = attr(first(s.inner, "w:name")?.open ?: "", "w:val")?.lowercase() ?: ""
        val level = Regex("heading\\s*(\\d)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: attr(first(s.inner, "w:outlineLvl")?.open ?: "", "w:val")?.toIntOrNull()?.plus(1)
        when {
            name == "title" -> map[id] = 1
            name == "subtitle" -> map[id] = 2
            level != null && level in 1..6 -> map[id] = level
        }
    }
    return map
}

private class Para(val html: String, val inner: String, val listType: String?)

private fun paragraph(xml: String, pkg: Package, rels: Map<String, Rel>, styles: Map<String, Int>): Para {
    val pPr = first(xml, "w:pPr")?.inner ?: ""
    val styleId = attr(first(pPr, "w:pStyle")?.open ?: "", "w:val") ?: ""
    val level = styles[styleId] ?: Regex("^(Heading|heading)(\\d)$").find(styleId)?.groupValues?.get(2)?.toIntOrNull()
        ?: if (styleId.equals("Title", true)) 1 else null
    val align = when (attr(first(pPr, "w:jc")?.open ?: "", "w:val")) { "center" -> "center"; "right", "end" -> "right"; "both" -> "justify"; else -> null }
    val numbered = first(pPr, "w:numPr") != null || styleId.startsWith("List", true)
    val inner = runs(xml.replace(Regex("<w:pPr>[\\s\\S]*?</w:pPr>"), ""), pkg, rels)
    val style = align?.let { " style=\"text-align:$it\"" } ?: ""
    val tag = if (level != null) "h${level.coerceIn(1, 6)}" else "p"
    val listType = if (numbered && level == null) (if (Regex("Number|decimal", RegexOption.IGNORE_CASE).containsMatchIn(xml + styleId)) "ol" else "ul") else null
    val html = if (inner.isBlank() && level == null) "<p>&nbsp;</p>" else "<$tag$style>$inner</$tag>"
    return Para(html, inner, listType)
}

/** The runs, links, breaks and pictures of a paragraph as inline HTML. */
private fun runs(xml: String, pkg: Package, rels: Map<String, Rel>): String {
    val out = StringBuilder()
    for (m in Regex("<w:r(?=[\\s>])[\\s\\S]*?</w:r>|<w:br[^>]*/>").findAll(xml)) {
        val r = m.value
        if (r.startsWith("<w:br")) { out.append(if ("w:type=\"page\"" in r) "<div style=\"page-break-after:always\"></div>" else "<br/>"); continue }
        val rPr = first(r, "w:rPr")?.inner ?: ""
        fun on(tag: String) = first(rPr, tag)?.let { attr(it.open, "w:val") !in setOf("0", "false", "none") } ?: false
        val content = StringBuilder()
        for (piece in Regex("<w:t(?=[\\s>])[^>]*>([\\s\\S]*?)</w:t>|<w:tab\\s*/>|<w:br([^>]*)/>|<w:drawing>[\\s\\S]*?</w:drawing>").findAll(r)) {
            val v = piece.value
            when {
                v.startsWith("<w:t") -> content.append(esc(unesc(piece.groupValues[1])))
                v.startsWith("<w:tab") -> content.append("&#160;&#160;&#160;&#160;")
                v.startsWith("<w:br") -> content.append(if ("page" in piece.groupValues[2]) "<div style=\"page-break-after:always\"></div>" else "<br/>")
                else -> content.append(picture(v, pkg, rels))
            }
        }
        if (content.isEmpty()) continue
        val css = buildList {
            attr(first(rPr, "w:sz")?.open ?: "", "w:val")?.toFloatOrNull()?.let { add("font-size:${it / 2}pt") }
            attr(first(rPr, "w:color")?.open ?: "", "w:val")?.takeIf { Regex("^[0-9A-Fa-f]{6}$").matches(it) && it != "000000" }?.let { add("color:#$it") }
        }
        var html = content.toString()
        if (on("w:b")) html = "<b>$html</b>"
        if (on("w:i")) html = "<i>$html</i>"
        if (on("w:u")) html = "<u>$html</u>"
        if (css.isNotEmpty()) html = "<span style=\"${css.joinToString(";")}\">$html</span>"
        out.append(html)
    }
    return out.toString()
}

private fun picture(drawing: String, pkg: Package, rels: Map<String, Rel>): String {
    val id = attr(first(drawing, "a:blip")?.open ?: "", "r:embed") ?: return ""
    val target = rels[id]?.target ?: return ""
    val bytes = pkg.bytes(target) ?: return ""
    val mime = when (target.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; else -> return "" }
    val ext = first(drawing, "wp:extent")
    val w = attr(ext?.open ?: "", "cx")?.toLongOrNull()?.let { it / 12700f }
    val size = w?.let { " style=\"width:${it}pt\"" } ?: ""
    return "<img$size src=\"data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"/>"
}

private fun table(xml: String, pkg: Package, rels: Map<String, Rel>, styles: Map<String, Int>): String {
    val inner = xml.substring(xml.indexOf('>') + 1, xml.lastIndexOf("</w:tbl>"))
    val rows = StringBuilder()
    for (tr in topLevelOf(inner, "w:tr")) {
        rows.append("<tr>")
        for (tc in topLevelOf(tr.substring(tr.indexOf('>') + 1), "w:tc")) {
            val span = attr(first(tc, "w:gridSpan")?.open ?: "", "w:val")?.toIntOrNull()?.takeIf { it > 1 }?.let { " colspan=\"$it\"" } ?: ""
            val cell = topLevel(tc.substring(tc.indexOf('>') + 1)).joinToString("") { part ->
                if (part.startsWith("<w:tbl")) table(part, pkg, rels, styles) else paragraph(part, pkg, rels, styles).inner.let { if (it.isBlank()) "" else "<div>$it</div>" }
            }
            rows.append("<td$span>").append(cell.ifEmpty { "&#160;" }).append("</td>")
        }
        rows.append("</tr>")
    }
    return "<table>$rows</table>"
}

/** Top-level elements named [tag] in [xml], counting nesting. */
private fun topLevelOf(xml: String, tag: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var startAt = -1
    for (t in Regex("<(/?)${Regex.escape(tag)}(?=[\\s>/])").findAll(xml)) {
        val close = t.groupValues[1].isNotEmpty()
        val gt = xml.indexOf('>', t.range.first)
        if (!close && xml[gt - 1] == '/') continue
        if (!close) { if (depth == 0) startAt = t.range.first; depth++ }
        else { depth--; if (depth == 0 && startAt >= 0) { out += xml.substring(startAt, gt + 1); startAt = -1 } }
    }
    return out
}
