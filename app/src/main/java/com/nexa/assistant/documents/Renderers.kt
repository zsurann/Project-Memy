package com.nexa.assistant.documents

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun todayItalian(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN))

/** Pagina web autonoma, con uno stile pulito. */
object HtmlRenderer {
    private fun inline(text: String) = Markdown.runs(text).joinToString("") { r ->
        var s = esc(r.text)
        if (r.italic) s = "<em>$s</em>"
        if (r.bold) s = "<strong>$s</strong>"
        s
    }

    fun render(title: String?, blocks: List<Block>): String = buildString {
        append("<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(esc(title ?: "Documento")).append("</title><style>")
        append("body{font:16px/1.6 -apple-system,'Segoe UI',Roboto,sans-serif;color:#1f2230;max-width:760px;margin:48px auto;padding:0 20px}")
        append("h1,h2,h3{color:#241a5e;line-height:1.25}h1{font-size:30px;margin:0 0 6px}.date{color:#8a8aa0;font-size:13px;margin-bottom:28px}")
        append("hr{border:0;border-top:1px solid #e3e0f0;margin:24px 0}table{border-collapse:collapse;width:100%;margin:16px 0}")
        append("th,td{border:1px solid #d9d6e8;padding:8px 10px;text-align:left}th{background:#f1eefc}")
        append("</style></head><body>")
        title?.let { append("<h1>").append(esc(it)).append("</h1><div class=\"date\">").append(todayItalian()).append("</div>") }
        var list: String? = null
        fun closeList() { list?.let { append("</$it>") }; list = null }
        for (b in blocks) {
            if (b !is Block.Bullet) closeList()
            when (b) {
                is Block.Heading -> append("<h${b.level + if (title != null) 1 else 0}>").append(inline(b.text)).append("</h${b.level + if (title != null) 1 else 0}>")
                is Block.Paragraph -> append("<p>").append(inline(b.text)).append("</p>")
                is Block.Bullet -> {
                    val tag = if (b.number != null) "ol" else "ul"
                    if (list != tag) { closeList(); append("<$tag>"); list = tag }
                    append("<li>").append(inline(b.text)).append("</li>")
                }
                is Block.Table -> {
                    append("<table>")
                    b.rows.forEachIndexed { i, r ->
                        val cell = if (i == 0) "th" else "td"
                        append("<tr>"); r.forEach { append("<$cell>").append(inline(it)).append("</$cell>") }; append("</tr>")
                    }
                    append("</table>")
                }
                Block.Rule -> append("<hr>")
            }
        }
        closeList()
        append("</body></html>")
    }
}

/** Documento Word (.docx) essenziale, che si apre con Word, Google Documenti, WPS e simili. */
object DocxRenderer {
    private const val ACCENT = "241A5E"
    private const val TEXT = "1F2230"

    private fun run(r: Run, size: Int, color: String, forceBold: Boolean = false) = buildString {
        append("<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>")
        if (r.bold || forceBold) append("<w:b/>")
        if (r.italic) append("<w:i/>")
        append("<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/></w:rPr>")
        append("<w:t xml:space=\"preserve\">").append(esc(r.text)).append("</w:t></w:r>")
    }

    private fun para(text: String, size: Int = 22, color: String = TEXT, bold: Boolean = false, before: Int = 0, after: Int = 160, indent: String = "", prefix: String = "") = buildString {
        append("<w:p><w:pPr><w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"300\" w:lineRule=\"auto\"/>$indent</w:pPr>")
        if (prefix.isNotEmpty()) append(run(Run(prefix), size, color))
        Markdown.runs(text).forEach { append(run(it, size, color, bold)) }
        append("</w:p>")
    }

    fun render(title: String?, blocks: List<Block>): ByteArray {
        val body = StringBuilder()
        title?.let {
            body.append(para(it, size = 48, color = ACCENT, bold = true, after = 60))
            body.append(para(todayItalian(), size = 18, color = "8A8AA0", after = 360))
        }
        for (b in blocks) when (b) {
            is Block.Heading -> body.append(
                para(b.text, size = when (b.level) { 1 -> 36; 2 -> 30; else -> 25 }, color = ACCENT, bold = true, before = 280, after = 120)
            )
            is Block.Paragraph -> body.append(para(b.text))
            is Block.Bullet -> body.append(
                para(b.text, after = 80, indent = "<w:ind w:left=\"567\" w:hanging=\"283\"/>", prefix = if (b.number != null) "${b.number}.\t" else "•\t")
            )
            is Block.Table -> {
                val cols = b.rows.maxOf { it.size }
                body.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                    body.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D9D6E8\"/>")
                }
                body.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
                repeat(cols) { body.append("<w:gridCol/>") }
                body.append("</w:tblGrid>")
                b.rows.forEachIndexed { i, r ->
                    body.append("<w:tr>")
                    for (c in 0 until cols) {
                        body.append("<w:tc><w:tcPr>")
                        if (i == 0) body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F1EEFC\"/>")
                        body.append("</w:tcPr>").append(para(r.getOrElse(c) { "" }, size = 20, bold = i == 0, after = 40, before = 40)).append("</w:tc>")
                    }
                    body.append("</w:tr>")
                }
                body.append("</w:tbl>").append(para("", after = 120))
            }
            Block.Rule -> body.append(
                "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"D9D6E8\"/></w:pBdr></w:pPr></w:p>"
            )
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in listOf("[Content_Types].xml" to contentTypes, "_rels/.rels" to rels, "word/document.xml" to document)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Il testo di un file Word qualsiasi, un paragrafo per riga. */
    fun extractText(bytes: ByteArray): String {
        val xml = ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" } ?: return ""
            zip.readBytes().toString(Charsets.UTF_8)
        }
        return xml.split("</w:p>").joinToString("\n") { p ->
            Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>|<w:tab/>").findAll(p).joinToString("") { m ->
                if (m.value == "<w:tab/>") "\t" else m.groupValues[1]
            }
        }.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim()
    }
}
