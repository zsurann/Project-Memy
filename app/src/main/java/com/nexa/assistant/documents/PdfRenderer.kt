package com.nexa.assistant.documents

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import java.io.ByteArrayOutputStream

/** PDF in formato A4 con un'impaginazione sobria: titolo, data, sezioni, elenchi, tabelle e numeri di pagina. */
object PdfRenderer {
    private const val W = 595
    private const val H = 842
    private const val MARGIN = 56f
    private const val CONTENT = W - 2 * MARGIN
    private const val BOTTOM = H - 64f

    private val INK = Color.rgb(0x1F, 0x22, 0x30)
    private val ACCENT = Color.rgb(0x24, 0x1A, 0x5E)
    private val VIOLET = Color.rgb(0x8B, 0x5C, 0xF6)
    private val MUTED = Color.rgb(0x8A, 0x8A, 0xA0)
    private val LINE = Color.rgb(0xD9, 0xD6, 0xE8)
    private val HEADER_BG = Color.rgb(0xF1, 0xEE, 0xFC)

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun styled(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in Markdown.runs(text)) {
            val start = sb.length
            sb.append(r.text)
            val style = when {
                r.bold && r.italic -> Typeface.BOLD_ITALIC
                r.bold -> Typeface.BOLD
                r.italic -> Typeface.ITALIC
                else -> null
            }
            style?.let { sb.setSpan(StyleSpan(it), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return sb
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Float, spacing: Float = 1.3f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    /** Dove finiscono le pagine: nel file PDF, oppure (nei test) in immagini. */
    interface Pages {
        fun start(number: Int, width: Int, height: Int): Canvas
        fun finish()
    }

    private class PdfPages(val doc: PdfDocument) : Pages {
        private var page: PdfDocument.Page? = null
        override fun start(number: Int, width: Int, height: Int): Canvas =
            doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create()).also { page = it }.canvas
        override fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }

    private class Writer(val pages: Pages) {
        private var current: Canvas? = null
        var number = 0
        var y = MARGIN
        val canvas: Canvas get() = current!!

        fun newPage() {
            finishPage()
            number++
            current = pages.start(number, W, H)
            y = MARGIN
        }

        fun finishPage() {
            val c = current ?: return
            val footer = paint(8.5f, MUTED)
            val label = "$number"
            c.drawText(label, W - MARGIN - footer.measureText(label), H - 34f, footer)
            pages.finish()
            current = null
        }

        fun ensure(height: Float) {
            if (current == null || y + height > BOTTOM) newPage()
        }

        /** Disegna un testo andando a capo e cambiando pagina quando serve, riga per riga. */
        fun text(l: StaticLayout, x: Float = MARGIN) {
            for (i in 0 until l.lineCount) {
                val top = l.getLineTop(i).toFloat()
                val bottom = l.getLineBottom(i).toFloat()
                ensure(bottom - top)
                canvas.save()
                canvas.translate(x, y - top)
                canvas.clipRect(0f, top, l.width.toFloat(), bottom)
                l.draw(canvas)
                canvas.restore()
                y += bottom - top
            }
        }
    }

    fun render(title: String?, blocks: List<Block>): ByteArray {
        val doc = PdfDocument()
        try {
            draw(title, blocks, PdfPages(doc))
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
        }
    }

    /** Impagina il documento sulle pagine date; restituisce il numero di pagine. */
    fun draw(title: String?, blocks: List<Block>, pages: Pages): Int {
        val w = Writer(pages)
        w.newPage()
        val body = paint(10.5f)
        title?.let {
            w.text(layout(it, paint(22f, ACCENT, bold = true), CONTENT, 1.15f))
            w.y += 4f
            w.text(layout(todayItalian(), paint(9f, MUTED), CONTENT))
            w.y += 10f
            w.canvas.drawRect(MARGIN, w.y, MARGIN + 42f, w.y + 2.5f, Paint().apply { color = VIOLET })
            w.y += 22f
        }
        for (b in blocks) when (b) {
            is Block.Heading -> {
                val p = paint(when (b.level) { 1 -> 16.5f; 2 -> 13.5f; else -> 11.5f }, ACCENT, bold = true)
                val l = layout(styled(b.text), p, CONTENT, 1.15f)
                w.y += if (b.level == 1) 14f else 10f
                // Un titolo non resta mai da solo in fondo alla pagina.
                w.ensure(l.height + 40f)
                w.text(l)
                w.y += 6f
            }
            is Block.Paragraph -> {
                w.text(layout(styled(b.text), body, CONTENT))
                w.y += 8f
            }
            is Block.Bullet -> {
                val marker = if (b.number != null) "${b.number}." else "•"
                val l = layout(styled(b.text), body, CONTENT - 18f)
                w.ensure(l.getLineBottom(0).toFloat())
                val markerPaint = if (b.number != null) paint(10.5f, ACCENT, bold = true) else paint(10.5f, VIOLET, bold = true)
                w.canvas.drawText(marker, MARGIN + 2f, w.y - markerPaint.ascent(), markerPaint)
                w.text(l, MARGIN + 18f)
                w.y += 4f
            }
            is Block.Table -> table(w, b)
            Block.Rule -> {
                w.y += 6f
                w.ensure(12f)
                w.canvas.drawRect(MARGIN, w.y, W - MARGIN, w.y + 0.8f, Paint().apply { color = LINE })
                w.y += 14f
            }
        }
        val count = w.number
        w.finishPage()
        return count
    }

    private fun table(w: Writer, t: Block.Table) {
        val cols = t.rows.maxOf { it.size }
        val colW = CONTENT / cols
        val pad = 6f
        val border = Paint().apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        val headerBg = Paint().apply { color = HEADER_BG }
        w.y += 4f
        t.rows.forEachIndexed { i, row ->
            val p = paint(9.5f, INK, bold = i == 0)
            val cells = (0 until cols).map { c -> layout(styled(row.getOrElse(c) { "" }), p, colW - 2 * pad, 1.2f) }
            val h = cells.maxOf { it.height } + 2 * pad
            w.ensure(h)
            val top = w.y
            if (i == 0) w.canvas.drawRect(MARGIN, top, W - MARGIN, top + h, headerBg)
            cells.forEachIndexed { c, l ->
                val x = MARGIN + c * colW
                w.canvas.save()
                w.canvas.translate(x + pad, top + pad)
                l.draw(w.canvas)
                w.canvas.restore()
                w.canvas.drawRect(x, top, x + colW, top + h, border)
            }
            w.y += h
        }
        w.y += 12f
    }
}
