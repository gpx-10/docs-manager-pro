package `in`.llmlabs.docsmanager.office

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * Reads the text boxes, pictures and coloured shapes of PowerPoint decks (.pptx), ported from
 * llmlabs.in (src/lib/office/pptx.ts and xml.ts). Simple regular-expression XML reading, enough
 * for the parts PowerPoint writes.
 */

class Box(val x: Float, val y: Float, val w: Float, val h: Float)
class TextRun(val text: String, val size: Float, val bold: Boolean, val italic: Boolean, val color: String)
class Paragraph(val runs: List<TextRun>, val align: String, val bullet: Boolean)

sealed interface Shape {
    val box: Box
    class Text(override val box: Box, val paragraphs: List<Paragraph>, val fill: String?, val anchor: String) : Shape
    class Picture(override val box: Box, val bytes: ByteArray, val png: Boolean) : Shape
    class Rect(override val box: Box, val fill: String) : Shape
}

class Slide(val background: String?, val shapes: List<Shape>)
class Deck(val width: Float, val height: Float, val slides: List<Slide>)

/** An unzipped Office file. */
internal class Package(private val files: Map<String, ByteArray>) {
    fun text(name: String): String? = files[name]?.toString(Charsets.UTF_8)
    fun bytes(name: String): ByteArray? = files[name]

    companion object {
        fun open(input: ByteArray, what: String): Package {
            val files = mutableMapOf<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(input)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory) files[e.name.removePrefix("/")] = zip.readBytes()
                    }
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("This is not a $what file, or it is damaged")
            }
            if (files.isEmpty()) throw IllegalArgumentException("This is not a $what file, or it is damaged")
            return Package(files)
        }
    }
}

internal fun unesc(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
    .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
    .replace("&amp;", "&")

/** Value of an attribute in a start tag; namespace prefixes must be included. */
internal fun attr(tag: String, name: String): String? =
    Regex("\\s${Regex.escape(name)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)?.let(::unesc)

internal class El(val open: String, val inner: String)

/** Every element with this tag name. Does not handle the same tag nested inside itself. */
internal fun elements(xml: String, tag: String): List<El> {
    val t = Regex.escape(tag)
    return Regex("<$t(?=[\\s/>])[^>]*?(/>|>([\\s\\S]*?)</$t>)").findAll(xml).map { m ->
        El(m.value.substring(0, m.value.indexOf('>') + 1), m.groups[2]?.value ?: "")
    }.toList()
}

internal fun first(xml: String, tag: String): El? = elements(xml, tag).firstOrNull()

internal class Rel(val target: String, val type: String)

/** A relationships part as a map of id to target, resolved against the folder of [from]. */
internal fun relationships(pkg: Package, from: String): Map<String, Rel> {
    val dir = from.substring(0, from.lastIndexOf('/') + 1)
    val name = "${dir}_rels/${from.substring(dir.length)}.rels"
    val map = mutableMapOf<String, Rel>()
    for (r in elements(pkg.text(name) ?: "", "Relationship")) {
        val id = attr(r.open, "Id") ?: continue
        val target = attr(r.open, "Target") ?: continue
        map[id] = Rel(resolvePath(dir, target), attr(r.open, "Type") ?: "")
    }
    return map
}

internal fun resolvePath(dir: String, target: String): String {
    if (target.startsWith("/")) return target.substring(1)
    val out = ArrayDeque<String>()
    for (p in (dir + target).split('/')) when (p) { ".." -> out.removeLastOrNull(); ".", "" -> Unit; else -> out.addLast(p) }
    return out.joinToString("/")
}

private const val EMU_PER_PT = 12700f
private fun pt(emu: String?) = (emu?.toFloatOrNull() ?: 0f) / EMU_PER_PT

private fun xfrmBox(xml: String): Box? {
    val x = first(xml, "a:xfrm") ?: return null
    val off = first(x.inner, "a:off") ?: return null
    val ext = first(x.inner, "a:ext") ?: return null
    return Box(pt(attr(off.open, "x")), pt(attr(off.open, "y")), pt(attr(ext.open, "cx")), pt(attr(ext.open, "cy")))
}

/** The colour of a fill, if it is a plain RGB or a common theme colour. */
private fun solidColour(xml: String?): String? {
    if (xml == null) return null
    val fill = first(xml, "a:solidFill") ?: return null
    first(fill.inner, "a:srgbClr")?.let { return attr(it.open, "val") }
    val scheme = attr(first(fill.inner, "a:schemeClr")?.open ?: "", "val") ?: return null
    return mapOf("bg1" to "FFFFFF", "lt1" to "FFFFFF", "tx1" to "000000", "dk1" to "000000", "accent1" to "4472C4", "accent2" to "ED7D31")[scheme]
}

private class Placeholder(val type: String, val idx: String)
private class PlaceholderBox(val ph: Placeholder, val box: Box?, val size: Float?)

private fun placeholderOf(shapeXml: String): Placeholder? =
    first(shapeXml, "p:ph")?.let { Placeholder(attr(it.open, "type") ?: "body", attr(it.open, "idx") ?: "") }

private fun placeholderBoxes(pkg: Package, part: String?): List<PlaceholderBox> {
    val xml = part?.let { pkg.text(it) } ?: return emptyList()
    return elements(xml, "p:sp").mapNotNull { sp ->
        val ph = placeholderOf(sp.inner) ?: return@mapNotNull null
        val def = first(sp.inner, "a:defRPr") ?: first(sp.inner, "a:rPr")
        PlaceholderBox(ph, xfrmBox(sp.inner), attr(def?.open ?: "", "sz")?.toFloatOrNull()?.div(100f))
    }
}

private fun findPlaceholder(list: List<PlaceholderBox>, ph: Placeholder): PlaceholderBox? {
    fun same(t: String) = when (t) { "ctrTitle" -> "title"; "subTitle" -> "body"; else -> t }
    return list.firstOrNull { ph.idx.isNotEmpty() && it.ph.idx == ph.idx } ?: list.firstOrNull { same(it.ph.type) == same(ph.type) }
}

private fun paragraphs(txBody: String, defaultSize: Float, isBody: Boolean): List<Paragraph> = elements(txBody, "a:p").map { p ->
    val pPr = first(p.inner, "a:pPr")
    val runs = (elements(p.inner, "a:r") + elements(p.inner, "a:fld")).mapNotNull { r ->
        val rPr = first(r.inner, "a:rPr")
        val text = unesc(first(r.inner, "a:t")?.inner ?: "")
        if (text.isEmpty()) return@mapNotNull null
        TextRun(
            text,
            attr(rPr?.open ?: "", "sz")?.toFloatOrNull()?.div(100f) ?: defaultSize,
            attr(rPr?.open ?: "", "b") == "1",
            attr(rPr?.open ?: "", "i") == "1",
            solidColour(rPr?.inner) ?: "000000",
        )
    }
    val algn = attr(pPr?.open ?: "", "algn")
    val noBullet = pPr != null && "<a:buNone" in pPr.inner
    val hasBullet = pPr != null && Regex("<a:bu(Char|AutoNum)").containsMatchIn(pPr.inner)
    Paragraph(runs, when (algn) { "ctr" -> "ctr"; "r" -> "r"; else -> "l" }, hasBullet || (isBody && !noBullet && runs.isNotEmpty()))
}

private fun defaultPlaceholderBox(ph: Placeholder, w: Float, h: Float): Box = when (ph.type) {
    "title", "ctrTitle" -> Box(w * 0.07f, h * 0.06f, w * 0.86f, h * 0.18f)
    "subTitle" -> Box(w * 0.12f, h * 0.55f, w * 0.76f, h * 0.2f)
    else -> Box(w * 0.07f, h * 0.28f, w * 0.86f, h * 0.62f)
}

fun readPptx(input: ByteArray): Deck {
    val pkg = Package.open(input, "PowerPoint (.pptx)")
    val pres = pkg.text("ppt/presentation.xml") ?: throw IllegalArgumentException("This is not a PowerPoint (.pptx) file")
    val size = first(pres, "p:sldSz")
    val width = pt(attr(size?.open ?: "", "cx") ?: "9144000")
    val height = pt(attr(size?.open ?: "", "cy") ?: "6858000")
    val presRels = relationships(pkg, "ppt/presentation.xml")

    val slides = mutableListOf<Slide>()
    for (id in elements(first(pres, "p:sldIdLst")?.inner ?: "", "p:sldId")) {
        val part = presRels[attr(id.open, "r:id") ?: ""]?.target ?: continue
        val xml = pkg.text(part) ?: continue
        val rels = relationships(pkg, part)
        val layout = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val master = layout?.let { l -> relationships(pkg, l).values.firstOrNull { it.type.endsWith("/slideMaster") }?.target }
        val layoutPh = placeholderBoxes(pkg, layout)
        val masterPh = placeholderBoxes(pkg, master)
        val shapes = mutableListOf<Shape>()

        // Shapes in drawing order. Group shapes are flattened; their child offsets are mapped into slide space.
        fun walk(tree: String, map: (Box) -> Box) {
            val re = Regex("<p:(sp|pic|grpSp)(?=[\\s>])[\\s\\S]*?</p:\\1>")
            var from = 0
            while (true) {
                val m = re.find(tree, from) ?: break
                var part0 = m.value
                from = m.range.last + 1
                when (m.groupValues[1]) {
                    "grpSp" -> {
                        // Re-match up to the balanced closing tag, since groups can nest.
                        var depth = 0
                        var end = m.range.first
                        for (t in Regex("<(/?)p:grpSp(?=[\\s>])").findAll(tree, m.range.first)) {
                            depth += if (t.groupValues[1].isNotEmpty()) -1 else 1
                            if (depth == 0) { end = tree.indexOf('>', t.range.first) + 1; break }
                        }
                        part0 = tree.substring(m.range.first, end)
                        from = end
                        val grpPr = first(part0, "p:grpSpPr")?.inner ?: ""
                        val x = first(grpPr, "a:xfrm")
                        val off = x?.let { first(it.inner, "a:off") }; val ext = x?.let { first(it.inner, "a:ext") }
                        val chOff = x?.let { first(it.inner, "a:chOff") }; val chExt = x?.let { first(it.inner, "a:chExt") }
                        var child = map
                        if (off != null && ext != null && chOff != null && chExt != null) {
                            val sx = pt(attr(ext.open, "cx")) / pt(attr(chExt.open, "cx")).let { if (it == 0f) 1f else it }
                            val sy = pt(attr(ext.open, "cy")) / pt(attr(chExt.open, "cy")).let { if (it == 0f) 1f else it }
                            val ox = pt(attr(off.open, "x")); val oy = pt(attr(off.open, "y"))
                            val cx = pt(attr(chOff.open, "x")); val cy = pt(attr(chOff.open, "y"))
                            child = { b -> map(Box(ox + (b.x - cx) * sx, oy + (b.y - cy) * sy, b.w * sx, b.h * sy)) }
                        }
                        val innerStart = part0.indexOf("</p:grpSpPr>").let { if (it < 0) 0 else it + 12 }
                        val innerEnd = part0.lastIndexOf("</p:grpSp>").let { if (it < innerStart) part0.length else it }
                        walk(part0.substring(innerStart, innerEnd), child)
                    }
                    "pic" -> {
                        val box = xfrmBox(part0) ?: continue
                        val target = rels[attr(first(part0, "a:blip")?.open ?: "", "r:embed") ?: ""]?.target ?: continue
                        val png = target.endsWith(".png", true)
                        if (!png && !Regex("\\.jpe?g$", RegexOption.IGNORE_CASE).containsMatchIn(target)) continue
                        val bytes = pkg.bytes(target) ?: continue
                        shapes += Shape.Picture(map(box), bytes, png)
                    }
                    else -> {
                        val ph = placeholderOf(part0)
                        if (ph != null && Regex("dt|ftr|sldNum").containsMatchIn(ph.type)) continue
                        val inherited = ph?.let { findPlaceholder(layoutPh, it) ?: findPlaceholder(masterPh, it) }
                        val masterSize = ph?.let { findPlaceholder(masterPh, it)?.size }
                        val box = xfrmBox(part0) ?: inherited?.box ?: ph?.let { defaultPlaceholderBox(it, width, height) } ?: continue
                        val fill = solidColour(first(part0, "p:spPr")?.inner)
                        val txBody = first(part0, "p:txBody")
                        val isTitle = ph?.type == "title" || ph?.type == "ctrTitle"
                        val defaultSize = inherited?.size ?: masterSize ?: if (isTitle) 40f else if (ph != null) 24f else 18f
                        val isBody = ph?.type == "body" || (ph != null && ph.type != "title" && ph.type != "ctrTitle" && ph.type != "subTitle" && ph.idx.isNotEmpty())
                        val paras = txBody?.let { paragraphs(it.inner, defaultSize, isBody && !isTitle) } ?: emptyList()
                        if (paras.any { it.runs.isNotEmpty() }) {
                            val anchor = attr(first(txBody!!.inner, "a:bodyPr")?.open ?: "", "anchor")
                            shapes += Shape.Text(map(box), paras, fill, when (anchor) { "ctr" -> "ctr"; "b" -> "b"; else -> if (isTitle) "ctr" else "t" })
                        } else if (fill != null) {
                            shapes += Shape.Rect(map(box), fill)
                        }
                    }
                }
            }
        }
        walk(first(xml, "p:spTree")?.inner ?: "", { it })
        slides += Slide(solidColour(first(xml, "p:bg")?.inner), shapes)
    }
    if (slides.isEmpty()) throw IllegalArgumentException("This presentation has no slides")
    return Deck(width, height, slides)
}
