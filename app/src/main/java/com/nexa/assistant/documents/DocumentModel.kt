package com.nexa.assistant.documents

/**
 * Il contenuto di un documento, scritto da Claude in markdown semplice:
 * `# Titolo`, `## Sezione`, `- punto`, `1. punto`, tabelle `| a | b |`, `---`, **grassetto** e *corsivo*.
 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String, val number: Int? = null) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

/** Un pezzo di testo con il suo stile. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false)

object Markdown {
    private val heading = Regex("^(#{1,3})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*•]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val tableSeparator = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

    fun parse(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        val paragraph = StringBuilder()
        val table = ArrayList<List<String>>()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) out += Block.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        fun flushTable() {
            if (table.isNotEmpty()) out += Block.Table(table.toList())
            table.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("|") && trimmed.length > 1) {
                flushParagraph()
                if (!tableSeparator.matches(trimmed)) {
                    table += trimmed.trim('|').split("|").map { it.trim() }
                }
                continue
            }
            flushTable()
            when {
                trimmed.isEmpty() -> flushParagraph()
                trimmed == "---" || trimmed == "***" -> { flushParagraph(); out += Block.Rule }
                heading.matches(trimmed) -> {
                    flushParagraph()
                    val m = heading.find(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }
                bullet.matches(line) -> { flushParagraph(); out += Block.Bullet(bullet.find(line)!!.groupValues[1].trim()) }
                numbered.matches(line) -> {
                    flushParagraph()
                    val m = numbered.find(line)!!
                    out += Block.Bullet(m.groupValues[2].trim(), m.groupValues[1].toInt())
                }
                else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(trimmed) }
            }
        }
        flushParagraph()
        flushTable()
        return out
    }

    private val inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(.+?)\\*|_(.+?)_")

    /** Divide una riga in pezzi normali, in grassetto e in corsivo. */
    fun runs(text: String): List<Run> {
        val out = ArrayList<Run>()
        var last = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > last) out += Run(text.substring(last, m.range.first))
            when {
                m.groups[1] != null -> out += Run(m.groupValues[1], bold = true)
                m.groups[2] != null -> out += Run(m.groupValues[2], bold = true)
                m.groups[3] != null -> out += Run(m.groupValues[3], italic = true)
                else -> out += Run(m.groupValues[4], italic = true)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Run(text.substring(last))
        return out.ifEmpty { listOf(Run("")) }
    }

    fun plain(text: String) = runs(text).joinToString("") { it.text }

    /** Testo semplice, per i file .txt. */
    fun toPlainText(title: String?, blocks: List<Block>): String = buildString {
        title?.let { append(it).append("\n").append("=".repeat(it.length.coerceAtMost(60))).append("\n\n") }
        for (b in blocks) when (b) {
            is Block.Heading -> append(plain(b.text).let { if (b.level == 1) it.uppercase() else it }).append("\n\n")
            is Block.Paragraph -> append(plain(b.text)).append("\n\n")
            is Block.Bullet -> append(if (b.number != null) "${b.number}. " else "• ").append(plain(b.text)).append("\n")
            is Block.Table -> {
                val widths = IntArray(b.rows.maxOf { it.size }) { c -> b.rows.maxOf { r -> plain(r.getOrElse(c) { "" }).length } }
                b.rows.forEach { r -> append(widths.indices.joinToString("  ") { c -> plain(r.getOrElse(c) { "" }).padEnd(widths[c]) }.trimEnd()).append("\n") }
                append("\n")
            }
            Block.Rule -> append("\n").append("—".repeat(30)).append("\n\n")
        }
    }.trimEnd() + "\n"
}
